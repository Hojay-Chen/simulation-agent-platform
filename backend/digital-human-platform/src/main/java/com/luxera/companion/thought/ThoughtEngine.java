package com.luxera.companion.thought;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 想法引擎(设计文档 §6): Trigger → Candidate → Scoring → Active → Decision。
 * 大部分内部事件最终什么都不发生 —— 这正是为了模拟真人。
 */
@Slf4j
@Component
public class ThoughtEngine {

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "(?:明天|后天|下周|今晚|过几天)[^。！？!?]{0,10}(?:面试|考试|开会|体检|复查|交(?:稿|方案|差)|见(?:客户|面)|搬(?:家|office)|出(?:差|国))"
                    + "|等(?:消息|结果|通知)"
                    + "|(?:面试|考试)(?:结果|出来|怎么样)",
            Pattern.CASE_INSENSITIVE);

    private static final String OPEN_LOOP_SYSTEM = """
            你是未完成事项抽取器。从一段对话中找出用户提到的"还没办完/在等结果/未来要做"的事。
            输出严格 JSON, 不要输出其他内容:
            {"open_loops":[{"title":"事项标题(简洁)","description":"补充说明","expected_resolution_at":"yyyy-MM-ddTHH:mm 或空"}]}
            只输出真正未了结的, 不要输出闲聊。没有就空数组。""";

    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;
    private final LlmRouter llm;

    public ThoughtEngine(ThoughtService thoughtService, OpenLoopService openLoopService, LlmRouter llm) {
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
        this.llm = llm;
    }

    /** 每日反思时用 LLM 抽取未办完的事(设计文档 §8, Level 3) */
    @Transactional
    public void extractOpenLoopsFromDay(String companionId, String dayExcerpt) {
        if (dayExcerpt == null || dayExcerpt.isBlank()) return;
        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("open-loop-extraction")
                    .system(OPEN_LOOP_SYSTEM)
                    .user(dayExcerpt.length() > 2500 ? dayExcerpt.substring(0, 2500) : dayExcerpt)
                    .temperature(0.2)
                    .build());
            for (JsonNode n : res.getJson().path("open_loops")) {
                String title = n.path("title").asText("");
                if (title.isBlank()) continue;
                openLoopService.create(companionId, "USER_EVENT", title,
                        n.path("description").asText(null), 0.6, 0.5,
                        parseTime(n.path("expected_resolution_at").asText("")));
            }
        } catch (Exception e) {
            log.debug("OpenLoop LLM 抽取失败: {}", e.getMessage());
        }
    }

    /** 想法转 OpenLoop(设计文档 §6: Convert to OpenLoop 决策) */
    @Transactional
    public void convertToOpenLoop(String companionId, String thoughtId) {
        thoughtService.activeThoughts(companionId).stream()
                .filter(t -> t.getId().equals(thoughtId))
                .findFirst()
                .ifPresent(t -> {
                    openLoopService.create(companionId, "THOUGHT", t.getContent(),
                            null, t.getImportance(), t.getEmotionalWeight(), null);
                    thoughtService.act(thoughtId);
                });
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从一条用户消息触发候选想法(Level 2, 规则为主) */
    @Transactional
    public Thought maybeFromConversation(String companionId, String userText) {
        if (userText == null) return null;
        Matcher m = RESOLUTION_PATTERN.matcher(userText);
        if (m.find()) {
            // 用户提到有"待办/待结果"的事 → CURIOSITY + 转 OpenLoop
            String title = extractTitle(userText);
            java.time.LocalDateTime expected = expectedResolution(userText);
            OpenLoop loop = openLoopService.create(companionId, "USER_EVENT", title,
                    userText, 0.8, 0.7, expected);
            Thought t = thoughtService.create(companionId,
                    "不知道他" + title + "怎么样了",
                    "CURIOSITY", "OPEN_LOOP", loop != null ? loop.getId() : null,
                    0.85, 0.6, 0.85, 0.95, 0.8);
            return t;
        }
        return null;
    }

    /** 推断预期解决时间: "明天"→明晚18点, "后天"→后晚18点, 否则当晚会 */
    private static java.time.LocalDateTime expectedResolution(String text) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime base = java.time.LocalDateTime.now().plusHours(6);
        if (text.contains("明天")) return today.plusDays(1).atTime(18, 0);
        if (text.contains("后天")) return today.plusDays(2).atTime(18, 0);
        return base;
    }

    /** 从一条记忆关联触发想法(如"他之前好像挺喜欢这里") */
    @Transactional
    public Thought maybeAssociation(String companionId, String memoryContent) {
        if (memoryContent == null || memoryContent.isBlank()) return null;
        if (memoryContent.length() > 4 && memoryContent.length() < 40) {
            Thought t = thoughtService.create(companionId,
                    "想起:" + memoryContent, "ASSOCIATION", "MEMORY", null,
                    0.4, 0.4, 0.6, 0.5, 0.6);
            return t;
        }
        return null;
    }

    private static String extractTitle(String text) {
        for (String kw : new String[]{"面试", "考试", "会议", "开会", "体检", "复查", "见面", "旅行", "交稿", "交方案", "结果", "通知"}) {
            if (text.contains(kw)) return "他要去" + kw;
        }
        String trimmed = text.length() > 24 ? text.substring(0, 24) : text;
        return trimmed;
    }
}
