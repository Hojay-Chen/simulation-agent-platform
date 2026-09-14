package com.luxera.companion.relationship;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 关系叙事(设计文档 §38/§42): "我们之间发生过一个故事", 版本化。
 * 每周反思/里程碑时由 LLM 生成, 记录 current_summary/important_chapters/emotional_arc/shared_identity。
 */
@Slf4j
@Service
public class RelationshipNarrativeService {

    private static final String SYSTEM = """
            你是关系叙事引擎。基于你们的关系里程碑、共同经历与当前关系, 写一段"我们之间发生过一个故事"。
            输出严格 JSON, 不要输出其他内容:
            {
              "current_summary": "3-5句, 叙述你们从认识到现在的经历与情感变化, 第一人称",
              "important_chapters": [{"chapter":"章节名","content":"这段经历"}],
              "emotional_arc": ["情绪变化轨迹, 3-5个节点, 如'初识的好奇'"],
              "shared_identity": "你们共同认同/共同拥有的东西"
            }
            只基于提供的内容, 不要编造。""";

    private final RelationshipNarrativeRepository repo;
    private final LlmRouter llm;

    public RelationshipNarrativeService(RelationshipNarrativeRepository repo, LlmRouter llm) {
        this.repo = repo;
        this.llm = llm;
    }

    @Transactional
    public RelationshipNarrative getOrCreate(String relationshipId) {
        return repo.findByRelationshipId(relationshipId).orElseGet(() -> {
            RelationshipNarrative n = new RelationshipNarrative();
            n.setRelationshipId(relationshipId);
            n.setCurrentSummary("我们还在互相了解。");
            return repo.save(n);
        });
    }

    /** 用 LLM 生成/更新关系叙事(每周反思后) */
    @Transactional
    public RelationshipNarrative generateNarrative(String relationshipId, String context) {
        RelationshipNarrative n = getOrCreate(relationshipId);
        if (!StringUtils.hasText(context)) return n;
        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("relationship-narrative")
                    .system(SYSTEM)
                    .user(context.length() > 3000 ? context.substring(0, 3000) : context)
                    .temperature(0.4)
                    .build());
            JsonNode root = res.getJson();
            n.setCurrentSummary(root.path("current_summary").asText(n.getCurrentSummary()));
            List<Object> chapters = new ArrayList<>();
            for (JsonNode c : root.path("important_chapters")) {
                chapters.add(c.isObject() ? c.toString() : c.asText());
            }
            n.setImportantChapters(chapters);
            n.setEmotionalArc(texts(root.path("emotional_arc")));
            n.setSharedIdentity(root.path("shared_identity").asText(n.getSharedIdentity()));
            n.setVersion(n.getVersion() + 1);
            n.setChangeReason("每周反思更新关系叙事");
            return repo.save(n);
        } catch (Exception e) {
            log.debug("关系叙事生成失败: {}", e.getMessage());
            return n;
        }
    }

    @Transactional(readOnly = true)
    public RelationshipNarrative get(String relationshipId) {
        return repo.findByRelationshipId(relationshipId).orElse(null);
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
