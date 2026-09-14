package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * ReplyNowPolicy: 立即回复。
 *
 * 情境: 完全注意到且事件重要(催问/情绪信号/亲密关系高联系压力)。
 * 真人被重要的人催问时会放下手头的事直接回。
 */
@Component
@Order(3)
public class ReplyNowPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "reply-now";
    }

    @Override
    public boolean supports(DecisionContext context) {
        PerceptionLevel level = context.perceptionLevel();
        if (level != PerceptionLevel.FOCUSED) {
            return false;
        }
        double importance = context.importance();
        // 高重要性直接回; 亲密关系 + 联系压力 → 也会直接回
        return importance >= 0.6
                || (context.relationship() != null && context.relationship().intimate()
                    && context.relationship().pressured() && importance >= 0.4);
    }

    @Override
    public PersonDecision decide(DecisionContext context) {
        return new PersonDecision.ReplyDecision("完全注意到了, 这件事值得马上回应");
    }
}
