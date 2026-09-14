package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEventType;

/**
 * V10 §10.4 PerceptionStrategy(Strategy Pattern):
 * 不同事件类型使用不同的感知策略 —— 感知这里不应该调用 LLM,
 * 应该用 规则 + 评分(Rule Engine + Scoring)。
 *
 * 每个策略决定自己处理哪种事件类型, 并对上下文输出 0~1 感知评分。
 */
public interface PerceptionStrategy {

    /** 本策略处理的事件类型 */
    ExternalEventType eventType();

    /** 感知评分: 0(完全没感知) ~ 1(强烈感知); 评分含义由 PerceptionRuntime 映射为等级 */
    double evaluate(PerceptionContext context);

    /** 策略名称(诊断/追踪用) */
    default String name() {
        return getClass().getSimpleName();
    }
}
