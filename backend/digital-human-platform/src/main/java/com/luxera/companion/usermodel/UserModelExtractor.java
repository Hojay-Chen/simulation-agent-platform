package com.luxera.companion.usermodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户理解抽取: 从对话中提炼事实/偏好/推测,检测用户纠正。
 * (设计文档 17-22 节)
 */
@Slf4j
@Component
public class UserModelExtractor {

    private static final String SYSTEM = """
            你是用户理解引擎。从一段对话中提取对用户的长期理解,只提取值得长期记住的。
            输出严格 JSON:
            {
              "facts": [{"predicate":"likes|prefers|works_as|studies|has|wants|dislikes|...","object":"咖啡","confidence":0-1,"source":"explicit|inferred"}],
              "preferences": [{"category":"communication|social|schedule|food|health|work","preference":"does_not_like_long_advice","confidence":0-1,"source":"explicit|inferred"}],
              "hypotheses": [{"hypothesis":"user_may_prefer_solitude","description":"可能更喜欢安静","confidence":0-1,"evidence":["依据1"]}],
              "corrections": [{"topic":"之前的理解","statement":"纠正后的理解"}]
            }
            规则:
            - 用户明确说出 → source=explicit,置信 0.85+;从行为观察推断 → inferred,置信 ≤0.7。
            - 用户纠正(如"不是.../其实...")→ 放入 corrections。
            - 没有就返回空数组。不要输出任何其他内容。""";

    private final LlmRouter llm;
    private final UserModelService userModelService;

    public UserModelExtractor(LlmRouter llm, UserModelService userModelService) {
        this.llm = llm;
        this.userModelService = userModelService;
    }

    @Async
    public void extractFromExchange(String userId, String companionId, String sourceId,
                                    String userText, String assistantText) {
        try {
            String excerpt = "对话:\n用户: " + userText + "\n伴侣: " + assistantText + "\n\n提取对用户的长期理解。";
            var res = llm.structured(StructuredRequest.builder()
                    .task("user-model-extraction")
                    .system(SYSTEM)
                    .user(excerpt)
                    .temperature(0.2)
                    .build());
            JsonNode root = res.getJson();

            for (JsonNode n : root.path("facts")) {
                String predicate = n.path("predicate").asText("");
                String object = n.path("object").asText("");
                if (!StringUtils.hasText(predicate) || !StringUtils.hasText(object)) continue;
                UserFact f = new UserFact();
                f.setPredicate(predicate);
                f.setObject(object);
                f.setConfidence(clamp(n.path("confidence").asDouble(0.7)));
                f.setSourceType("explicit".equals(n.path("source").asText("inferred")) ? "explicit" : "inferred");
                f.setSourceId(sourceId);
                userModelService.saveFact(userId, companionId, f);
            }

            for (JsonNode n : root.path("preferences")) {
                String category = n.path("category").asText("communication");
                String pref = n.path("preference").asText("");
                if (!StringUtils.hasText(pref)) continue;
                UserPreference p = new UserPreference();
                p.setCategory(category);
                p.setPreference(pref);
                p.setConfidence(clamp(n.path("confidence").asDouble(0.7)));
                p.setSourceType("explicit".equals(n.path("source").asText("inferred")) ? "explicit" : "inferred");
                p.setSourceId(sourceId);
                userModelService.savePreference(userId, companionId, p);
            }

            for (JsonNode n : root.path("hypotheses")) {
                String hypothesis = n.path("hypothesis").asText("");
                if (!StringUtils.hasText(hypothesis)) continue;
                UserHypothesis h = new UserHypothesis();
                h.setHypothesis(hypothesis);
                h.setDescription(n.path("description").asText(hypothesis));
                h.setConfidence(clamp(n.path("confidence").asDouble(0.5)));
                List<Object> evidence = new ArrayList<>();
                for (JsonNode e : n.path("evidence")) evidence.add(e.asText());
                h.setEvidence(evidence);
                userModelService.saveHypothesis(userId, companionId, h);
            }

            if (root.path("corrections").size() > 0) {
                userModelService.correct(userId, companionId);
            }
        } catch (Exception e) {
            log.warn("用户理解抽取失败: {}", e.getMessage());
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
