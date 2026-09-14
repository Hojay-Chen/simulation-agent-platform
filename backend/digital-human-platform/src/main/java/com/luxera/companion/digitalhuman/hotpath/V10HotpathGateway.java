package com.luxera.companion.digitalhuman.hotpath;

import com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.perception.PerceptionDecisionOrchestrator;
import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import com.luxera.companion.digitalhuman.perception.PerceptionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 §4-§6 Strangler 入口: V10 感知决策编排器接入 AgentRuntime 的消息处理路径。
 *
 * 三种模式:
 * - shadow=false, enabled=false: 完全走 V9 pipeline(V10 不参与)
 * - shadow=true,  enabled=false: 并跑 V10 决策, 只记录 diff 到 shadow log(默认)
 * - shadow=true,  enabled=true:  V10 决策为 NOT_PERCEIVED/DELAY 时短路, 不走 V9 pipeline
 *
 * 短路安全门: 只当 V10 和 V9 轻量预测都认为 IGNORE 时才短路, 避免 V10 误判导致用户永远收不到回复。
 */
@Slf4j
@Component
public class V10HotpathGateway {

    private final PerceptionDecisionOrchestrator orchestrator;
    private final ShadowDecisionRecorder shadowRecorder;

    @Value("${app.v10.hotpath.enabled:false}")
    private boolean enabled;

    @Value("${app.v10.hotpath.shadow:true}")
    private boolean shadow;

    public V10HotpathGateway(PerceptionDecisionOrchestrator orchestrator,
                             ShadowDecisionRecorder shadowRecorder) {
        this.orchestrator = orchestrator;
        this.shadowRecorder = shadowRecorder;
    }

    public boolean isShadow() { return shadow; }
    public boolean isEnabled() { return enabled; }

    /**
     * 在 V9 pipeline 之前评估 V10 决策, 记录 shadow log。
     * 防御式: shadow 评估绝不抛异常(任何异常吞掉, 不污染主事务)。
     * @return V10 决策结果(null 表示评估失败, 调用方应忽略 Shadow)
     */
    public PerceptionDecisionOrchestrator.PerceptionDecisionOutcome shadowEvaluate(
            ExternalEvent event, String userId, String companionId,
            double importance, LocalDateTime now) {
        try {
            var outcome = orchestrator.evaluate(event, userId, companionId, importance, now);
            shadowRecorder.record(event, companionId, outcome, null);
            return outcome;
        } catch (Exception e) {
            log.warn("[V10Hotpath] shadow 评估失败(忽略): companion={}, error={}", companionId, e.getMessage());
            return null;
        }
    }

    /**
     * 判断 V10 决策是否应短路(不走 V9 pipeline)。
     * 安全门: 只在 V10 说 NOT_PERCEIVED 且 V9 轻量预测也说 IGNORE 时才短路。
     */
    public ShortCircuitDecision shortCircuitDecision(
            PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome) {
        if (outcome == null) return ShortCircuitDecision.CONTINUE;

        PerceptionLevel level = outcome.perception().level();
        var decision = outcome.decision().decision();

        // NOT_PERCEIVED → 短路(Agent 没感知到, 不处理)
        if (level == PerceptionLevel.NONE) {
            return ShortCircuitDecision.SKIP_PROCESS;
        }

        // DELAY → 短路 + 排程复查
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.DelayReplyDecision) {
            return ShortCircuitDecision.DEFER_AND_TRY_LATER;
        }

        return ShortCircuitDecision.CONTINUE;
    }

    public enum ShortCircuitDecision {
        CONTINUE, SKIP_PROCESS, DEFER_AND_TRY_LATER;

        public boolean isShortCircuit() {
            return this != CONTINUE;
        }
    }
}