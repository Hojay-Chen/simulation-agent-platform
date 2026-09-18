package com.luxera.companion.cognition;

import java.time.LocalDateTime;

/**
 * V11 §12.1 —— <b>一次认知的结论</b>。
 *
 * <p>{@code {"decision": "DEFER", "reason": "currently_busy", "nextWakeupAt": "..."}}
 * 就是本类。设计文档 §12.1 的原话是"Agent 每次 Cognition 后必须产生 decision,
 * 而不是 response" —— 本类是那句话的落地。
 *
 * <h2>为什么 {@code reason} 是字符串而不是枚举</h2>
 * 因为理由的集合是<b>开放</b>的, 而行动的集合是<b>封闭</b>的。
 * "为什么押后"可以是 currently_busy / sleeping / not_noticed / low_value /
 * reality_conflict / state_version_conflict ……这个清单会随着认知变聪明而变长;
 * 而"她能做什么"是一份产品决定, 不该因为多了一个理由就改枚举。
 * 把理由做成枚举的后果很具体: 要么枚举被撑到几十个值(那时它已经不是枚举了),
 * 要么新理由被硬塞进一个意思相近的旧值里 —— 于是日志开始说谎。
 *
 * <h2>{@code nextWakeupAt} 只有一部分决策有</h2>
 * 它是"这个决策什么时候该被重新考虑", 不是"日程"。{@link DecisionType#REPLY} 没有
 * (回了就了结了), {@link DecisionType#DEFER} 必须有 —— 一个没有复查时刻的 DEFER
 * 与"永远不回"是同一件事, 而它看上去像是"待会儿就回"。所以
 * {@link #needsWakeup()} 把这条不变量变成一个可以被断言的方法。
 */
public record CognitiveDecision(DecisionType type,
                                String reason,
                                LocalDateTime nextWakeupAt,
                                double confidence) {

    public CognitiveDecision {
        if (type == null) {
            throw new IllegalArgumentException("决策不能没有类型 —— 没有类型的决策不是'默认'，是'不知道'");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("决策必须说明理由 —— 否则日志里只剩一个动词，无法归因");
        }
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public static CognitiveDecision of(DecisionType type, String reason) {
        return new CognitiveDecision(type, reason, null, 1.0);
    }

    /** 押后 / 等待类决策: 必须带上"什么时候再看一眼"。 */
    public static CognitiveDecision until(DecisionType type, String reason, LocalDateTime nextWakeupAt) {
        return new CognitiveDecision(type, reason, nextWakeupAt, 1.0);
    }

    /** "以后再说"类的决策必须有一个复查时刻, 否则它与静默丢消息无法区分。 */
    public boolean needsWakeup() {
        return type.needsFollowUp() && nextWakeupAt == null;
    }

    // ─────────────────────── 为什么这里没有"老链 → 新词表"的适配器 ───────────────────────
    //
    // Phase 4 曾在这里放过四个单向适配器 —— from(Outcome, reason) / fromBrain(action, reason) /
    // from(PersonDecision, now) / from(PersonDecision) —— Phase 6 把它们删了,
    // 因为它们一次都没有被 src/main 里的任何代码调用过, 从头到尾只有它们自己的测试在用。
    //
    // 删掉不是因为"暂时用不上", 而是因为它们声称的那件事是错的: 注释写着
    // "shadow 期的差异率靠它把老链的 Outcome 读成新词表算出来", 而真正的差异率在
    // CognitionDecisionRecorder.classify —— 它比的是"会不会写一条消息出去"
    // (DecisionType.producesOutboundMessage) 与老链这一次到底有没有发消息, 刻意不比决策名字。
    // 这是两种对照里更结实的一种: 决策名是两套词表各自的方言, 拿方言对齐只会得到一张
    // 永远需要维护的映射表; 而"她会不会开口"不需要翻译。
    //
    // 留着它们的代价很具体: 后来的人读到一个"并跑期观察设施"的注释, 会去找它在哪被用,
    // 找不到, 只能怀疑自己看漏了。真需要按决策名对照时, 从 git 历史里取回即可 ——
    // 那时它也会带着一个说真话的理由活过来。
}
