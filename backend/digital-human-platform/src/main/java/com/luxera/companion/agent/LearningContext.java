package com.luxera.companion.agent;

import com.luxera.companion.usermodel.UserModelService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习上下文(设计文档 §29): 供后台反思/记忆/人格学习使用的独立上下文, 与 Prompt Context 分离。
 */
public record LearningContext(
        String companionId,
        String name,
        List<String> recentExperienceSummary,
        UserModelService.UserModelSummary userModel,
        String lifeSummary,
        LocalDateTime now) {

    /** 压缩为学习用文本 */
    public String toLearningText() {
        StringBuilder sb = new StringBuilder();
        if (recentExperienceSummary != null && !recentExperienceSummary.isEmpty()) {
            sb.append("最近经历:\n");
            for (String e : recentExperienceSummary) sb.append("- ").append(e).append("\n");
        }
        if (userModel != null && userModel.patterns() != null && !userModel.patterns().isEmpty()) {
            sb.append("用户习惯: ").append(String.join(";", userModel.patterns())).append("\n");
        }
        if (lifeSummary != null && !lifeSummary.isBlank()) {
            sb.append("生活: ").append(lifeSummary).append("\n");
        }
        return sb.toString();
    }
}
