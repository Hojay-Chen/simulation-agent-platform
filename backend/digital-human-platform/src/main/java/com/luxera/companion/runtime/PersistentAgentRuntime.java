package com.luxera.companion.runtime;

import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventPriority;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * V11 §4.1 —— 持久运行时的门面。设计文档给它的动词只有四个。
 *
 * <pre>
 *   accept(envelope)   世界把一件事交给她
 *   wake(agentId, 为什么) 把她叫醒 —— 而"醒着"不等于"要回复"
 *   snapshot(agentId)  她此刻怎样
 *   recover(agentId)   接着做
 * </pre>
 *
 * <h2>为什么是门面, 而不是一个新引擎</h2>
 * 设计文档描述的目标是"把 agent 从 ChatBot 改成数字人", 读到这句话最自然的反应是
 * 写一个新的 {@code PersistentAgentRuntime} 把所有东西重做一遍。但底盘勘察的结论是
 * 反过来的: 这个系统里已经有一份<b>写对了的</b>每个 agent 一条队列 + 一条专属消费线程
 * ({@code PersonActor}), 一个已经跑起来的决策引擎({@code DecisionPolicyEngine}),
 * 一套已经定义好的意识阶梯常量({@code WorldEventType}), 和一张已经存在的通知表
 * ({@code phone_notifications})。
 *
 * <p>缺的从来不是部件, 是<b>接线</b>: 那个"消息一到就整段读出来"的入口
 * ({@code AgentRuntime.onChatMessageDelivered}) 把感知、注意、阅读、决策、
 * 表达五件事压成了一件事。所以这个类刻意<b>很薄</b> —— 它只是给出四个正确的动词,
 * 让后续的 Phase 有地方挂, 而不是在 V11 的第一阶段就把主链推倒。
 *
 * <h2>Phase 1 里它是惰性的</h2>
 * 这个类目前<b>没有任何生产调用者</b>。这不是没写完, 是刻意的顺序:
 * 先把"事件能被持久地收下、能被重放、能被她自己的线程串行处理"这件事做成真的,
 * 再让世界开始往这里发事件(V11 §25 的 Adapter → Shadow → Cutover 不许跳步)。
 *
 * <p>因此本类的存在价值此刻完全由测试证明: 幂等、串行、暂停不漏、重启重放。
 * 一条通不过测试的运行时不该接到 53 个真实 agent 上。
 */
@Service
@Slf4j
public class PersistentAgentRuntime {

    private final AgentMailbox mailbox;
    private final AgentSnapshotService snapshots;
    private final AgentRecoveryService recovery;

    public PersistentAgentRuntime(AgentMailbox mailbox,
                                  AgentSnapshotService snapshots,
                                  AgentRecoveryService recovery) {
        this.mailbox = mailbox;
        this.snapshots = snapshots;
        this.recovery = recovery;
    }

    /**
     * 世界把一件事交给她。
     *
     * @return true = 这封信是新收下的; false = 重复投递或信封不合法。
     *         调用者<b>不该</b>把 false 当成失败 —— 重复投递是正常运行的一部分
     *         (聊天平台的重试、outbox 的兜底都可能让同一件事来两次)。
     */
    public boolean accept(EventEnvelope envelope) {
        return mailbox.accept(envelope);
    }

    /**
     * 把她叫醒 —— <b>不是"让她回复"</b>。
     *
     * <p>设计文档 §2.1 里最容易被写坏的一条: 唤醒是一个<b>机会</b>, 不是一个命令。
     * 她醒过来之后可能看一眼又睡了, 可能决定不回, 也可能去干别的事。
     * 因此这个方法的返回值只说明"唤醒这件事被记下了", 不说明她做了什么。
     *
     * @param agentId   叫醒谁
     * @param reason    为什么叫醒(复用既有的 {@code WakeReason}, 它此前是死代码)
     * @param sourceKey 幂等键的来源。同一件事引起的唤醒只该发生一次;
     *                  但"今天九点的闹钟"和"明天九点的闹钟"是两件事,
     *                  所以调用者必须给一个能区分它们的键(比如带上日期)。
     */
    public boolean wake(String agentId, WakeReason reason, String sourceKey) {
        EventEnvelope envelope = EventEnvelope.withDeterministicId(
                agentId,
                AgentEventType.SCHEDULED_WAKEUP,
                EventSource.SCHEDULE,
                (reason == null ? "unspecified" : reason.name()) + "-" + sourceKey,
                Map.of("reason", reason == null ? "unspecified" : reason.name(),
                        "wakeKey", String.valueOf(sourceKey)));
        return accept(envelope.withPriority(EventPriority.IMPORTANT));
    }

    /** 她此刻怎样。只读, 不产生任何写入。 */
    public AgentSnapshot snapshot(String agentId) {
        return snapshots.snapshot(agentId);
    }

    /** 接着做 —— 重放这个 agent 近期还没拆的信。 */
    public AgentRecoveryService.RecoveryReport recover(String agentId) {
        return recovery.recover(agentId);
    }

    /** 信箱里还欠着几件事(全平台)。运维看板用。 */
    public long pendingInboxDepth(String agentId) {
        return mailbox.depthOf(agentId);
    }
}
