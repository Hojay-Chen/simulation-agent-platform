package com.luxera.companion.interaction;

/**
 * 交互行为(§四 + §十五): 收到消息后 Agent 先决定"要不要回复", 再决定"回复什么"。
 * DEFER = 看到了但本次不回(状态已 Appraisal), 由 Drives 竞争产生。
 */
public enum InteractionAction {
    /** 正常回复 */
    REPLY_NOW,
    /** 极短应和(哈哈→笑死) */
    SHORT_ACK,
    /** 延迟再回 */
    DELAY_REPLY,
    /** 等待/不打断 */
    WAIT,
    /** 合法地不回(未读/琐碎) */
    IGNORE,
    /** 看到了但不回(已读不回, 状态保留) */
    DEFER,
    /** 结束本轮对话 */
    END_CONVERSATION
}

