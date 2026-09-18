package com.luxera.companion.behavior;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.life.LifeRuntime;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.phone.PhoneNotificationRepository;
import com.luxera.companion.phone.PhoneState;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.proactive.ProactiveDecision;
import com.luxera.companion.proactive.ProactiveEngine;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.sleep.SleepModel;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.world.WorldEvent;
import com.luxera.companion.world.WorldEventEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * §三十四~§四十一 BehaviorEngine: 数字人的中央行为选择器。
 *
 * 世界每时每刻都在发生事件(时间/生活/身体/关系/记忆/意图/外界),
 * BehaviorEngine 每隔一段时间或收到事件时问: "现在我最可能做什么?"
 *
 * 流程: 状态收集 → 候选生成 → 效用打分(价值 - 打断成本 + 关系/人格修正 + 随机扰动)
 *       → 概率化选择(轮盘赌) → 约束校验 → 执行。
 *
 * 主动联系用户只是候选之一: 她今天可能更想睡觉、看手机、联系朋友、或者只是继续生活。
 * 这是"真人的主动性", 而不是"Proactive 模块定时发消息"。
 */
@Slf4j
@Service
public class BehaviorEngine {

    /** 选择温度: 越小越倾向最高分候选; 越大越随机 */
    private static final double TEMPERATURE = 0.5;
    /** 随机扰动幅度(行为熵: 规律是统计规律, 不决定每次行为) */
    private static final double JITTER_AMPLITUDE = 0.22;

    private final CompanionRepository companionRepo;
    private final PersonaService personaService;
    private final AgentStateService agentStateService;
    private final RelationshipService relationshipService;
    private final SleepModel sleepModel;
    private final CompanionSchedule schedule;
    private final ProactiveEngine proactiveEngine;
    private final PhoneStateService phoneStateService;
    private final PhoneNotificationRepository phoneNotificationRepo;
    private final ChatWorldPort chatWorld;
    private final LifeRuntime lifeRuntime;
    private final WorldEventEngine worldEventEngine;
    private final AvailabilityService availabilityService;
    private final com.luxera.companion.plan.PlanService planService;
    private final Random random = new Random();

    public BehaviorEngine(CompanionRepository companionRepo, PersonaService personaService,
                          AgentStateService agentStateService, RelationshipService relationshipService,
                          SleepModel sleepModel, CompanionSchedule schedule, ProactiveEngine proactiveEngine,
                          PhoneStateService phoneStateService, PhoneNotificationRepository phoneNotificationRepo,
                          ChatWorldPort chatWorld, LifeRuntime lifeRuntime,
                          WorldEventEngine worldEventEngine, AvailabilityService availabilityService,
                          com.luxera.companion.plan.PlanService planService) {
        this.companionRepo = companionRepo;
        this.personaService = personaService;
        this.agentStateService = agentStateService;
        this.relationshipService = relationshipService;
        this.sleepModel = sleepModel;
        this.schedule = schedule;
        this.proactiveEngine = proactiveEngine;
        this.phoneStateService = phoneStateService;
        this.phoneNotificationRepo = phoneNotificationRepo;
        this.chatWorld = chatWorld;
        this.lifeRuntime = lifeRuntime;
        this.worldEventEngine = worldEventEngine;
        this.availabilityService = availabilityService;
        this.planService = planService;
    }

    /**
     * 对所有伴侣做一次行为评估(行为 Tick) —— 全世界推一次, 老链的定时入口。
     *
     * <p>返回值是每个 agent 的结果, 而不是 {@code void}: V11 Phase 5 的 dual run 需要
     * 把"老链这一轮真的做了什么"与"新链算出来她会做什么"逐条对上。没有返回值的话,
     * shadow 只能记新链那一半, 而"差异"这个词需要两边都存在。
     */
    @Transactional
    public List<BehaviorOutcome> evaluateAll(LocalDateTime now) {
        List<BehaviorOutcome> outcomes = new ArrayList<>();
        for (Companion c : companionRepo.findRunnable()) {
            if (c.getDeletedAt() != null) continue;
            try {
                prepare(c.getId(), now);
                BehaviorOutcome outcome = evaluate(c, now, "TIME_TICK");
                if (outcome != null) {
                    outcomes.add(outcome);
                }
            } catch (Exception e) {
                log.debug("[BehaviorEngine] {} 评估失败: {}", c.getId(), e.getMessage());
            }
        }
        return outcomes;
    }

    /**
     * 世界往前走带来的那些<b>事实更新</b> —— 与"她做什么"无关, 所以归触发器做, 不归消费者。
     *
     * <p>抽出来的理由很具体: V11 切流之后 {@code evaluateAll} 不再被调用, 而
     * §三十九 的关系维护压力如果跟着它一起消失, 后果是"沉默越久越想联系"这条规则
     * 静默失效 —— 不报错, 只表现为她再也不主动找人。这种故障靠读代码很难发现,
     * 所以把它单独提成一个名字, 让"切流时漏掉了什么"变成一次可以逐行对照的检查。
     */
    @Transactional
    public void prepare(String companionId, LocalDateTime now) {
        Companion c = companionRepo.findById(companionId).orElse(null);
        if (c == null || c.getUserId() == null) {
            return;
        }
        relationshipService.decayConnectionPressure(c.getUserId(), companionId, now);
    }

    /** 对单个伴侣做行为评估并执行选中的行为 */
    @Transactional
    public BehaviorOutcome evaluate(String companionId, LocalDateTime now, String reason) {
        Companion c = companionRepo.findById(companionId).orElse(null);
        if (c == null) return null;
        return evaluate(c, now, reason);
    }

    /**
     * 评估并执行 —— 这是"她真的动了"。V11 的消费者在 {@code enabled} 档调它。
     */
    @Transactional
    public BehaviorOutcome evaluate(Companion c, LocalDateTime now, String reason) {
        BehaviorCandidate selected = select(c, now);
        if (selected == null) {
            return null;
        }
        return execute(c, selected, now, reason);
    }

    /** 按 id 评估并执行。 */
    @Transactional
    public BehaviorOutcome evaluateById(String companionId, LocalDateTime now, String reason) {
        Companion c = companionRepo.findById(companionId).orElse(null);
        return c == null ? null : evaluate(c, now, reason);
    }

    /**
     * <b>只选, 不做</b> —— V11 §25.2 的 shadow 期要的就是这一半。
     *
     * <p>抽出来的原因不是"代码好看", 而是一个 shadow 期必须回答的问题:
     * <b>"她本来会做什么"</b>。原来的 {@code evaluate} 边选边做, 所以想观察它就必然
     * 会改变世界 —— 于是"观察"这个词在本类上原本是不成立的。拆开之后:
     * <pre>
     *   shadow   → select()  算出一个候选, 记一笔, 到此为止
     *   enabled  → evaluate() 选出来并让它发生
     * </pre>
     * 两者的<b>选择逻辑是同一条</b>(同一个方法), 所以 shadow 记录下来的东西与切流后
     * 真的会发生的事情是同一个答案 —— 这正是一个 shadow 唯一有用的性质。
     *
     * <p>注意 {@code select} 只读不写: 步骤 1 的状态收集里都是读
     * (睡意、关系、手机状态都是"现在是什么样"), 唯一的例外是
     * {@code sleepModel.sleepPropensity} 这类会推进模型的调用 —— 它们与
     * {@code BehaviorTickJob} 今天在干的事一模一样, 所以不引入新的副作用。
     */
    @Transactional
    public BehaviorCandidate select(String companionId, LocalDateTime now) {
        Companion c = companionRepo.findById(companionId).orElse(null);
        return c == null ? null : select(c, now);
    }

    /** 生成候选、打分、选一个。不执行。 */
    @Transactional
    public BehaviorCandidate select(Companion c, LocalDateTime now) {
        String companionId = c.getId();
        String userId = c.getUserId();

        // ── 1. 状态收集 ─────────────────────────────
        AgentState state = agentStateService.get(companionId);
        Relationship rel = relationshipService.find(userId, companionId);
        boolean sleeping = sleepModel.currentSleeping(companionId, now);
        Persona persona = safePersona(companionId);
        // 人格
        double sociability = trait(persona, "sociability", 0.5);
        double initiative = persona != null && persona.getCommunication() != null
                ? persona.getCommunication().getInitiative() : 0.5;

        // 关系状态
        double intimacy = rel != null ? rel.getIntimacy() : 0;
        double affection = rel != null ? rel.getAffection() : 0;
        double tension = rel != null ? rel.getTension() : 0;
        double connectionPressure = rel != null ? rel.getConnectionPressure() : 0;

        // 最近互动(几点/几分钟前)
        Long minutesSinceContact = rel != null && rel.getLastInteractionAt() != null
                ? java.time.Duration.between(rel.getLastInteractionAt(), now).toMinutes() : null;

        // ── 2. 候选生成 ─────────────────────────────
        List<BehaviorCandidate> candidates = new ArrayList<>();

        // 2.1 主动联系用户候选(ProactiveEngine 的价值-成本竞争作为候选之一)
        if (!sleeping && !dnd(now)) {
            ProactiveDecision proactive = proactiveEngine.decide(c, now,
                    rel != null ? rel.getLastInteractionAt() : null,
                    proactiveEngine.lastProactive(c.getId()), true);
            if (proactive.act()) {
                double pScore = proactive.expectedValue() - proactive.interruptionCost()
                        + intimacy * 0.15 + affection * 0.1 + connectionPressure * 0.3
                        + (initiative - 0.5) * 0.4;
                candidates.add(BehaviorCandidate.proactive(proactive, pScore));
            }
        }

        // 2.2 睡眠候选(睡觉是行为, 不是时刻表)
        if (!sleeping) {
            double motivation = engagement(state, minutesSinceContact);
            double propensity = sleepModel.sleepPropensity(companionId, now, motivation, engagement(state, minutesSinceContact));
            if (propensity >= 0.55) {
                double sleepScore = (propensity - 0.5) * 2.0 - motivation * 0.6 + (0.5 - intimacy) * 0.1;
                candidates.add(BehaviorCandidate.of(BehaviorAction.SLEEP, "困意上来了", sleepScore));
            }
        } else {
            candidates.add(BehaviorCandidate.of(BehaviorAction.CONTINUE_ACTIVITY, "在睡", 0.8));
        }

        // 2.3 看手机候选(有未读通知 & 闲着)
        if (!sleeping) {
            long unread = phoneNotificationRepo.countByCompanionIdAndReadFalse(companionId);
            PhoneState phone = phoneStateService.current(companionId, now);
            double idleFactor = idleFactor(schedule.activityFor(companionId, now));
            if (unread > 0 && phone != null && !phone.isDoNotDisturb()) {
                double checkScore = 0.3 * idleFactor + Math.min(0.4, unread * 0.05)
                        + (0.5 - intimacy) * 0.1 + connectionPressure * 0.2;
                candidates.add(BehaviorCandidate.of(BehaviorAction.CHECK_PHONE, "手机好像响了", checkScore));
            }
        }

        // 2.4 与其他人物互动(社会网络; 内向/外向差异)
        if (!sleeping) {
            double loneliness = state != null ? state.getLoneliness() : 0;
            double contactPenalty = minutesSinceContact != null && minutesSinceContact < 180 ? 0.3 : 0;
            double otherScore = sociability * 0.45 + loneliness * 0.35
                    + (0.4 - intimacy) * 0.15 - contactPenalty;
            if (otherScore > 0.35) {
                candidates.add(BehaviorCandidate.of(BehaviorAction.CONTACT_OTHER_PERSON,
                        "和朋友们聚聚", otherScore));
            }
        }

        // 2.5 想起某事(好奇心/未完成事项)
        if (!sleeping && state != null && state.getCuriosity() > 0.55) {
            candidates.add(BehaviorCandidate.of(BehaviorAction.RECALL_MEMORY,
                    "突然想起点事", state.getCuriosity() * 0.5 - 0.1));
        }

        // 2.6 默认候选: 继续生活 / 什么都不做
        double continueScore = 0.55 + (sleeping ? 0 : (0.5 - intimacy) * 0.1) + randomJitter();
        candidates.add(BehaviorCandidate.of(BehaviorAction.CONTINUE_ACTIVITY, "继续手头的事", continueScore));
        candidates.add(BehaviorCandidate.of(BehaviorAction.DO_NOTHING, "发会呆", 0.35 + randomJitter()));

        // ── 3. 效用修正 + 随机扰动 ──────────────────
        for (int i = 0; i < candidates.size(); i++) {
            BehaviorCandidate bc = candidates.get(i);
            double adjusted = bc.score()
                    + personalityModifier(bc.action(), sociability, initiative)
                    + tensionModifier(bc.action(), tension)
                    + entropyJitter(c.getId());
            candidates.set(i, new BehaviorCandidate(bc.action(), bc.trigger(), adjusted,
                    bc.baseUtility(), bc.interruptionCost(), bc.proactive()));
        }

        // ── 4. 概率化选择(轮盘赌 + 温度) ────────────
        return stochasticSelect(candidates);
    }

    /** 执行选中的行为 */
    @Transactional
    public BehaviorOutcome execute(Companion c, BehaviorCandidate selected, LocalDateTime now, String reason) {
        String companionId = c.getId();
        BehaviorOutcome decision = new BehaviorOutcome(selected.action(), selected.trigger(),
                selected.score(), reason, now);

        switch (selected.action()) {
            case SEND_PROACTIVE_MESSAGE -> {
                if (selected.proactive() != null && selected.proactive().act()) {
                    proactiveEngine.execute(c, selected.proactive(), now);
                    log.info("[行为] {} 主动联系用户 ({}, 得分{})", c.getName(),
                            selected.proactive().trigger(), round(selected.score()));
                }
            }
            case SLEEP -> {
                if (!sleepModel.isSleeping(companionId, now)) {
                    sleepModel.fallAsleep(companionId, now, "NATURAL");
                    worldEventEngine.publish(companionId, WorldEventEngine.TYPE_SLEEP_STATE_CHANGED,
                            WorldEvent.SRC_BODY, companionId, null,
                            Map.of("sleeping", true, "reason", "behavior"), 0.4);
                    log.info("[行为] {} 去睡了 (得分{})", c.getName(), round(selected.score()));
                }
            }
            case CHECK_PHONE -> {
                worldEventEngine.publish(companionId, "CHECKED_PHONE", WorldEvent.SRC_LIFE,
                        companionId, null, Map.of(), 0.15);
                // 看手机 → 未读通知可能变成 seen(由 PhoneNotificationTick 自然推进)
                log.debug("[行为] {} 拿起手机看了看", c.getName());
            }
            case CONTACT_OTHER_PERSON -> {
                worldEventEngine.publish(companionId, WorldEventEngine.TYPE_CONTACT_OTHER_PERSON,
                        WorldEvent.SRC_SOCIAL, companionId, null,
                        Map.of("activity", "和朋友们见面/聊天"), 0.35);
                // V9: 突发事件打断最近的低优先级计划(朋友喊我吃饭 → 计划顺延)
                try {
                    var latest = planService.activePlans(companionId).stream()
                            .filter(p -> p.getFlexibility() > 0.3)
                            .findFirst();
                    latest.ifPresent(p -> planService.interrupt(companionId, p.getId(),
                            "friend_meetup", "朋友突然喊我出去, 就先没顾上" + p.getTitle()));
                } catch (Exception ignored) { }
                AgentState st = agentStateService.getOrCreate(companionId);
                st.setSocialEnergy(Math.min(1.0, st.getSocialEnergy() + 0.06));
                st.setLoneliness(Math.max(0, st.getLoneliness() - 0.1));
                agentStateService.save(st);
                log.info("[行为] {} 和朋友聚了聚 (得分{})", c.getName(), round(selected.score()));
            }
            case RECALL_MEMORY -> {
                worldEventEngine.publish(companionId, WorldEventEngine.TYPE_MEMORY_ACTIVATED,
                        WorldEvent.SRC_MEMORY, companionId, null, Map.of(), 0.2);
            }
            case STAY_AWAKE -> {
                // 保持清醒(不做事)
            }
            case CONTINUE_ACTIVITY, CHANGE_ACTIVITY, REST, DO_NOTHING -> {
                // 继续生活/发呆/休息 —— 世界仍在运行, 只是没有用户可见行为
            }
        }
        return decision;
    }

    /** §二十九: 睡眠决策也应考虑"正在陪你聊" —— 供 SleepTickJob 使用 */
    @Transactional
    public SleepModel.SleepDecision sleepDecision(String companionId, LocalDateTime now) {
        boolean engaged = recentlyEngaged(companionId, now);
        double motivation = engaged ? 0.85 : 0.0;
        double social = engaged ? 0.8 : 0.0;
        return sleepModel.decideSleep(companionId, now, motivation, social);
    }

    // ── 内部: 打分与状态 ────────────────────────────

    /** 最近 30 分钟内有对话 → 社交参与(意志克服睡意) */
    private boolean recentlyEngaged(String companionId, LocalDateTime now) {
        try {
            List<MessageView> recent = chatWorld.userMessagesSince(companionId, now.minusMinutes(30));
            return !recent.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private double engagement(AgentState state, Long minutesSinceContact) {
        if (minutesSinceContact != null && minutesSinceContact < 45) return 0.8;
        return 0.0;
    }

    /** 关系张力: 有摩擦时更少主动联系/更少社交, 更多"自己待着" */
    private static double tensionModifier(BehaviorAction action, double tension) {
        if (tension <= 0.25) return 0;
        return switch (action) {
            case SEND_PROACTIVE_MESSAGE -> -tension * 0.8;
            case CONTACT_OTHER_PERSON -> -tension * 0.3;
            case DO_NOTHING, CONTINUE_ACTIVITY -> tension * 0.3;
            default -> 0;
        };
    }

    /** 人格: 外向/主动 → 更可能联系; 内向 → 更多独处 */
    private static double personalityModifier(BehaviorAction action, double sociability, double initiative) {
        return switch (action) {
            case SEND_PROACTIVE_MESSAGE -> (initiative - 0.5) * 0.5 + (sociability - 0.5) * 0.2;
            case CONTACT_OTHER_PERSON -> (sociability - 0.5) * 0.8;
            case DO_NOTHING, CONTINUE_ACTIVITY -> (0.5 - sociability) * 0.2;
            default -> 0;
        };
    }

    /** 行为熵: 用"最近主动消息间隔的方差"做随机扰动 —— 规律不决定每次行为 */
    private double entropyJitter(String companionId) {
        return randomJitter();
    }

    private double randomJitter() {
        return (random.nextDouble() * 2 - 1) * JITTER_AMPLITUDE;
    }

    /** 概率化选择: softmax + 轮盘赌 */
    private BehaviorCandidate stochasticSelect(List<BehaviorCandidate> candidates) {
        if (candidates.isEmpty()) return BehaviorCandidate.of(BehaviorAction.DO_NOTHING, "无候选", 0.3);
        double max = candidates.stream().mapToDouble(BehaviorCandidate::score).max().orElse(1.0);
        double[] weights = new double[candidates.size()];
        double sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            double w = Math.exp((candidates.get(i).score() - max) / TEMPERATURE);
            if (!Double.isFinite(w)) w = 0;
            weights[i] = w;
            sum += w;
        }
        if (sum <= 0) return candidates.get(0);
        double r = random.nextDouble() * sum;
        for (int i = 0; i < candidates.size(); i++) {
            r -= weights[i];
            if (r <= 0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    private boolean dnd(LocalDateTime now) {
        int h = now.getHour();
        return h >= 1 && h < 7;   // 深夜几乎不主动打扰
    }

    private static double idleFactor(CompanionSchedule.Activity a) {
        return switch (a) {
            case WORK_BUSY, WORK_AFTERNOON -> 0.3;
            case LUNCH, EVENING -> 0.7;
            case LEISURE -> 1.0;
            default -> 0.5;
        };
    }

    private Persona safePersona(String companionId) {
        try {
            return personaService.getActive(companionId);
        } catch (Exception e) {
            return null;
        }
    }

    private static double trait(Persona persona, String key, double fallback) {
        try {
            if (persona != null && persona.getPersonality() != null
                    && persona.getPersonality().getTraits() != null) {
                Double v = persona.getPersonality().getTraits().get(key);
                if (v != null) return Math.max(0, Math.min(1, v));
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
