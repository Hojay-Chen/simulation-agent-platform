package com.luxera.companion.behavior;

import java.time.LocalDateTime;

/**
 * §三十五/§三十八 行为选择结果: 选中了什么、为什么、得分多少。
 * (与遗留 BehaviorPolicyEngine 的 BehaviorDecision 区分)
 */
public record BehaviorOutcome(
        BehaviorAction action,
        String trigger,
        double score,
        String reason,       // 触发上下文(TIME_TICK / MESSAGE_* / 手动)
        LocalDateTime decidedAt) {
}
