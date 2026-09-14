package com.luxera.companion.selfmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自我模型抽取(设计文档 §9 / Phase 2): 反思后从经历中提炼"她此刻觉得自己怎样"。
 * Persona 与 SelfModel 严格分离。
 */
@Slf4j
@Component
public class SelfModelExtractor {

    private static final String SYSTEM = """
            你是自我认知引擎。基于伴侣最近的经历与反思, 更新"她此刻觉得自己怎样"的自我模型(第一人称)。
            输出严格 JSON, 不要输出其他内容:
            {
              "facts": ["关于自己的事实, 2-4条"],
              "preferences": ["自己最近的偏好, 如'越来越喜欢晚上安静地待着'"],
              "patterns": ["自己最近的习惯/模式"],
              "beliefs": ["自己相信/在意什么"],
              "goals": ["自己近期的目标"],
              "concerns": ["自己近期的担忧"],
              "plans": ["自己近期的计划"],
              "narrative": "当前阶段的自我叙事, 2-3句第一人称, 如'最近工作有点忙。我发现自己越来越喜欢晚上安静地待着。'"
            }
            只输出有依据的, 不要编造; 没有就空数组。""";

    private final LlmRouter llm;
    private final SelfModelService selfModelService;

    public SelfModelExtractor(LlmRouter llm, SelfModelService selfModelService) {
        this.llm = llm;
        this.selfModelService = selfModelService;
    }

    /** 反思后调用(每日/每周) */
    public void extractFromContext(String companionId, String context) {
        if (context == null || context.isBlank()) return;
        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("self-model-extraction")
                    .system(SYSTEM)
                    .user(context.length() > 2500 ? context.substring(0, 2500) : context)
                    .temperature(0.3)
                    .build());
            JsonNode root = res.getJson();
            SelfModelService.SelfModelUpdate patch = new SelfModelService.SelfModelUpdate(
                    texts(root.path("facts")), texts(root.path("preferences")), texts(root.path("patterns")),
                    texts(root.path("beliefs")), texts(root.path("goals")), texts(root.path("concerns")),
                    texts(root.path("plans")), root.path("narrative").asText(""));
            selfModelService.update(companionId, patch, "反思更新自我模型");
        } catch (Exception e) {
            log.debug("自我模型抽取失败: {}", e.getMessage());
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asText("");
            if (!s.isBlank()) list.add(s);
        }
        return list;
    }
}
