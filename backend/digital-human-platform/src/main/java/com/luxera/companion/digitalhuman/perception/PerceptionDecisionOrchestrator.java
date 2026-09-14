package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.decision.DecisionContext;
import com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine;
import com.luxera.companion.digitalhuman.decision.RelationshipSnapshot;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * PerceptionDecisionOrchestrator: V10 因果链局部落地 ——
 * ExternalEvent → Perception(规则评分) → Decision(策略决策)。
 *
 * 完整链路(方案 §1.2): World → Event → Perception → Awareness → Cognition
 * → Decision → Action → Reality。本编排器覆盖 Event→Perception→Decision 段,
 * Cognition(LLM)与 Action(Simulator)由后续轮次接入。
 */
@Component
public class PerceptionDecisionOrchestrator {

    private final PerceptionRuntime perceptionRuntime;
    private final DecisionPolicyEngine decisionEngine;
    private final SnapshotFactory snapshotFactory;
    private final RelationshipService relationshipService;

    public PerceptionDecisionOrchestrator(PerceptionRuntime perceptionRuntime,
                                          DecisionPolicyEngine decisionEngine,
                                          SnapshotFactory snapshotFactory,
                                          RelationshipService relationshipService) {
        this.perceptionRuntime = perceptionRuntime;
        this.decisionEngine = decisionEngine;
        this.snapshotFactory = snapshotFactory;
        this.relationshipService = relationshipService;
    }

    /** 评估一个外部事件: 感知 + 决策 */
    public PerceptionDecisionOutcome evaluate(ExternalEvent event, String userId, String companionId,
                                              double importance, LocalDateTime now) {
        PerceptionContext context = PerceptionContext.of(event,
                snapshotFactory.life(companionId, now),
                snapshotFactory.mind(companionId),
                snapshotFactory.device(companionId, now),
                snapshotFactory.environment());
        PerceptionResult perception = perceptionRuntime.perceive(event, context);

        DecisionContext decisionContext = DecisionContext.of(companionId, event,
                perception.level(), importance, context.life(), context.mind(),
                relationshipSnapshot(userId, companionId));
        DecisionPolicyEngine.DecisionOutcome decision = decisionEngine.decide(decisionContext);

        return new PerceptionDecisionOutcome(perception, decision, context);
    }

    private RelationshipSnapshot relationshipSnapshot(String userId, String companionId) {
        try {
            Relationship rel = relationshipService.find(userId, companionId);
            if (rel == null) {
                return RelationshipSnapshot.of("new", 0, 0, 0, 0);
            }
            return RelationshipSnapshot.of(rel.getRelationshipStage(), rel.getIntimacy(),
                    rel.getFamiliarity(), rel.getTension(), rel.getConnectionPressure());
        } catch (Exception e) {
            return RelationshipSnapshot.of("new", 0, 0, 0, 0);
        }
    }

    /** 编排结果 */
    public record PerceptionDecisionOutcome(PerceptionResult perception,
                                            DecisionPolicyEngine.DecisionOutcome decision,
                                            PerceptionContext context) {}
}
