package com.luxera.companion.digitalhuman.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * V9 §20 Session Rolling Summary Service:
 * 会话消息达到阈值(40)后, 把早期消息压缩为分节摘要(facts/unresolved/relationship/plans),
 * 每新增 20 条重新摘要一次。近期原文保留, 远期摘要化 —— 长会话不丢失早期事实。
 *
 * Async 路径: 由 AgentPostProcessor 异步触发, 不阻塞主链路。
 */
@Slf4j
@Service
public class SessionSummaryService {

    /** 触发摘要的消息阈值 */
    private static final int TRIGGER_THRESHOLD = 40;
    /** 重新摘要的增量步长 */
    private static final int STEP = 20;
    /** 保留原文的最近消息数 */
    private static final int KEEP_RECENT = 20;
    /** 单次参与摘要的消息上限(防超长上下文) */
    private static final int MAX_SUMMARIZE = 100;

    private final SessionSummaryRepository repo;
    private final ChatWorldPort chatWorld;
    private final LlmRouter llm;

    public SessionSummaryService(SessionSummaryRepository repo, ChatWorldPort chatWorld,
                                 LlmRouter llm) {
        this.repo = repo;
        this.chatWorld = chatWorld;
        this.llm = llm;
    }

    /** 检查并生成/刷新摘要(幂等: 每 STEP 条新消息一次) */
    @Transactional
    public boolean maybeSummarize(String conversationId) {
        try {
            List<MessageView> all = chatWorld.messages(conversationId);
            int total = all.size();
            if (total < TRIGGER_THRESHOLD) return false;
            SessionSummary existing = repo.findByConversationId(conversationId).orElse(null);
            int lastCount = existing == null ? 0 : existing.getMessageCountAtSummary();
            if (total - lastCount < STEP) return false;

            // 保留最近原文, 早期消息参与摘要(限制条数)
            List<MessageView> toSummarize = new ArrayList<>(all.subList(0, Math.max(0, total - KEEP_RECENT)));
            if (toSummarize.size() > MAX_SUMMARIZE) {
                toSummarize = new ArrayList<>(toSummarize.subList(toSummarize.size() - MAX_SUMMARIZE, toSummarize.size()));
            }
            SummaryParts parts = summarize(conversationId, toSummarize);

            if (existing == null) {
                SessionSummary s = new SessionSummary();
                s.setConversationId(conversationId);
                applyParts(s, parts);
                s.setMessageCountAtSummary(total);
                repo.save(s);
            } else {
                applyParts(existing, parts);
                existing.setMessageCountAtSummary(total);
                existing.setVersion(existing.getVersion() + 1);
                repo.save(existing);
            }
            return true;
        } catch (Exception e) {
            log.debug("[SessionSummary] 生成失败: {}", e.getMessage());
            return false;
        }
    }

    private void applyParts(SessionSummary s, SummaryParts parts) {
        s.setFactsText(parts.facts());
        s.setUnresolvedText(parts.unresolved());
        s.setRelationshipText(parts.relationship());
        s.setPlansText(parts.plans());
        StringBuilder sb = new StringBuilder();
        if (parts.facts() != null && !parts.facts().isBlank()) sb.append("你了解到关于他的事: ").append(parts.facts()).append("\n");
        if (parts.unresolved() != null && !parts.unresolved().isBlank()) sb.append("没说完/待办的事: ").append(parts.unresolved()).append("\n");
        if (parts.relationship() != null && !parts.relationship().isBlank()) sb.append("你们关系的变化: ").append(parts.relationship()).append("\n");
        if (parts.plans() != null && !parts.plans().isBlank()) sb.append("约定过的事: ").append(parts.plans()).append("\n");
        s.setSummaryText(sb.toString().trim());
    }

    /** LLM 生成分节摘要; mock/失败回退规则抽取 */
    private SummaryParts summarize(String conversationId, List<MessageView> msgs) {
        StringBuilder transcript = new StringBuilder();
        for (MessageView m : msgs) {
            String role = m.isFromUser() ? "他" : "我";
            transcript.append(role).append(": ").append(m.getContent()).append("\n");
        }
        try {
            if (llm.available() && !llm.isMockActive()) {
                String system = "你是长期陪伴的见证者, 把一段早期聊天压缩为分节摘要。"
                        + "只保留确定的事实, 不要臆测。输出 JSON: "
                        + "{\"facts\":\"关于对方的事实\",\"unresolved\":\"没说完/待办的事\","
                        + "\"relationship\":\"关系的变化\",\"plans\":\"约定/计划过的事\"}。"
                        + "没有的节留空字符串。";
                var result = llm.structured(StructuredRequest.builder()
                        .system(system)
                        .user("聊天记录:\n" + transcript)
                        .task("session-summary")
                        .schemaHint("{\"facts\":\"\",\"unresolved\":\"\",\"relationship\":\"\",\"plans\":\"\"}")
                        .build());
                JsonNode n = new ObjectMapper().readTree(result.getRaw());
                return new SummaryParts(
                        n.path("facts").asText(""), n.path("unresolved").asText(""),
                        n.path("relationship").asText(""), n.path("plans").asText(""));
            }
        } catch (Exception e) {
            log.debug("[SessionSummary] LLM 摘要失败,回退规则: {}", e.getMessage());
        }
        return fallback(msgs);
    }

    /** 规则回退: 事实=用户消息去重前 N 条; 计划=含"下次/明天/周末/答应"的句子 */
    private SummaryParts fallback(List<MessageView> msgs) {
        StringBuilder facts = new StringBuilder();
        StringBuilder plans = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (MessageView m : msgs) {
            if (!m.isFromUser()) continue;
            String c = m.getContent() == null ? "" : m.getContent().trim();
            if (c.isEmpty() || c.length() > 60) continue;
            if (seen.add(c) && facts.length() < 200) {
                facts.append(c).append("; ");
            }
            if (c.contains("下次") || c.contains("明天") || c.contains("周末")
                    || c.contains("答应") || c.contains("约好")) {
                if (plans.length() < 150) plans.append(c).append("; ");
            }
        }
        return new SummaryParts(facts.toString(), "", "", plans.toString());
    }

    public record SummaryParts(String facts, String unresolved, String relationship, String plans) {
    }
}
