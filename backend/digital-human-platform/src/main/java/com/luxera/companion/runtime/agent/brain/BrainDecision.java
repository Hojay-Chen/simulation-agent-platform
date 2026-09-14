package com.luxera.companion.runtime.agent.brain;

import com.luxera.companion.interaction.InteractionDecision;

import java.util.List;

/**
 * Brain 决策(§42): Brain 不输出自然语言, 只输出 Action + Intent + Schedule。
 * 用户可见文本只能来自 Expression Agent。
 */
public record BrainDecision(
        String action,
        double priority,
        List<String> reasonFactors,
        String expressionGoal,
        double confidence,
        boolean fallback,
        InteractionDecision baseline,
        int desiredLength,
        int messageCount,
        String delayHint,
        String styleHint) {

    public static final String REPLY = "REPLY";
    public static final String SHORT_ACK = "SHORT_ACK";
    public static final String CHECK_PHONE_FIRST = "CHECK_PHONE_FIRST";
    public static final String READ_NO_REPLY = "READ_NO_REPLY";
    public static final String IGNORE = "IGNORE";
    public static final String END_CONVERSATION = "END_CONVERSATION";

    /** 是否进入回复路径(需要生成表达) */
    public boolean shouldReply() {
        return REPLY.equals(action) || SHORT_ACK.equals(action) || END_CONVERSATION.equals(action);
    }

    /** 是否"看到了但不回"(需要创建待复查状态) */
    public boolean isDefer() {
        return READ_NO_REPLY.equals(action);
    }

    /** 是否先打开手机再看(需要第二次 Brain 评估) */
    public boolean isCheckPhoneFirst() {
        return CHECK_PHONE_FIRST.equals(action);
    }
}
