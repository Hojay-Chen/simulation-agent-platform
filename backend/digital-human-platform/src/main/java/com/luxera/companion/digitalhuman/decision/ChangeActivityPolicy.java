package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * ChangeActivityPolicy: 生活事件 → 改变活动。
 *
 * 情境: 生活事件(活动开始/结束/计划变化) → 数字人调整当前活动。
 * 这是"生活持续运行"的决策侧落点(计划被突发事件打断 → 改变计划)。
 */
@Component
@Order(4)
public class ChangeActivityPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "change-activity";
    }

    @Override
    public boolean supports(DecisionContext context) {
        return context.event() != null && context.event().type() == ExternalEventType.LIFE_EVENT;
    }

    @Override
    public PersonDecision decide(DecisionContext context) {
        String activity = context.event().str("activity");
        return new PersonDecision.ChangeActivityDecision(
                "生活事件发生, 调整当前活动", activity == null ? "NEW_ACTIVITY" : activity);
    }
}
