package com.luxera.companion.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 实体抽取(P2 §五十四): 从对话中抽取用户提到的实体(人/公司/地点/餐厅/项目/电影/事件/话题)。
 * 解决"那家公司/上次那个地方/他"这类长期指代 —— 让她的上下文里有"他在乎的那些名词"。
 * LLM 结构化抽取, 失败静默(不阻塞主链路)。
 */
@Slf4j
@Component
public class MemoryEntityExtractor {

    private static final String SYSTEM = """
            你是实体抽取器。从对话中抽取用户提到的"他在乎/常提"的具体实体。
            只抽具体的名词(专名或明确话题), 忽略寒暄、代词、虚词。
            输出严格 JSON(不要输出任何其他内容):
            {
              "entities": [
                {"name":"公司名/人名/地点/项目名…","type":"PERSON|COMPANY|PLACE|RESTAURANT|PROJECT|MOVIE|EVENT|TOPIC","description":"一句话说明这是什么(供以后引用)"}
              ]
            }
            规则:
            - 用户说的"那家公司""上次那个地方"这类指代 → 如果对话上下文能确定是哪个实体, 抽取为那个实体名。
            - 称呼(老板/同事/朋友)若无法确定是谁, 不抽或 type=TOPIC 加 context。
            - 每轮最多抽 5 个。没有就返回空数组。description 用中文, 不超过 40 字。""";

    private final LlmRouter llm;
    private final MemoryEntityService entityService;

    public MemoryEntityExtractor(LlmRouter llm, MemoryEntityService entityService) {
        this.llm = llm;
        this.entityService = entityService;
    }

    @Async
    public void extractFromExchange(String userId, String companionId, String conversationId,
                                    String userText, String assistantText) {
        try {
            if (userText == null || userText.isBlank()) return;
            String excerpt = "对话:\n用户: " + userText + "\n伴侣: " + assistantText + "\n\n抽取用户提到的实体。";
            var res = llm.structured(StructuredRequest.builder()
                    .task("entity-extraction")
                    .system(SYSTEM)
                    .user(excerpt)
                    .temperature(0.1)
                    .build());
            JsonNode root = res.getJson();
            int count = 0;
            for (JsonNode n : root.path("entities")) {
                String name = n.path("name").asText("");
                if (!StringUtils.hasText(name)) continue;
                String type = n.path("type").asText("TOPIC");
                String desc = n.path("description").asText("");
                String context = userText.length() > 120 ? userText.substring(0, 120) : userText;
                entityService.mention(userId, companionId, name, type, desc, context);
                count++;
            }
            if (count > 0) {
                log.info("实体抽取完成: {} 个 (conversation={})", count, conversationId);
            }
        } catch (Exception e) {
            log.debug("实体抽取失败(静默): {}", e.getMessage());
        }
    }
}
