package com.luxera.companion.digitalhuman.decision;

/**
 * V10 §13 DecisionPolicy(Policy Pattern):
 * 行为决策策略 —— 每个策略负责一类情境, 按优先级顺序执行,
 * 第一个支持当前情境的策略做出决策(复杂冲突才交给 LLM)。
 */
public interface DecisionPolicy {

    /** 策略名(追踪/解释) */
    String name();

    /** 本策略是否支持当前情境 */
    boolean supports(DecisionContext context);

    /** 做出决策 */
    PersonDecision decide(DecisionContext context);
}
