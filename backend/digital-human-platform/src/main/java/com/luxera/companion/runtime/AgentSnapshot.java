package com.luxera.companion.runtime;

import java.time.LocalDateTime;
import java.util.List;

/** V11 §4.1 —— 一个 agent 在某一刻的<b>可序列化切面</b>。
 *
 * <h2>它是视图, 不是状态</h2>
 * 这个类<b>不持有</b>任何东西。她的状态散在若干张表里({@code agent_states} 的身体与情绪、
 * {@code cognitive_sessions} 的当前念头、{@code phone_notifications} 的通知阶梯、
 * {@code open_loops} 的悬而未决、{@code agent_inbox} 的未拆信件),
 * 这是数据该有的样子 —— 每张表有它自己的写入者与生命周期。
 *
 * <p>但"她现在怎么样了"这个问题不该要求调用者知道五张表的连接方式。快照把这件事
 * 收成一个不可变对象: 恢复时用它判断"重启前她欠着什么", 运维时用它回答"她是不是卡住了",
 * 测试时用它断言行为。
 *
 * <h2>它刻意不含的东西</h2>
 * <ul>
 *   <li><b>任何消息正文</b> —— 快照可以在没有用户在场时被打印、被写进日志、
 *       被发给运维看板。带上正文就等于把私聊内容散到每一个看得见快照的地方。</li>
 *   <li><b>persona / 提示词</b> —— 那是"她是谁", 不是"她此刻怎样"。
 *       前者几乎不变, 后者每秒钟都在变; 混在一起会让快照无法被安全地缓存。</li>
 * </ul>
 */
public record AgentSnapshot(
        String agentId,
        String lifecycle,
        LocalDateTime takenAt,
        State state,
        Attention attention) {

    /** 她此刻的内在状态。字段直接对应 {@code agent_states} + {@code cognitive_sessions}。 */
    public record State(String mood,
                        double energy,
                        double stress,
                        double focus,
                        String currentFocus,
                        String currentThought,
                        String currentIntention,
                        long stateVersion) {
    }

    /**
     * 她此刻欠着什么 —— 全是<b>计数</b>, 没有一条内容。
     *
     * <p>{@code pendingInbox} 是 V11 新增的那一维: 在信箱落地之前, "她还欠着几件事"
     * 在系统里根本不存在, 因此一个丢事件的进程和一个只是很忙的 agent 长得一模一样。
     */
    public record Attention(int unreadNotifications,
                            long pendingInbox,
                            int openLoops,
                            int activeIntentions) {
    }

    public boolean paused() {
        return "PAUSED".equalsIgnoreCase(lifecycle);
    }

    /** 她是不是"卡住了": 有活要干, 但一条都没在推进。运维看这个。 */
    public boolean stalled() {
        return !paused() && attention != null && attention.pendingInbox() > 0;
    }
}
