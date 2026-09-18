package com.luxera.companion.mind;

import java.time.LocalDateTime;

/**
 * V11 §9.2 / §23.4 —— <b>她此刻手上挂着的一条线</b>。
 *
 * <h2>它为什么必须存在(而 CognitiveSession 不够)</h2>
 * {@code cognitive_sessions} 只有一组 {@code current_focus / current_thought / current_intention}
 * —— 一条消息写一次, 第二条消息把它<b>盖掉</b>。这正是设计文档 §2.2.4 指出的那个毛病:
 * 有两条线同时在谈时, 她"当前在想什么"永远只剩最后一条。线程是<b>按会话分的</b>,
 * 所以 A 在说的事不会因为 B 发了消息而从她脑子里消失。
 *
 * <h2>它刻意不带 topic</h2>
 * 一个 {@code topic} 字段在这里很诱人, 但它在<b>结构上</b>填不出来: 线程是在聚合阶段
 * (收到消息、还没读正文)建立的, 那时没有任何东西知道这条线在谈什么。唯一能填的时机是
 * 认知之后 —— 而那时候 {@code CognitiveSession.currentFocus} 已经在记了。
 * 所以这里不设这个字段: 一个恒为 null 的字段会让人以为"她还没想明白",
 * 而不是"这个字段填不出来"。话题看 {@link FocusState}。
 *
 * @param conversationId 这条线挂在哪次会话上
 * @param userId         对面是谁
 * @param state          只有 {@link #STATE_OPEN} / {@link #STATE_QUIET_WAIT} 会被持久化(见下)
 * @param pendingCount   她还欠这条线几条没处理的消息
 * @param openedAt       这条线是什么时候挂上的
 * @param lastMessageAt  最近一条消息什么时候到的
 */
public record WorkingThread(String conversationId, String userId, String state, int pendingCount,
                            LocalDateTime openedAt, LocalDateTime lastMessageAt) {

    /** 有消息进来了, 还在等更多。 */
    public static final String STATE_OPEN = "OPEN";

    /** 安静下来了(静默窗口在走), 但还没封口。 */
    public static final String STATE_QUIET_WAIT = "QUIET_WAIT";

    /**
     * 一个正在被她思考的线。
     *
     * <p>落库的状态<b>只有</b> OPEN 与 QUIET_WAIT 两个。SEALED / PROCESSING 是回合的瞬时状态,
     * 不进 {@code WorkingThread}: 一个重启后的进程没有任何资格声称"她正在想这件事" ——
     * 真实情况是那个回合已经随着进程一起没了。持久化一个正在进行中的动作,
     * 只会让下一次读它的人以为它还在进行。
     */
    public WorkingThread {
        if (state == null || state.isBlank()) {
            state = STATE_OPEN;
        }
        if (pendingCount < 0) {
            pendingCount = 0;
        }
    }

    /** 她欠这条线东西吗。 */
    public boolean hasPending() {
        return pendingCount > 0;
    }

    /** 同一会话上再进来一批消息之后的样子。 */
    public WorkingThread extended(int newPendingCount, LocalDateTime at) {
        return new WorkingThread(conversationId, userId, STATE_QUIET_WAIT,
                newPendingCount, openedAt, at);
    }
}
