package com.luxera.companion.digitalhuman.decision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V10 §13 Decision Runtime: 决策引擎(Policy Pattern + State Pattern 心智)。
 *
 * 策略按 @Order 优先级执行, 第一个支持当前情境的策略做出决策:
 * Ignore(1) → ReplyLater(2) → ReplyNow(3) → ChangeActivity(4) → InspectDevice(5)。
 *
 * 语义: 没感知→忽略; 感知且忙/疲惫→稍后回; 完全注意到且重要→立即回;
 * 生活事件→调整活动; 其余值得看→先查看。复杂冲突才调用 LLM。
 */
@Slf4j
@Component
public class DecisionPolicyEngine {

    private final List<DecisionPolicy> policies;

    public DecisionPolicyEngine(List<DecisionPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    /** 做出决策; 返回命中策略 + 决策 */
    public DecisionOutcome decide(DecisionContext context) {
        for (DecisionPolicy policy : policies) {
            try {
                if (policy.supports(context)) {
                    PersonDecision decision = policy.decide(context);
                    log.debug("[Decision] {} → {} ({})", context.personId(), policy.name(),
                            decision.reason());
                    return new DecisionOutcome(policy.name(), decision);
                }
            } catch (Exception e) {
                log.warn("[Decision] 策略 {} 执行失败: {}", policy.name(), e.getMessage());
            }
        }
        // 保守回退: 什么策略都不匹配 → 忽略(不会乱行动)
        return new DecisionOutcome("fallback",
                new PersonDecision.IgnoreDecision("没有策略匹配当前情境, 保守忽略"));
    }

    /** 决策结果: 命中策略名 + 决策 */
    public record DecisionOutcome(String policy, PersonDecision decision) {}
}
