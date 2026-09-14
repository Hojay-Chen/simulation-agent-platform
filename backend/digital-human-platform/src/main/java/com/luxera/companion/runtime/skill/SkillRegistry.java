package com.luxera.companion.runtime.skill;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表(§52): 按技能 id 存取; 按 Agent 类型返回固定技能列表。
 * Runtime 根据 Agent 类型加载核心技能, 不让 Agent 自己决定加载什么。
 */
@Component
public class SkillRegistry {

    private final SkillLoader loader;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /** Agent 类型 → 固定技能列表(核心技能固定注入, 任务技能动态) */
    private static final Map<String, List<String>> AGENT_SKILLS = Map.of(
            "emotion", List.of("core.identity", "core.personality", "core.relationship", "emotion.appraisal"),
            "memory", List.of("memory.recall"),
            "event", List.of("core.personality", "event.simulation"),
            "expression", List.of("core.identity", "core.personality", "core.relationship",
                    "expression.generation", "expression.cadence"),
            "brain", List.of("core.identity", "core.personality", "core.relationship", "brain.executive")
    );

    public SkillRegistry(SkillLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    void init() {
        for (Skill s : loader.loadAll()) {
            skills.put(s.id(), s);
        }
    }

    public Skill get(String id) {
        return skills.get(id);
    }

    /** 某 Agent 类型的技能列表 */
    public List<Skill> listForAgent(String agentType) {
        List<String> ids = AGENT_SKILLS.getOrDefault(agentType, List.of());
        List<Skill> out = new ArrayList<>();
        for (String id : ids) {
            Skill s = skills.get(id);
            if (s != null) out.add(s);
        }
        return out;
    }

    public Map<String, Skill> all() {
        return skills;
    }
}
