package com.luxera.companion.wakeup;

import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.AgentEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * V11 §17.2 —— <b>她永远有一个闹钟</b>。这是 {@code BehaviorTickJob} 在 V11 里的替身。
 *
 * <pre>
 *   enabled=false, shadow=true   排闹钟; 到点投信; 消费者算出她会做什么并记账, 不动手
 *   enabled=true                 同上, 但消费者让它真的发生 —— 老链的 tick 同时停摆
 * </pre>
 *
 * <h2>为什么不是"每 5 分钟评估所有 agent"</h2>
 * 老链的形态是<b>一个全局节拍</b>: 每 5 分钟, 全世界的 agent 同时被推一下。这个形状
 * 与"她在生活"是不相容的, 有两个具体的后果:
 * <ol>
 *   <li>她的时间表不由她决定, 由 cron 决定 —— 所有人同一秒醒来, 同一秒开始想要不要说话;</li>
 *   <li>这个节拍<b>必须一直存在</b>, 于是"她在过日子"这件事永远依赖于某个定时任务没挂。</li>
 * </ol>
 * 换成闹钟之后, 每一行 {@code agent_schedule} 都是<b>她自己的</b>下一个时刻。本类只做一件事:
 * 保证那行存在。它响过之后, 消费者(或决策)会排出下一个 —— 生命靠自己的回路延续,
 * 而不是靠一个外部节拍反复踢它。
 *
 * <h2>为什么无条件补, 而不是"算出她该几点醒"</h2>
 * 一个自然的设计是让本类去问"她该不该睡/该不该忙", 然后算出精准的下次时刻。这里没有那样做,
 * 理由与 {@code MindDecisionPlanner} 是同一条: <b>会查库的判断只能靠集成测试碰运气,
 * 一个纯粹的补位可以被穷举</b>。她的作息、忙碌、情绪在被唤醒之后由决策层读
 * (消费者那一侧), 那时它手上有一整份事实, 而这里只有一张表。
 *
 * <p>代价是醒来之后她常常发现自己"没什么可做的" —— 而那恰恰是真人一天里绝大多数时刻的
 * 真相, 也正是 {@code BehaviorEngine} 里 {@code CONTINUE_ACTIVITY / DO_NOTHING} 两个候选
 * 存在的意义。切流之后的正确性判据不是"她每次醒来都做点什么", 而是
 * <b>"她做的那件事有据可依"</b>。
 *
 * <h2>抖动不是装饰</h2>
 * 53 个 agent 如果都在同一秒被排上闹钟, 就会在同一秒一起醒来 —— 而它们共用
 * <b>一根调度线程</b>与<b>一个 LLM 出口</b>。抖动把这一秒摊成一段, 顺带让"她什么时候
 * 想起你"这件事看起来不像机器。种子不固定: 抖动本来就不该可复现, 可复现的是行为判据,
 * 不是噪声。
 *
 * <h2>被暂停的 agent 不排闹钟(与 AgentWakeupJob 的规矩不冲突)</h2>
 * {@code AgentWakeupJob} 的注释说"暂停中的 agent 照样投信", 那说的是<b>已经排上的</b>闹钟:
 * 世界在走, 那一刻确实到了。本类说的是另一件事 —— <b>谁来上发条</b>。排一个闹钟是
 * "她给自己定了个时刻", 而一个被暂停的 agent 认知是关掉的({@code AgentSwitchService}
 * 那道闸管着信箱与 LLM), 她定不了。所以这里用
 * {@link CompanionRepository#findRunnable()} —— 它已经把暂停与软删都排除在外,
 * 而那正是"谁在过日子"的定义。
 */
@Component
@Slf4j
public class WakeupRearmJob {

    /**
     * 一轮最多看几个 agent。
     *
     * <p>与 {@code AgentWakeupJob.BATCH} 同一个理由: 这个循环跑在共用调度线程上,
     * "某天 agent 数量涨到十万"是一旦发生就会让全平台定时任务一起停摆的形态。
     */
    private static final int BATCH = 500;

    private final CompanionRepository companions;
    private final AgentWakeupService wakeups;
    private final BehaviorEngine behaviorEngine;
    private final V11ProactiveSwitch v11;
    private final Random random = new Random();

    /** 她两次醒来之间的基准间隔(分钟)。这是"她的呼吸频率", 属于产品参数, 不是常量。 */
    @Value("${app.v11.proactive.rearm-minutes:30}")
    private int rearmMinutes;

    /** 抖动比例(0.25 = ±25%)。见类注释。 */
    @Value("${app.v11.proactive.rearm-jitter:0.25}")
    private double jitter;

    public WakeupRearmJob(CompanionRepository companions, AgentWakeupService wakeups,
                          BehaviorEngine behaviorEngine, V11ProactiveSwitch v11) {
        this.companions = companions;
        this.wakeups = wakeups;
        this.behaviorEngine = behaviorEngine;
        this.v11 = v11;
    }

    @Scheduled(cron = "${app.scheduler.wakeup-rearm-cron:0 */5 * * * *}")
    public void rearm() {
        if (!v11.isActive()) {
            // 两个开关都关 = V11 完全不参与, 她的节拍仍由 BehaviorTickJob 提供。
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int armed = 0;
            int scanned = 0;
            for (Companion c : companions.findRunnable()) {
                if (scanned++ >= BATCH) {
                    log.warn("[Wakeup] agent 数超过单轮上限 {}, 剩下的下一轮再补", BATCH);
                    break;
                }
                try {
                    // 世界往前走一步 —— §三十九 的关系维护压力。
                    //
                    // 这一步必须在这里: 切流之后 evaluateAll 不再跑, 而压力更新原来寄生在它里面。
                    // 漏掉的后果是"沉默越久越想联系"静默失效 —— 不报错, 只表现为她再也不主动
                    // 找人。decayConnectionPressure 是幂等的(它由 lastInteractionAt 与 now 算出
                    // 目标值), 所以 shadow 期两处都调也不会衰减两次。
                    behaviorEngine.prepare(c.getId(), now);

                    if (wakeups.nextWakeupOf(c.getId()) != null) {
                        continue;   // 她已经有下一个时刻了, 不再插手
                    }
                    if (wakeups.schedule(c.getId(), nextWakeupAt(now), AgentEventType.SCHEDULED_WAKEUP,
                            AgentWakeupService.SRC_LIFE, "生命节律")) {
                        armed++;
                    }
                } catch (Exception e) {
                    // 一个 agent 排不上不该让其余 52 个一起停 —— 逐个隔开, 下一轮还在
                    log.debug("[Wakeup] {} 补闹钟失败: {}", c.getId(), e.getMessage());
                }
            }
            if (armed > 0) {
                log.info("[Wakeup] 给 {} 个 agent 补上了下一个时刻 (基准 {} 分钟)", armed, rearmMinutes);
            }
        } catch (Exception e) {
            log.warn("[Wakeup] 补闹钟失败: {}", e.getMessage());
        }
    }

    /** 基准间隔 ± 抖动, 下限 1 分钟 —— 一个 0 分钟的间隔会在同一秒里排出一串闹钟。 */
    private LocalDateTime nextWakeupAt(LocalDateTime now) {
        int base = Math.max(1, rearmMinutes);
        double factor = 1.0 + (random.nextDouble() * 2 - 1) * Math.max(0, Math.min(0.9, jitter));
        long minutes = Math.max(1, Math.round(base * factor));
        return now.plusMinutes(minutes);
    }
}
