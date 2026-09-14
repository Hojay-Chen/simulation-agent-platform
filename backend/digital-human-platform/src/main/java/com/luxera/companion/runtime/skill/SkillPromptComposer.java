package com.luxera.companion.runtime.skill;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能提示组合器(§54): 按推荐顺序组合 Agent 的 system prompt:
 * Agent Role → Core Identity → Personality → Relationship → Agent Skill → Context → Task。
 * 防止"一个巨大 prompt"。
 */
@Component
public class SkillPromptComposer {

    private final SkillRegistry registry;

    public SkillPromptComposer(SkillRegistry registry) {
        this.registry = registry;
    }

    /** 组合某 Agent 类型的技能基座(不含当前上下文与任务, 由 Agent 自行追加) */
    public String composeBase(String agentType, String agentRole) {
        StringBuilder sb = new StringBuilder();
        if (agentRole != null && !agentRole.isBlank()) {
            sb.append("【角色】").append(agentRole).append("\n\n");
        }
        for (Skill s : registry.listForAgent(agentType)) {
            sb.append("【").append(displayName(s.id())).append("】\n")
                    .append(s.content()).append("\n\n");
        }
        return sb.toString();
    }

    private static String displayName(String id) {
        int idx = id.lastIndexOf('.');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }
}
