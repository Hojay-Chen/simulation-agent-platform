package com.luxera.companion.digitalhuman.relationship;

import java.time.Instant;

/**
 * V10 §17 InteractionSummary: 从 Reality Ledger 投影出的互动摘要(Projection Pattern)。
 *
 * 关系的**事实层**(互动次数/模式/最近互动)一律从账本投影, 不手工维护 ——
 * Memory/模型输出不能覆盖 Reality(MVP 验收 10 的关系侧延伸)。
 */
public record InteractionSummary(
        long totalEvents,
        long messagesSentByPerson,
        long messagesRead,
        long messagesDeferred,
        long messagesIgnored,
        long activitiesEnded,
        Instant lastInteractionAt,
        Instant firstInteractionAt
) {

    public static InteractionSummary empty() {
        return new InteractionSummary(0, 0, 0, 0, 0, 0, null, null);
    }

    /** 回复率: 已回复(发送) / 读到(需要回应的用户消息) */
    public double replyRate() {
        long responded = messagesRead;
        return responded == 0 ? 0 : (double) messagesSentByPerson / responded;
    }

    /** 是否从未互动 */
    public boolean noInteraction() {
        return totalEvents == 0;
    }
}
