package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.perception.LifeSnapshot;
import com.luxera.companion.digitalhuman.perception.MindSnapshot;
import com.luxera.companion.digitalhuman.perception.PerceptionLevel;

/**
 * V10 §13 DecisionContext: 决策输入 —— 感知结果 + 全维度状态。
 *
 * importance: 事件重要性(0~1, 由认知层评估: 催问/情绪信号/关系权重等)。
 */
public record DecisionContext(
        String personId,
        ExternalEvent event,
        PerceptionLevel perceptionLevel,
        double importance,
        LifeSnapshot life,
        MindSnapshot mind,
        RelationshipSnapshot relationship
) {

    public static DecisionContext of(String personId, ExternalEvent event, PerceptionLevel perceptionLevel,
                                     double importance, LifeSnapshot life, MindSnapshot mind,
                                     RelationshipSnapshot relationship) {
        return new DecisionContext(personId, event, perceptionLevel, importance, life, mind, relationship);
    }
}
