package com.luxera.companion.selfmodel;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 自我叙事呈现: 回答"她最近觉得自己怎样"(用户视角) */
@Component
public class SelfNarrativeService {

    private final SelfModelService selfModelService;

    public SelfNarrativeService(SelfModelService selfModelService) {
        this.selfModelService = selfModelService;
    }

    @Transactional(readOnly = true)
    public String narrative(String companionId) {
        SelfModel m = selfModelService.get(companionId);
        return m != null && m.getNarrative() != null ? m.getNarrative() : "";
    }

    /** 供 Prompt 注入: "她最近觉得自己: ..." */
    @Transactional(readOnly = true)
    public String describeSelf(String companionId, String name) {
        SelfModel m = selfModelService.get(companionId);
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        if (m.getNarrative() != null && !m.getNarrative().isBlank()) {
            sb.append(m.getNarrative());
        }
        if (m.getConcerns() != null && !m.getConcerns().isEmpty()) {
            sb.append(" 最近有点担心: ").append(String.join("、", m.getConcerns())).append("。");
        }
        if (m.getPlans() != null && !m.getPlans().isEmpty()) {
            sb.append(" 最近在计划: ").append(String.join("、", m.getPlans())).append("。");
        }
        return sb.toString();
    }
}
