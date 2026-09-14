package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * V10 §10 Perception Runtime: 感知引擎(规则 + 评分, 不调用 LLM)。
 *
 * 流程: ExternalEvent + PerceptionContext → 按事件类型选择策略 → 评分 → 等级映射。
 *
 * 等级阈值(V10 §10.4):
 *   score < 0.2        → NONE
 *   0.2 ~ 0.5          → SUBCONSCIOUS
 *   0.5 ~ 0.8          → AWARE
 *   > 0.8              → FOCUSED
 *
 * 设计: Strategy Pattern 注册表 —— 新增事件类型只需新增一个 PerceptionStrategy。
 */
@Slf4j
@Component
public class PerceptionRuntime {

    /** 等级阈值(可配置) */
    static final double NONE_THRESHOLD = 0.2;
    static final double SUBCONSCIOUS_THRESHOLD = 0.5;
    static final double AWARE_THRESHOLD = 0.8;

    private final Map<com.luxera.companion.digitalhuman.event.ExternalEventType, PerceptionStrategy> strategies;

    public PerceptionRuntime(List<PerceptionStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(PerceptionStrategy::eventType, Function.identity()));
    }

    /** 感知一个外部事件; 无匹配策略时返回 NONE */
    public PerceptionResult perceive(ExternalEvent event, PerceptionContext context) {
        if (event == null || context == null) {
            return PerceptionResult.of(PerceptionLevel.NONE, 0, "no-context");
        }
        PerceptionStrategy strategy = strategies.get(event.type());
        if (strategy == null) {
            log.debug("[Perception] {} 无感知策略, 视为 NONE", event.type());
            return PerceptionResult.of(PerceptionLevel.NONE, 0, "none");
        }
        double score = clamp(strategy.evaluate(context));
        return PerceptionResult.of(level(score), score, strategy.name());
    }

    /** 评分 → 等级 */
    public static PerceptionLevel level(double score) {
        if (score < NONE_THRESHOLD) return PerceptionLevel.NONE;
        if (score < SUBCONSCIOUS_THRESHOLD) return PerceptionLevel.SUBCONSCIOUS;
        if (score < AWARE_THRESHOLD) return PerceptionLevel.AWARE;
        return PerceptionLevel.FOCUSED;
    }

    /** 已注册的策略(诊断用) */
    public List<String> registeredStrategies() {
        return strategies.values().stream().map(PerceptionStrategy::name).toList();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
