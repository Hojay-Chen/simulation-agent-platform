package com.luxera.companion.runtime.skill;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V9 §21 Skill 按需加载: 根据感知 Intent 选择相关技能, 注入当前轮(L3),
 * 不破坏 L0 稳定前缀(技能内容属于"当前任务", 放动态层)。
 */
@Component
public class SkillSelector {

    private final SkillRegistry registry;

    /** Intent → 技能 id(按需, 不每轮全量加载) */
    private static final Map<String, List<String>> INTENT_SKILLS = Map.ofEntries(
            Map.entry("sad", List.of("emotion.appraisal", "expression.generation")),
            Map.entry("angry", List.of("emotion.appraisal")),
            Map.entry("anxious", List.of("emotion.appraisal")),
            Map.entry("lonely", List.of("emotion.appraisal")),
            Map.entry("happy", List.of("expression.generation", "expression.cadence")),
            Map.entry("grateful", List.of("expression.generation")),
            Map.entry("planning", List.of("event.simulation")),
            Map.entry("farewell", List.of("expression.cadence")),
            Map.entry("greeting", List.of("expression.cadence")),
            Map.entry("question", List.of("core.relationship")),
            Map.entry("relationship", List.of("core.relationship", "emotion.appraisal")),
            Map.entry("health", List.of("core.relationship")),
            Map.entry("correction", List.of("core.relationship"))
    );

    public SkillSelector(SkillRegistry registry) {
        this.registry = registry;
    }

    /** 按 intent 选中技能并拼接为提示文本(放当前轮 L3; 无匹配返回 null) */
    public String composeForIntent(String intent) {
        if (intent == null || intent.isBlank()) return null;
        List<String> ids = INTENT_SKILLS.getOrDefault(intent, List.of());
        if (ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            Skill s = registry.get(id);
            if (s == null) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(s.content() == null ? "" : s.content().trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 调试/观测: intent 命中的技能 */
    public List<String> skillIdsFor(String intent) {
        if (intent == null) return List.of();
        return new ArrayList<>(INTENT_SKILLS.getOrDefault(intent, List.of()));
    }
}
