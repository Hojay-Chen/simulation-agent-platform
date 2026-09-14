package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * IgnorePolicy: 不值得处理 → 忽略(不产生任何行动)。
 *
 * 情境: 完全没感知到 / 潜意识层面且不重要 / 琐碎且疲惫。
 * 真人不会对每一条消息都做出反应 —— 这是拟真关键(V10 MVP 验收 5/6)。
 */
@Component
@Order(1)
public class IgnorePolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "ignore";
    }

    @Override
    public boolean supports(DecisionContext context) {
        PerceptionLevel level = context.perceptionLevel();
        if (level == PerceptionLevel.NONE) {
            return true;
        }
        if (level == PerceptionLevel.SUBCONSCIOUS) {
            // 潜意识层面: 只有高重要性事件才值得被"想起"
            return context.importance() < 0.5;
        }
        // AWARE/FOCUSED 且琐碎(低重要性)且疲惫 → 懒得动
        return context.importance() < 0.25
                && context.mind() != null && context.mind().exhausted();
    }

    @Override
    public PersonDecision decide(DecisionContext context) {
        return new PersonDecision.IgnoreDecision(describe(context));
    }

    private static String describe(DecisionContext context) {
        return switch (context.perceptionLevel()) {
            case NONE -> "根本没感知到这件事";
            case SUBCONSCIOUS -> "隐约觉得有什么, 但没放在心上";
            default -> "太琐碎又没精力, 懒得理";
        };
    }
}
