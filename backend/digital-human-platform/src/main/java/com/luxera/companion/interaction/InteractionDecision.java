package com.luxera.companion.interaction;

/**
 * 交互决策(设计文档 §四): 收到消息后先决定行为, 再由运行时执行。
 */
public class InteractionDecision {

    public final InteractionAction action;
    public final ResponseCommitment commitment;
    /** 发送延迟(ms), 由 ResponseLatencyEngine 计算 */
    public final long delayMs;
    /** 是否继续本轮对话 */
    public final boolean continueConversation;
    /** 是否问问题 */
    public final boolean askQuestion;
    /** 是否分享自己 */
    public final boolean selfDisclose;
    public final String reason;
    public final double confidence;
    public final ResponseBudget budget;

    public InteractionDecision(InteractionAction action, ResponseCommitment commitment, long delayMs,
                               boolean continueConversation, boolean askQuestion, boolean selfDisclose,
                               String reason, double confidence, ResponseBudget budget) {
        this.action = action;
        this.commitment = commitment;
        this.delayMs = delayMs;
        this.continueConversation = continueConversation;
        this.askQuestion = askQuestion;
        this.selfDisclose = selfDisclose;
        this.reason = reason;
        this.confidence = confidence;
        this.budget = budget;
    }

    public boolean shouldSend() {
        return action != InteractionAction.IGNORE && action != InteractionAction.WAIT
                && action != InteractionAction.DEFER;
    }
}
