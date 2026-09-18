package com.luxera.companion.phone;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V11 §7.2 —— <b>她的手机</b>: 两个动作, 一个是"有什么在响", 一个是"我要看"。
 *
 * <h2>为什么把"通知"和"读消息"分开成两个方法</h2>
 * 因为它们是两件事, 而今天它们被压在同一个方法里。设计文档指认的根子问题就在这里:
 *
 * <pre>
 *   今天:  消息送达 → 直接读取正文 → 感知 → 决策
 *   应该是: 消息送达 → 通知 → 注意 → 她自己决定要不要看 → 读 → 正文
 * </pre>
 *
 * <p>一旦正文在手, 后面所有的"她在忙 / 没注意到 / 已读不回"都只是已经知道内容之后
 * 找的借口。所以 {@link #notifications} <b>结构上拿不到正文</b> —— 它返回的
 * {@link NotificationSnapshot} 里没有一个字段能装下消息内容。这不是约定,
 * 是类型层面的事实。
 *
 * <h2>为什么 readMessages 要带 agentId 和 ReadPolicy</h2>
 * 带 agentId 是因为"用谁的手去读"决定了走哪条传输(她自己的设备, 还是平台直读);
 * 带 {@link ReadPolicy} 是因为读的范围决定了她的注意力被占掉多少。见 {@link ReadPolicy}。
 *
 * <h2>实现必须容忍"读不到"</h2>
 * 手机没电、没信号、设备没配对 —— 这些都不是异常, 是她生活的一部分。所以
 * {@link #readMessages} 不抛异常, 而是返回一个 {@code messages} 为空、
 * {@link MessageBatch#note()} 写清原因的批次。<b>"没消息"和"没读到"必须能分开</b>:
 * 前者是"没人找她", 后者是"她错过了什么", 而后者正是 V11 要让它可见的那件事。
 */
public interface PhoneCapability {

    /**
     * 手机上现在有什么。<b>不含正文。</b>
     *
     * @param agentId 谁的手机
     */
    NotificationSnapshot notifications(String agentId);

    /**
     * 她要看消息了 —— <b>正文只能从这里进来</b>。
     *
     * <p>这个方法被调用的时刻, 就是"阅读"这个台阶在意识阶梯上被踩下的时刻;
     * 调用方(V11 §7.2 的 {@code ReadMessagesAction})负责把这个时刻记进阶梯。
     *
     * @return 读到的批次。读不到时返回空批次 + 原因, <b>不抛异常</b>
     */
    MessageBatch readMessages(String agentId, String conversationId, ReadPolicy policy);

    /**
     * 手机此刻的样子。刻意只有元数据 —— 见类注释。
     *
     * @param unreadCount 未读通知数
     * @param pending     还没被读的那些通知(只有坐标, 没有内容)
     * @param available   手机现在可用吗(响铃/震动是否会被她感知到)
     */
    record NotificationSnapshot(String agentId, String conversationId,
                                int unreadCount, List<PendingNotification> pending,
                                boolean available, LocalDateTime takenAt) {

        public NotificationSnapshot {
            pending = pending == null ? List.of() : List.copyOf(pending);
        }

        public boolean isEmpty() {
            return unreadCount == 0;
        }

        /** 一条还没被读的通知。<b>没有 preview 字段</b> —— 她还没看, 所以还没有内容。 */
        public record PendingNotification(String messageId, String conversationId,
                                          boolean heard, boolean seen, boolean opened) {}
    }
}
