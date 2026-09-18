package com.luxera.companion.mailbox;

import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;

/**
 * V11 §4.1 —— <b>拆信的人</b>。
 *
 * <p>信箱只负责"信安全地躺在那儿、且只会被交给一个人"; 至于信拆开之后发生什么,
 * 由实现者决定。这条分界让信箱可以在<b>还没有任何消费者</b>的时候先上线并接受检验 ——
 * V11 Phase 1 正是这个状态: 事件开始被持久地收下, 但还没有任何一条认知链去读它,
 * 因此线上 53 个 agent 的行为一个字节都没有变。
 *
 * <p>注册方式是 Spring 的 {@code List<AgentInboxConsumer>} 注入(与
 * {@code AgentEventHandler} / {@code DecisionPolicy} / {@code ActionHandler} 同一套路),
 * 因此加一个消费者不需要改动信箱本身。
 *
 * <p>实现必须遵守两条:
 * <ol>
 *   <li><b>幂等不是你的责任</b> —— 信箱已经保证同一条信只会被交给你一次(claim 是条件更新)。
 *       但你仍然应该容忍重放: 租约超时回收会让一条信被交第二次, 这是为了崩溃恢复
 *       而付出的代价, 消费者必须能承受。</li>
 *   <li><b>不要在这里决定"要不要回"</b> —— 拆信是感知, 决策是决策。把两件事揉在一起
 *       正是旧链 {@code onChatMessageDelivered} 直接读正文的根源。</li>
 * </ol>
 */
public interface AgentInboxConsumer {

    /** 你处理哪一类事件。 */
    boolean supports(AgentEventType type);

    /**
     * 拆开一封信。抛异常 = 这次没处理好, 信箱会按 {@link AgentInboxEntry#MAX_ATTEMPTS}
     * 重试, 到顶转 FAILED 并留下 {@code lastError}。
     */
    void consume(EventEnvelope envelope);
}
