package com.luxera.companion.interaction;

/**
 * 回复预算(设计文档 §八/十/十一): 长度是行为结果, 不是 Prompt 固定。
 */
public class ResponseBudget {

    public final int maxCharacters;
    public final int maxSentences;
    /** 允许问几个问题(0=不追问) */
    public final int questionBudget;
    /** 允许给几条建议(0=不指导) */
    public final int adviceBudget;
    /** 情绪强度(影响语气, 0-1) */
    public final double emotionalIntensity;
    /** 是否允许分享自己的事 */
    public final boolean allowSelfDisclose;

    public ResponseBudget(int maxCharacters, int maxSentences, int questionBudget, int adviceBudget,
                          double emotionalIntensity, boolean allowSelfDisclose) {
        this.maxCharacters = maxCharacters;
        this.maxSentences = maxSentences;
        this.questionBudget = questionBudget;
        this.adviceBudget = adviceBudget;
        this.emotionalIntensity = emotionalIntensity;
        this.allowSelfDisclose = allowSelfDisclose;
    }

    /** 按投入级别生成默认预算 */
    public static ResponseBudget forCommitment(ResponseCommitment c, boolean intimate) {
        return switch (c) {
            case ACK -> new ResponseBudget(12, 1, 0, 0, 0.3, false);
            case CASUAL -> new ResponseBudget(40, 2, 0, 0, 0.4, false);
            case ENGAGED -> new ResponseBudget(120, 3, 1, 0, 0.6, intimate);
            case DEEP -> new ResponseBudget(260, 5, 1, 1, 0.8, intimate);
        };
    }
}
