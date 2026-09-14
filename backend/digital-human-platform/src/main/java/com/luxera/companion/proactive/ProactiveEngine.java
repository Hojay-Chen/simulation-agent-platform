package com.luxera.companion.proactive;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.thought.ThoughtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 主动消息引擎(设计文档 52-55 节): 调度器只负责何时检查,
 * 真正决定是否打扰的是决策引擎(打断成本 vs 预期价值)。
 * 主动消息内容用 LLM 按人格+时间+近期话题生成,让陪伴感真实。
 */
@Slf4j
@Component
public class ProactiveEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final AppProperties props;
    private final CompanionRepository companionRepo;
    private final PersonaService personaService;
    private final RelationshipRepository relationshipRepo;
    private final ChatWorldPort chatWorld;

    private final CompanionSchedule schedule;
    private final OpenLoopService openLoopService;
    private final ThoughtService thoughtService;
    private final com.luxera.companion.state.AgentStateService agentStateService;
    /** V10 §15: 唯一聊天文本生产入口(主动消息也必须过质量闸门) */
    private final com.luxera.companion.digitalhuman.conversation.ConversationRuntime conversationRuntime;

    public ProactiveEngine(AppProperties props, CompanionRepository companionRepo, PersonaService personaService,
                           RelationshipRepository relationshipRepo, ChatWorldPort chatWorld,
                           CompanionSchedule schedule,
                           OpenLoopService openLoopService, ThoughtService thoughtService,
                           com.luxera.companion.state.AgentStateService agentStateService,
                           com.luxera.companion.digitalhuman.conversation.ConversationRuntime conversationRuntime) {
        this.props = props;
        this.companionRepo = companionRepo;
        this.personaService = personaService;
        this.relationshipRepo = relationshipRepo;
        this.chatWorld = chatWorld;

        this.schedule = schedule;
        this.openLoopService = openLoopService;
        this.thoughtService = thoughtService;
        this.agentStateService = agentStateService;
        this.conversationRuntime = conversationRuntime;
    }

    /**
     * §四十一: 定时主动检查已由 BehaviorEngine(行为 Tick)接管,
     * 主动联系只是数字人的行为候选之一。此方法保留手动触发入口(Admin/测试)。
     */
    public void runScheduled() {
        // 由 BehaviorEngine.evaluateAll 替代 —— 这里不再自动跑, 避免双发
    }

    @Transactional
    public List<String> run() {
        List<String> actions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // "到点的提醒 → 通知" 这一段已经不在这里了(LAP v1 R5)。
        //
        // 它曾经属于这里, 是因为提醒是数字人的一张表, 而"到点了"只有数字人自己在扫的时候才发现。
        // 现在提醒触发是一个应用事件: com.luxera.reminder 的 ReminderDispatchJob 扫它自己的表,
        // 发一条 reminder.due, 由数字人的 ApplicationNotificationBridge 变成通知。两处扫同一张表
        // 的那种"谁先谁后"的问题, 随之消失 —— 提醒应用是唯一的扫描者。

        int dndStart = props.getProactive().getDndStartHour();
        int dndEnd = props.getProactive().getDndEndHour();
        int hour = now.getHour();
        boolean inDnd = inDnd(hour, dndStart, dndEnd);

        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            String userId = c.getUserId();
            if (inDnd) continue;
            // 她的作息: 睡觉时不主动打扰(比 DND 更贴合个人作息)
            if (schedule.activityFor(c.getId(), now) == CompanionSchedule.Activity.SLEEP) continue;

            // 主动意愿: 由"自然频率"决定, 不再有硬性上限/间隔
            MessageView lastProactive = lastProactiveOf(c.getId());

            Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
            LocalDateTime lastInteraction = rel != null ? rel.getLastInteractionAt() : null;
            boolean responsive = isResponsive(userId, c.getId(), lastProactive, now);

            ProactiveDecision decision = decide(c, now, lastInteraction, lastProactive, responsive);
            if (decision.act()) {
                String content = draftMessage(c, decision.trigger(), decision.content(), now);
                // 主动消息只进聊天框(见设计 §二十七~三十), 它是 Chat 消息, 不是 Notification
                injectMessage(c.getId(), content);
                actions.add(c.getName() + " → " + decision.title() + " (预期" + round(decision.expectedValue())
                        + " vs 成本" + round(decision.interruptionCost()) + ")");
                log.info("[主动消息] {}: {} (trigger={})", c.getName(), decision.title(), decision.trigger());
            }
        }
        return actions;
    }

    /** 定向触发某伴侣的主动消息(测试实时推送用, 可用 force 模拟"隔了一阵没聊") */
    @Transactional
    public List<String> runForCompanion(String companionId, boolean force) {
        List<String> actions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        companionRepo.findById(companionId).ifPresent(c -> {
            if (c.getDeletedAt() != null) return;
            String userId = c.getUserId();
            Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
            LocalDateTime lastInteraction = rel != null ? rel.getLastInteractionAt() : null;
            // force: 模拟"已经有一阵没互动了", 让价值>成本可稳定触发
            if (force) {
                lastInteraction = now.minusHours(6);
            }
            ProactiveDecision decision = decide(c, now, lastInteraction, null, true);
            if (decision.act()) {
                String content = draftMessage(c, decision.trigger(), decision.content(), now);
                injectMessage(c.getId(), content);
                actions.add(c.getName() + " → " + decision.title() + " (trigger=" + decision.trigger() + ")");
                log.info("[主动消息-定向] {}: {} (trigger={})", c.getName(), decision.title(), decision.trigger());
            } else {
                actions.add(c.getName() + " → 无合适触发");
            }
        });
        return actions;
    }

    /** 决策引擎: 收集触发,计算预期价值与打断成本(设计文档 §18) —— 作为行为候选生成器 */
    public ProactiveDecision decide(Companion c, LocalDateTime now, LocalDateTime lastInteraction,
                                    MessageView lastProactive, boolean responsive) {
        String userId = c.getUserId();
        int hour = now.getHour();
        Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
        // 按作息调节主动意愿: 忙碌时低, 休闲时高
        double factor = schedule.proactiveFactor(c.getId(), now);
        // 关系相关性(设计文档 §18): 越熟悉/越亲密, 主动联系的价值越高
        double relBonus = relationshipBonus(rel);
        // 打断成本: 由"最后一次互动距今"的正相关曲线决定(刚聊过→很低, 越久没聊→越低)
        double cst = cost(lastInteraction);
        // "今天还没聊过"是合理自然条件(用于早安/回访触发)
        long todayCount = chatWorld.countByCompanionAndKindSince(
                c.getId(), "PROACTIVE", now.toLocalDate().atStartOfDay());

        // 自然频率: 上次主动在 2 小时内 → 这次先不主动(真人不会连续轰炸);
        // 超过 2 小时 → 由成本曲线自然决定是否值得发, 不再有"每日上限"这种机械限制
        if (lastProactive != null && lastProactive.getCreatedAt().isAfter(now.minusHours(2))) {
            return ProactiveDecision.nothing();
        }

        // 触发 0: OpenLoop 驱动(未完成事项, 最真实) — "面试怎么样了"
        // P1 增强: 到点后问(±2h 窗口内价值最高), 错过但仍未跟进的在 2 天内仍值得问一次(价值随时间递减)
        OpenLoop bestLoop = openLoopService.activeLoops(c.getId()).stream()
                .filter(l -> l.getExpectedResolutionAt() != null)
                .filter(l -> l.getExpectedResolutionAt().isBefore(now.plusHours(2)))
                .filter(l -> !l.getExpectedResolutionAt().isBefore(now.minusDays(2)))
                .max(Comparator.comparingDouble(l -> openLoopValue(now, l)))
                .orElse(null);
        if (bestLoop != null && !loopFollowedUp(c.getId(), bestLoop.getTitle())) {
            double value = (0.5 + openLoopValue(now, bestLoop)) * factor + relBonus;
            if (value > cst) {
                return ProactiveDecision.send("未了结的事",
                        "想问问" + bestLoop.getTitle() + "的事,后来怎么样了。",
                        "open_loop", value, cst);
            }
        }

        // 触发 0.5: Thought 驱动(她"想起了你")
        Thought bestThought = thoughtService.activeThoughts(c.getId()).stream()
                .filter(t -> t.getStrength() >= 0.35)
                .filter(t -> List.of("CURIOSITY", "WORRY", "EXPECTATION", "UNFINISHED").contains(t.getType()))
                .filter(t -> !"SUPPRESSED".equals(t.getStatus()))
                .max(Comparator.comparingDouble(Thought::getStrength))
                .orElse(null);
        if (bestThought != null) {
            // 若想法关联的未完成事项还没到预期时间(提前问=打扰) → 今天不打扰, SUPPRESSED 保留
            if (isPremature(bestThought, now)) {
                thoughtService.suppress(bestThought.getId());
            } else {
                double value = (0.35 + bestThought.getStrength() * 0.4) * factor + relBonus;
                if (value > cst) {
                    thoughtService.act(bestThought.getId());
                    return ProactiveDecision.send("心里想着", bestThought.getContent(), "thought", value, cst);
                } else {
                    // 被成本压制的想法 → SUPPRESSED, 保留次日再激活(形成自然连续性)
                    thoughtService.suppress(bestThought.getId());
                }
            }
        }

        // 触发 1: 深夜加班模式
        List<MessageView> week = chatWorld.userMessagesSince(c.getId(), now.minusDays(7));
        long lateCount = week.stream().filter(m -> {
            int h = m.getCreatedAt().getHour();
            return h >= 23 || h < 2;
        }).count();
        boolean alreadyMentioned = false;
        MessageView lastP = lastProactiveOf(c.getId());
        if (lastP != null) alreadyMentioned = lastP.getContent() != null && lastP.getContent().contains("忙到很晚");
        if (lateCount >= 3 && !alreadyMentioned) {
            return ProactiveDecision.send("深夜提醒",
                    "你最近是不是又忙到很晚了?我不催你睡,只是想知道你还好吗。",
                    "late_work", 0.62 * factor, cst);
        }

        // 触发 2: 早安问候(上午 8-11 点,今天还没聊过,且之前聊过)
        if (hour >= 8 && hour < 11 && todayCount == 0 && rel != null && rel.getMessageCount() > 0) {
            return ProactiveDecision.send("早安问候",
                    "早上好呀,新的一天开始啦。你今天有什么安排吗?",
                    "morning_greeting", 0.52 * factor, cst);
        }

        // 触发 3: 傍晚回访(今天聊过,但 2-8 小时没动静)
        if (hour >= 17 && hour <= 22 && lastInteraction != null
                && lastInteraction.isAfter(now.minusHours(8))
                && lastInteraction.isBefore(now.minusHours(2))) {
            return ProactiveDecision.send("傍晚回访",
                    "这会儿忙完了吗?想听听你今天过得怎么样。",
                    "evening_checkin", 0.55 * factor, cst);
        }

        // 触发 4: 分享好消息后的跟进(48h 内,未跟进过)
        MessageView lastJoy = null;
        for (MessageView m : chatWorld.userMessagesSince(c.getId(), now.minusDays(2))) {
            if ("share_joy".equals(m.getIntent()) || "happy".equals(m.getEmotion())) {
                lastJoy = m;
            }
        }
        if (lastJoy != null) {
            boolean followedUp = chatWorld.messagesBetween(c.getId(), lastJoy.getCreatedAt(), now)
                    .stream().anyMatch(m -> "PROACTIVE".equals(m.getMessageKind())
                            && m.getContent() != null && m.getContent().contains("好消息"));
            if (!followedUp) {
                return ProactiveDecision.send("跟进好消息",
                        "前两天你说的那个好消息,后来怎么样了?我一直惦记着呢。",
                        "follow_up_joy", 0.65 * factor, cst);
            }
        }

        // 触发 5: 长时间沉默(2 天)
        if (rel != null && rel.getFamiliarity() > 0.15 && lastInteraction != null
                && lastInteraction.isBefore(now.minusDays(2))) {
            return ProactiveDecision.send("好久不见",
                    "最近两天你都没怎么找我,我有点想你了。要是忙,就告诉我一声,我不吵你。",
                    "silence", 0.5 * factor, cst);
        }

        // 触发 5.5: Re-engagement(冲突后缓和) — 她受了伤/生气, 但过了一阵想缓和关系
        var astate = agentStateService.get(c.getId());
        if (astate != null && (astate.getHurt() + astate.getAnger()) > 0.5
                && lastInteraction != null && lastInteraction.isBefore(now.minusHours(1))
                && todayCount == 0) {
            return ProactiveDecision.send("缓和关系",
                    "刚才是我不太对……你现在还生气吗?",
                    "reconnect", 0.55 * factor, cst);
        }

        return ProactiveDecision.nothing();
    }

    /** 用 LLM 按人格+作息+场景生成主动消息,失败时回退到固定文案 */
    private String draftMessage(Companion c, String trigger, String fallback, LocalDateTime now) {
        try {
            Persona persona = personaService.getActive(c.getId());
            String personaDesc = "";
            if (persona != null && persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                personaDesc = persona.getPersonality().getSummary();
            }
            String scheduleDesc = schedule.describe(c.getId(), c.getName(), now);
            // V10 §15: 唯一文本生成入口 —— 分层 Prompt + 输出质量闸门(禁旁白/AI 腔) + 重试
            com.luxera.companion.digitalhuman.conversation.ConversationRequest request =
                    com.luxera.companion.digitalhuman.conversation.ConversationRequest.builder(
                            c.getId(), c.getName(), "现在给用户发这条主动消息吧。")
                    .stable("你是" + c.getName() + ",一个真实的人。")
                    .stable(personaDesc)
                    .stable("你打算主动给用户发一条消息, 场景:" + triggerDesc(trigger)
                            + "。请自然地说 2-3 句, 像发微信, 不要解释你是 AI, 不要问号堆砌。")
                    .semiStable(scheduleDesc)
                    .temperature(0.9)
                    .maxLength(120)
                    .build();
            var drafts = conversationRuntime.generateDrafts(request);
            if (!drafts.isEmpty()) {
                String content = drafts.get(0).text().trim();
                if (content.length() >= 5 && content.length() <= 120) {
                    return content;
                }
            }
        } catch (Exception e) {
            log.debug("主动消息 LLM 生成失败,回退模板: {}", e.getMessage());
        }
        return fallback;
    }

    private static String triggerDesc(String trigger) {
        switch (trigger == null ? "" : trigger) {
            case "late_work": return "用户最近常熬夜加班";
            case "morning_greeting": return "新的一天开始了,想问候一下用户";
            case "evening_checkin": return "用户今天聊过但有一阵没动静了";
            case "follow_up_joy": return "用户前几天分享过好消息,想问问后续";
            case "silence": return "用户两天没联系你了";
            case "reconnect": return "你们刚闹了点不愉快,你想主动缓和一下,先低个头";
            default: return "随口关心一下用户";
        }
    }

    /**
     * 打断成本: 由"最后一次互动距今"的正相关曲线决定。
     * - 刚聊过(<30min) → 成本高, 不打扰(她刚回完你, 不会立刻又发)
     * - 越久没聊 → 成本越低(她想你了, 联系更自然)
     * - 用平滑曲线而非固定加值; 时间成本自然回落, 不再有"4h 内一律 +0.35"的生硬
     */
    private double cost(LocalDateTime lastInteraction) {
        if (lastInteraction == null) return 0.05;   // 从未聊过, 几乎无打扰成本
        long minutes = java.time.Duration.between(lastInteraction, LocalDateTime.now()).toMinutes();
        if (minutes <= 0) return 0.9;
        // 1 小时内的打扰成本: 从 0.9 平滑下降到 ~0.2
        double cost = 0.9 * Math.exp(-minutes / 55.0) + 0.15;
        return Math.max(0.05, Math.min(1.0, cost));
    }

    /** 上次主动消息后 2 小时内用户是否回话(响应率代理) */
    private boolean isResponsive(String userId, String companionId, MessageView lastProactive, LocalDateTime now) {
        if (lastProactive == null) return true;
        LocalDateTime windowEnd = lastProactive.getCreatedAt().plusHours(2);
        if (windowEnd.isAfter(now)) windowEnd = now;
        for (MessageView m : chatWorld.userMessagesSince(companionId, lastProactive.getCreatedAt())) {
            if (m.getCreatedAt().isBefore(windowEnd)) return true;
        }
        return false;
    }

    /** 该未完成事项是否已经被主动跟进过 */
    private boolean loopFollowedUp(String companionId, String title) {
        MessageView last = lastProactiveOf(companionId);
        if (last == null) return false;
        String content = last.getContent();
        return content != null && title != null && content.contains(title);
    }

    /** OpenLoop 跟进价值: 到点附近最高, 错过越久越低(但 2 天内仍值得问一次) */
    private static double openLoopValue(LocalDateTime now, OpenLoop l) {
        LocalDateTime expected = l.getExpectedResolutionAt();
        if (expected == null) return 0;
        if (expected.isAfter(now)) {
            // 还没到点: 越接近越高
            long minsLeft = java.time.Duration.between(now, expected).toMinutes();
            return l.getImportance() * 0.5 + clamp01(1 - minsLeft / 120.0) * 0.4;
        }
        // 已过点: 刚过时最高, 24h 后衰减到基础值
        long minsAfter = java.time.Duration.between(expected, now).toMinutes();
        double decay = Math.max(0, 1 - minsAfter / (24 * 60.0));
        return l.getImportance() * 0.5 + decay * 0.5;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 想法关联的未完成事项是否还没到预期时间(提前问=打扰) */
    private boolean isPremature(Thought t, LocalDateTime now) {
        if (!"OPEN_LOOP".equals(t.getTriggerType()) || t.getTriggerRef() == null) return false;
        return openLoopService.getById(t.getTriggerRef())
                .map(l -> l.getExpectedResolutionAt() != null
                        && l.getExpectedResolutionAt().isAfter(now.plusHours(2)))
                .orElse(false);
    }

    /** 关系相关性加权(设计文档 §18): 越熟悉/越亲密, 主动价值越高 */
    private static double relationshipBonus(Relationship rel) {
        if (rel == null) return 0;
        return clamp(rel.getFamiliarity() * 0.1 + rel.getIntimacy() * 0.1);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 上次主动消息(BehaviorEngine 候选生成用) */
    public MessageView lastProactive(String companionId) {
        return lastProactiveOf(companionId);
    }

    /** 执行一次主动联系(草稿 + 注入聊天框 + 事件推送) —— 由 BehaviorEngine 调用 */
    @Transactional
    public void execute(Companion c, ProactiveDecision decision, LocalDateTime now) {
        if (decision == null || !decision.act()) return;
        String content = draftMessage(c, decision.trigger(), decision.content(), now);
        injectMessage(c.getId(), content);
        log.info("[主动消息] {}: {} (trigger={})", c.getName(), decision.title(), decision.trigger());
    }

    private void injectMessage(String companionId, String content) {
        var convs = chatWorld.conversationsOf(companionId);
        if (convs.isEmpty()) return;
        // 主动消息进入聊天框, 标记 message_kind=PROACTIVE(仍是 Chat 消息, 不是 Notification)
        MessageView m = chatWorld.append(MessageAppendCommand
                .of(convs.get(0).getId(), "companion", content)
                .withKind("PROACTIVE")
                .withProactive(true));
        // 主动消息经持久事件流实时推给前端
        chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_MESSAGE,
                Map.of("messageId", m.getId(), "conversationId", m.getConversationId(),
                        "content", content, "senderType", "companion", "proactive", true));
    }

    /** 最近一条主动消息(判定"是否已经主动过"用) */
    private MessageView lastProactiveOf(String companionId) {
        return chatWorld.recentByCompanionAndKind(companionId, "PROACTIVE", 1).stream()
                .findFirst().orElse(null);
    }

    private static boolean inDnd(int hour, int start, int end) {
        if (start > end) {
            return hour >= start || hour < end;
        }
        return hour >= start && hour < end;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
