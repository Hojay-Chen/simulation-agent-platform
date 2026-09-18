package com.luxera.companion.proactive;

import com.luxera.companion.behavior.BehaviorCandidate;
import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.behavior.BehaviorOutcome;
import com.luxera.companion.mailbox.AgentInboxConsumer;
import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * V11 §18.2 —— <b>她被唤醒之后</b>。全仓第一个真正的 {@link AgentInboxConsumer}。
 *
 * <pre>
 *   §18.2  Wake → Load Mind → Process pending events → Update awareness
 *          → Evaluate intentions → Decide → Schedule next wakeup
 * </pre>
 * 本类落在"Decide"那一格: 前面几步(载入心智、处理堆积的事件)由 {@link BehaviorEngine}
 * 的状态收集完成, 后面"排下一次唤醒"是决策自己的产出(见 {@code AgentWakeupService})。
 *
 * <h2>它只回答一个问题: 醒来之后她做了什么</h2>
 * {@link AgentInboxConsumer} 的接口注释写着"拆信是感知, 决策是决策"。本类严格遵守:
 * 它不判断"该不该主动说话" —— 那是 {@link BehaviorEngine} 的事, 一门从 V9 就在跑、
 * 有自己的人格/关系/作息输入的引擎。本类做的是<b>把事件递给那门引擎</b>,
 * 并把它选出来的结果记进账本。
 *
 * <p>这一点值得说清楚, 因为"新写一个消费者"很容易被写成"新写一套决策":
 * 那样做的话, 切流就不是"换一条送达路径", 而是<b>换一个大脑</b> ——
 * 于是 shadow 期观察的东西与切流之后跑的东西不是同一个, shadow 白跑。
 *
 * <h2>为什么只支持这五种事件类型</h2>
 * <b>刻意不含阶梯事件</b>({@code USER_MESSAGE_RECEIVED / NOTIFIED / NOTICED / READ / DEFERRED})。
 * 那条链今天由 {@code V11DeliveryPath} 直连认知; 再注册一个消费者会让同一条消息
 * 被处理两次 —— 而"她一次回了你两条"这种故障不报错。阶梯事件的消费者是 Phase 6
 * 切流时要一起做的一件事, 不是现在顺手加的。
 *
 * <h2>shadow 走的是 select, 不是 evaluate</h2>
 * {@link BehaviorEngine#evaluate} 边选边做, 所以 shadow 无法用它观察 ——
 * 调一次就等于真的让她发了消息。这就是 {@link BehaviorEngine#select} 存在的原因:
 * 两个档位调的是<b>同一个选择方法</b>, 差别只在"选出来之后要不要 execute"。
 * 于是 shadow 记下的那个答案与切流后真的会发生的事情是同一个答案。
 *
 * <h2>关掉时是"拆掉然后丢掉", 不是"不看"</h2>
 * 开关两个都关时, 本类被调用会直接返回 —— 而 {@code AgentMailbox} 在消费者正常返回后
 * 会把信标记为 CONSUMED。这是有意的: 一封在开关关掉期间到达的信, 如果留在信箱里,
 * 会在切流那天被一起拆开, 表现为她一口气发了几十条主动消息。宁可丢掉一封已经决定
 * 不处理的信, 也不能攒着。
 */
@Component
@Slf4j
public class ProactiveActionConsumer implements AgentInboxConsumer {

    /**
     * 由她自己的时钟/生活/未完成事项唤醒的那几种。
     *
     * <p>用 {@code Set.of} 而不是一堆 {@code ||}: 支持列表是这个类对外唯一的接口面,
     * 写成集合之后"它到底认哪几种"是可以一眼读完的, 而且加一种时必须动这一行。
     */
    private static final Set<AgentEventType> SUPPORTED = Set.of(
            AgentEventType.SCHEDULED_WAKEUP,
            AgentEventType.LIFE_EVENT,
            AgentEventType.OPEN_LOOP_DUE,
            AgentEventType.INTENTION_ACTIVATED,
            AgentEventType.RELATIONSHIP_CHANGED);

    /** 理由串的前缀 —— 让"这次行为是被 V11 唤醒的"在日志与账本里可检索。 */
    static final String REASON_PREFIX = "V11_";

    private final BehaviorEngine behaviorEngine;
    private final V11ProactiveSwitch v11;
    private final ProactiveActionRecorder recorder;

    public ProactiveActionConsumer(BehaviorEngine behaviorEngine, V11ProactiveSwitch v11,
                                   ProactiveActionRecorder recorder) {
        this.behaviorEngine = behaviorEngine;
        this.v11 = v11;
        this.recorder = recorder;
    }

    @Override
    public boolean supports(AgentEventType type) {
        return type != null && SUPPORTED.contains(type);
    }

    @Override
    public void consume(EventEnvelope envelope) {
        if (envelope == null || envelope.agentId() == null) {
            return;
        }
        String wire = envelope.type() == null ? "UNKNOWN" : envelope.type().wire();
        if (!v11.isActive()) {
            // 拆掉、记一笔、丢掉。见类注释最后一段。
            recorder.record(wire, null, "proactive_off", 0);
            return;
        }

        String agentId = envelope.agentId();
        String reason = REASON_PREFIX + wire;
        try {
            if (v11.isEffective()) {
                BehaviorOutcome outcome = behaviorEngine.evaluateById(agentId, LocalDateTime.now(), reason);
                if (outcome == null) {
                    recorder.recordError();
                    return;
                }
                recorder.record(wire, outcome.action(), outcome.trigger(), outcome.score());
                if (ProactiveActionRecorder.speaks(outcome.action())) {
                    log.info("[V11] {} 被 {} 唤醒后主动开口 ({})", agentId, wire, outcome.trigger());
                }
            } else {
                BehaviorCandidate planned = behaviorEngine.select(agentId, LocalDateTime.now());
                if (planned == null) {
                    recorder.recordError();
                    return;
                }
                recorder.record(wire, planned.action(), planned.trigger(), planned.score());
                if (ProactiveActionRecorder.speaks(planned.action())) {
                    log.info("[V11] {} 被 {} 唤醒, 本来会主动开口 ({}, 得分{}) —— shadow 不动手",
                            agentId, wire, planned.trigger(), Math.round(planned.score() * 1000) / 1000.0);
                }
            }
        } catch (Exception e) {
            // 记一笔再抛出去: 信箱会把它标成 FAILED 并重试, 而"重试了几次"与
            // "算出来不主动"必须能分开 —— 两者的账面表现本来是一样的。
            recorder.recordError();
            log.warn("[V11] 拆信失败 {} {}: {}", agentId, wire, e.getMessage());
            throw e;
        }
    }
}
