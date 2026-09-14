package com.luxera.companion.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆抽取: 每轮对话后异步从交流中抽取 episodic/semantic/shared 记忆。
 * (设计文档 25-27 节)
 */
@Slf4j
@Component
public class MemoryExtractor {

    private static final String SYSTEM = """
            你是记忆抽取器,负责从一段对话中提取值得长期记住的记忆。
            只抽取对长期关系有意义的信息,忽略寒暄和客套。
            输出严格 JSON(不要输出任何其他内容):
            {
              "episodic": [{"content":"具体发生了什么","summary":"一句话摘要","importance":0-1,"emotional_weight":0-1,"occurred_at":"yyyy-MM-dd"}],
              "semantic": [{"content":"对用户的稳定长期认知(从多次交流总结)","summary":"","importance":0-1,"confidence":0-1}],
              "shared": [{"content":"双方共同经历或形成的默契/梗","summary":"","importance":0-1}]
            }
            episodic=具体发生过的事;semantic=对用户的长期认知;shared=你们共同经历。
            没有就返回空数组。content 要具体、口语化、可检索,用中文,不超过 60 字。""";

    private final LlmRouter llm;
    private final MemoryService memoryService;
    private final MemoryAssociationService associationService;
    private final PgVectorEmbeddingProvider vectorProvider;

    public MemoryExtractor(LlmRouter llm, MemoryService memoryService,
                           MemoryAssociationService associationService,
                           PgVectorEmbeddingProvider vectorProvider) {
        this.llm = llm;
        this.memoryService = memoryService;
        this.associationService = associationService;
        this.vectorProvider = vectorProvider;
    }

    @Async
    public void extractFromExchange(String userId, String companionId, String conversationId,
                                    String userText, String assistantText) {
        try {
            String excerpt = "对话:\n用户: " + userText + "\n伴侣: " + assistantText + "\n\n请提取值得长期记住的记忆。";
            var res = llm.structured(StructuredRequest.builder()
                    .task("memory-extraction")
                    .system(SYSTEM)
                    .user(excerpt)
                    .temperature(0.2)
                    .build());
            List<Memory> memories = new ArrayList<>();
            JsonNode root = res.getJson();
            for (String key : List.of("episodic", "semantic", "shared")) {
                for (JsonNode n : root.path(key)) {
                    Memory m = new Memory();
                    m.setType(key);
                    m.setContent(n.path("content").asText(""));
                    m.setSummary(n.path("summary").asText(null));
                    m.setImportance(clamp(n.path("importance").asDouble(0.5)));
                    m.setConfidence(clamp(n.path("confidence").asDouble(0.7)));
                    m.setEmotionalWeight(clamp(n.path("emotional_weight").asDouble(0.5)));
                    m.setOccurredAt(parseTime(n.path("occurred_at").asText(null)));
                    if (StringUtils.hasText(m.getContent())) {
                        memories.add(m);
                    }
                }
            }
            if (!memories.isEmpty()) {
                memoryService.saveBatch(userId, companionId, "conversation", conversationId, memories);
                // 为新记忆建立关联(记忆网络): 同批互链 + 与历史记忆互链
                associationService.linkBatch(memories);
                for (Memory m : memories) {
                    if (m.getId() != null) {
                        associationService.linkNewMemory(userId, companionId, m, 100);
                    }
                }
                // Phase 5: 若配置了 embedding key → 写向量(失败静默, 走回退)
                if (vectorProvider.available()) {
                    for (Memory m : memories) {
                        if (m.getId() != null && m.getContent() != null) {
                            vectorProvider.updateEmbedding(m.getId(), vectorProvider.embed(m.getContent()));
                        }
                    }
                }
                log.info("记忆抽取完成: {} 条 (conversation={})", memories.size(), conversationId);
            }
        } catch (Exception e) {
            log.warn("记忆抽取失败: {}", e.getMessage());
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static LocalDateTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s).atTime(LocalTime.NOON);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }
}
