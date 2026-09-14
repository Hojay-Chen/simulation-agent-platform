package com.luxera.companion.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 感知精炼(质量优先): 在回复生成前同步用大模型精炼意图/情绪/话题/实体,
 * 失败时回退到启发式结果。用户可接受延迟,因此感知精度不再异步补。
 * (设计文档 39 节;app.agent.intent-extraction=llm 时启用)
 */
@Slf4j
@Component
public class PerceptionRefiner {

    private static final String SYSTEM = """
            你是对话感知引擎,分析用户这条消息的状态。输出严格 JSON,不要输出其他内容:
            {
              "intent": "greeting|farewell|question|share_upset|share_joy|share_tired|request_tool|correction|planning|gratitude|ask_about_her|chat",
              "emotion": "tired|sad|anxious|angry|happy|lonely|grateful|neutral",
              "topic": "work|study|health|relationship|entertainment|food|travel|weather|daily",
              "entities": ["提到的关键对象,如咖啡/具体项目/人名/地点,最多3个"]
            }
            依据对话语境判断,不要只看关键词。""";

    private final LlmRouter llm;
    private final ChatWorldPort chatWorld;
    private final WorkingMemory workingMemory;
    private final AppProperties props;

    public PerceptionRefiner(LlmRouter llm, ChatWorldPort chatWorld, WorkingMemory workingMemory,
                             AppProperties props) {
        this.llm = llm;
        this.chatWorld = chatWorld;
        this.workingMemory = workingMemory;
        this.props = props;
    }

    /**
     * 同步精炼感知(回复生成前调用)。返回精炼后的 Perception(失败回退启发式),
     * 并更新工作记忆与消息元数据。
     */
    public PerceptionEngine.Perception refineNow(String userMessageId, String companionId, String conversationId,
                                                 String userText, PerceptionEngine.Perception heuristic) {
        WorkingMemory.RecentLine userLine = new WorkingMemory.RecentLine("user", userText, LocalDateTime.now());
        // 先记启发式结果,保证即使 LLM 失败工作记忆也是新鲜的
        workingMemory.record(companionId, conversationId, userLine, heuristic);

        if (!"llm".equals(props.getAgent().getIntentExtraction())) {
            return heuristic;
        }
        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("perception")
                    .system(SYSTEM)
                    .user(userText)
                    .temperature(0.2)
                    .build());
            JsonNode root = res.getJson();

            String refinedIntent = root.path("intent").asText("");
            String refinedEmotion = root.path("emotion").asText("");
            String refinedTopic = root.path("topic").asText("");
            List<String> entities = new ArrayList<>();
            for (JsonNode e : root.path("entities")) {
                String s = e.asText("");
                if (!s.isBlank()) entities.add(s);
            }

            PerceptionEngine.Perception refined = new PerceptionEngine.Perception(
                    refinedIntent.isBlank() ? heuristic.intent() : refinedIntent,
                    refinedEmotion.isBlank() ? heuristic.emotion() : refinedEmotion,
                    refinedTopic.isBlank() ? heuristic.topic() : refinedTopic);

            // 更新用户消息元数据(供关系/反思/追溯用)
            if (userMessageId != null) {
                chatWorld.updatePerception(userMessageId, refinedIntent, refinedEmotion, refinedTopic);
            }

            // 更新工作记忆(精炼结果 + 实体)
            workingMemory.record(companionId, conversationId, userLine, refined);
            if (!entities.isEmpty()) {
                workingMemory.setEntities(companionId, conversationId, entities);
            }
            return refined;
        } catch (Exception e) {
            log.debug("LLM 感知精炼失败,使用启发式结果: {}", e.getMessage());
            return heuristic;
        }
    }
}
