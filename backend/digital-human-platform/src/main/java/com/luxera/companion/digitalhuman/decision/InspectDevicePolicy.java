package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * InspectDevicePolicy: 查看设备 —— 值得看一眼但不必立即回。
 *
 * 情境: 感知到(AWARE/FOCUSED)且重要性尚可, 但不足以直接回复:
 * 先打开会话看看是什么事, 看完再决定回不回。
 * (V10 §25 时序: Decision → Action(Inspect Device) → 读取消息 → 再决策是否回复)
 */
@Component
@Order(5)
public class InspectDevicePolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "inspect-device";
    }

    @Override
    public boolean supports(DecisionContext context) {
        PerceptionLevel level = context.perceptionLevel();
        if (level != PerceptionLevel.AWARE && level != PerceptionLevel.FOCUSED) {
            return false;
        }
        // 有感知且重要性达到"值得看一眼"
        return context.importance() >= 0.3;
    }

    @Override
    public PersonDecision decide(DecisionContext context) {
        return new PersonDecision.InspectDeviceDecision("注意到了, 先看看是什么事");
    }
}
