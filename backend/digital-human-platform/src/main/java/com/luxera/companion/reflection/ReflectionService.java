package com.luxera.companion.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.usermodel.UserModelService;
import com.luxera.companion.usermodel.UserPattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 反思引擎(设计文档 48-51 节): 异步、不参与实时聊天。
 * 每日/每周用 LLM 对对话做深度分析,产出记忆候选 / 用户模型候选 / 关系候选。
 */
@Slf4j
@Service
public class ReflectionService {

    private static final String DAILY_SYSTEM = """
            你是反思引擎,回顾用户与数字伴侣今天的对话,产出多维度洞察(设计文档 §39)。
            输出严格 JSON,不要输出其他内容:
            {
              "summary": "今天相处的总结(2-3句)",
              "insights": ["对今天关系的洞察,2-4条"],
              "memory_candidates": [{"content":"值得长期记住的事","type":"episodic|semantic|shared","importance":0-1}],
              "user_insights": ["对用户的新认识,1-3条"],
              "relationship_candidates": ["关系变化的观察,1-3条"],
              "self_insights": ["她(伴侣)自己今天的状态/变化,1-2条"],
              "life_patterns": ["用户今天的生活/作息模式,1-2条"],
              "memory_insights": ["今天哪些记忆被强化/值得重新唤起,1-2条"],
              "thought_insights": ["她今天可能在想什么,1-2条"]
            }
            只基于提供的对话,不要编造。""";

    private static final String WEEKLY_SYSTEM = """
            你是反思引擎,回顾用户与数字伴侣过去一周的对话,形成长期用户理解。
            输出严格 JSON,不要输出其他内容:
            {
              "summary": "这一周的相处总结(2-3句)",
              "long_term_user_understanding": ["对用户稳定的长期认识,3-6条,必须是这周对话能支撑的"],
              "behavioral_patterns": [{"pattern":"user_often_works_late","description":"行为模式描述","confidence":0-1}],
              "relationship_changes": ["关系在这周的变化,1-3条"],
              "self_evolution": ["她(伴侣)自己这一周的变化,1-2条"]
            }
            只基于提供的对话,不要编造。""";

    private final CompanionRepository companionRepo;
    private final ChatWorldPort chatWorld;
    private final MemoryService memoryService;
    private final UserModelService userModelService;
    private final ReflectionRecordRepository recordRepo;
    private final com.luxera.companion.selfmodel.SelfModelExtractor selfModelExtractor;
    private final com.luxera.companion.selfmodel.SelfModelService selfModelService;
    private final com.luxera.companion.thought.ThoughtEngine thoughtEngine;
    private final com.luxera.companion.agent.ContextLoader contextLoader;
    private final com.luxera.companion.relationship.RelationshipService relationshipService;
    private final com.luxera.companion.relationship.RelationshipNarrativeService narrativeService;
    private final LlmRouter llm;

    public ReflectionService(CompanionRepository companionRepo, ChatWorldPort chatWorld,
                             MemoryService memoryService, UserModelService userModelService,
                             ReflectionRecordRepository recordRepo,
                             com.luxera.companion.selfmodel.SelfModelExtractor selfModelExtractor,
                             com.luxera.companion.selfmodel.SelfModelService selfModelService,
                             com.luxera.companion.thought.ThoughtEngine thoughtEngine,
                             com.luxera.companion.agent.ContextLoader contextLoader,
                             com.luxera.companion.relationship.RelationshipService relationshipService,
                             com.luxera.companion.relationship.RelationshipNarrativeService narrativeService,
                             LlmRouter llm) {
        this.companionRepo = companionRepo;
        this.chatWorld = chatWorld;
        this.memoryService = memoryService;
        this.userModelService = userModelService;
        this.recordRepo = recordRepo;
        this.selfModelExtractor = selfModelExtractor;
        this.selfModelService = selfModelService;
        this.thoughtEngine = thoughtEngine;
        this.contextLoader = contextLoader;
        this.relationshipService = relationshipService;
        this.narrativeService = narrativeService;
        this.llm = llm;
    }

    @Transactional
    public List<ReflectionRecord> runAllDaily() {
        List<ReflectionRecord> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                results.add(dailyReflect(c));
            } catch (Exception e) {
                log.warn("每日反思失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        return results;
    }

    @Transactional
    public List<ReflectionRecord> runAllWeekly() {
        List<ReflectionRecord> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                results.add(weeklyReflect(c));
            } catch (Exception e) {
                log.warn("每周反思失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        return results;
    }

    // ── 每日 ─────────────────────────────────
    @Transactional
    public ReflectionRecord dailyReflect(Companion c) {
        LocalDate today = LocalDate.now();
        String period = today.toString();
        String userId = c.getUserId();

        // 确定性规则: 深夜活跃模式(不依赖 LLM,稳定)
        List<MessageView> weekUser = chatWorld.userMessagesSince(c.getId(), LocalDateTime.now().minusDays(7));
        long lateCount = weekUser.stream().filter(m -> {
            int h = m.getCreatedAt().getHour();
            return h >= 23 || h < 2;
        }).count();
        if (lateCount >= 3) {
            UserPattern p = new UserPattern();
            p.setPattern("user_often_works_late");
            p.setDescription("最近经常深夜(23点后)还在忙");
            p.setConfidence(0.72);
            p.setEvidenceCount((int) lateCount);
            p.setEvidence(List.of("最近7天有 " + lateCount + " 条深夜消息"));
            userModelService.savePattern(userId, c.getId(), p);
        }

        List<MessageView> dayMessages = chatWorld.messagesBetween(c.getId(), today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        ReflectionRecord rec = new ReflectionRecord();
        rec.setUserId(userId);
        rec.setCompanionId(c.getId());
        rec.setType("daily");
        rec.setPeriod(period);

        if (dayMessages.isEmpty()) {
            rec.setSummary("今天没有聊天。");
            rec.setInsights(new ArrayList<>());
            return recordRepo.save(rec);
        }

        String excerpt = dayMessages.stream()
                .map(m -> ("user".equals(m.getSenderType()) ? "用户" : "伴侣") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (excerpt.length() > 3000) excerpt = excerpt.substring(0, 3000);

        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("daily-reflection").system(DAILY_SYSTEM).user(excerpt).temperature(0.3).build());
            JsonNode root = res.getJson();
            rec.setSummary(root.path("summary").asText("今天有交流,但没能自动总结。"));
            rec.setInsights(texts(root.path("insights")));
            rec.setUserModelCandidates(texts(root.path("user_insights")));
            rec.setRelationshipCandidates(texts(root.path("relationship_candidates")));
            // §39 多维度: 自我洞察 / 生活模式 / 记忆洞察 / 想法洞察
            rec.setSelfInsights(texts(root.path("self_insights")));
            rec.setLifePatterns(texts(root.path("life_patterns")));
            List<Object> allInsights = new ArrayList<>(rec.getInsights());
            for (Object s : texts(root.path("memory_insights"))) allInsights.add("记忆: " + s);
            for (Object t : texts(root.path("thought_insights"))) allInsights.add("想法: " + t);
            rec.setInsights(allInsights);

            // 记忆候选入库
            List<Memory> candidates = new ArrayList<>();
            for (JsonNode n : root.path("memory_candidates")) {
                Memory m = new Memory();
                m.setType(n.path("type").asText("episodic"));
                m.setContent(n.path("content").asText(""));
                m.setImportance(clamp(n.path("importance").asDouble(0.5)));
                m.setOccurredAt(LocalDateTime.now());
                if (!m.getContent().isBlank()) candidates.add(m);
            }
            if (!candidates.isEmpty()) {
                memoryService.saveBatch(userId, c.getId(), "reflection", "daily-" + period, candidates);
            }
            rec.setMemoryCandidates(new ArrayList<>(candidates.stream().map(Memory::getContent).toList()));
        } catch (Exception e) {
            log.warn("每日反思 LLM 分析失败,使用兜底摘要: {}", e.getMessage());
            rec.setSummary("今天聊了 " + dayMessages.size() + " 条消息。");
        }
        ReflectionRecord saved = recordRepo.save(rec);
        // §8: 反思后用 LLM 补抽未了结事项
        try {
            thoughtEngine.extractOpenLoopsFromDay(c.getId(), excerpt);
        } catch (Exception e) {
            log.debug("OpenLoop 抽取失败: {}", e.getMessage());
        }
        // Phase 2 + §29: 反思后同步自我模型(用 LearningContext)
        try {
            java.util.List<String> expSummary = java.util.List.of(excerpt);
            com.luxera.companion.agent.LearningContext learning = contextLoader.loadLearning(userId, c.getId(), expSummary);
            selfModelExtractor.extractFromContext(c.getId(), learning.toLearningText());
        } catch (Exception e) {
            log.debug("自我模型同步失败: {}", e.getMessage());
        }
        return saved;
    }

    // ── 每周 ─────────────────────────────────
    @Transactional
    public ReflectionRecord weeklyReflect(Companion c) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);
        String period = weekStart + "_" + today;
        String userId = c.getUserId();

        List<MessageView> weekMessages = chatWorld.messagesBetween(c.getId(), weekStart.atStartOfDay(), today.plusDays(1).atStartOfDay());
        ReflectionRecord rec = new ReflectionRecord();
        rec.setUserId(userId);
        rec.setCompanionId(c.getId());
        rec.setType("weekly");
        rec.setPeriod(period);

        if (weekMessages.isEmpty()) {
            rec.setSummary("这一周没有聊天。");
            return recordRepo.save(rec);
        }

        String excerpt = weekMessages.stream()
                .map(m -> ("user".equals(m.getSenderType()) ? "用户" : "伴侣") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (excerpt.length() > 5000) excerpt = excerpt.substring(0, 5000);

        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("weekly-reflection").system(WEEKLY_SYSTEM).user(excerpt).temperature(0.3).build());
            JsonNode root = res.getJson();
            rec.setSummary(root.path("summary").asText("这一周有交流。"));
            rec.setInsights(texts(root.path("long_term_user_understanding")));

            // 行为模式入库
            for (JsonNode n : root.path("behavioral_patterns")) {
                UserPattern p = new UserPattern();
                p.setPattern(n.path("pattern").asText("user_behavior"));
                p.setDescription(n.path("description").asText(p.getPattern()));
                p.setConfidence(clamp(n.path("confidence").asDouble(0.6)));
                p.setEvidence(List.of("每周反思 " + period));
                userModelService.savePattern(userId, c.getId(), p);
            }
            rec.setRelationshipCandidates(texts(root.path("relationship_changes")));
            // §35/§39: 她自己的变化 → 归入自我模式(Pattern 归纳)
            List<Object> selfEvolution = texts(root.path("self_evolution"));
            rec.setSelfInsights(selfEvolution);
            if (!selfEvolution.isEmpty()) {
                List<String> patterns = selfEvolution.stream().map(String::valueOf).toList();
                selfModelService.update(c.getId(),
                        new com.luxera.companion.selfmodel.SelfModelService.SelfModelUpdate(
                                null, null, patterns, null, null, null, null, null),
                        "每周反思: 自我模式归纳");
            }
        } catch (Exception e) {
            log.warn("每周反思 LLM 分析失败,使用兜底摘要: {}", e.getMessage());
            rec.setSummary("这一周聊了 " + weekMessages.size() + " 条消息。");
        }
        ReflectionRecord saved = recordRepo.save(rec);
        // Phase 3: 每周反思后更新关系叙事("我们之间发生过一个故事")
        try {
            var rel = relationshipService.find(userId, c.getId());
            if (rel != null) {
                StringBuilder ctx = new StringBuilder("这一周的关系变化: ");
                if (rec.getRelationshipCandidates() != null) ctx.append(rec.getRelationshipCandidates());
                ctx.append("\n这段对话: ").append(excerpt);
                narrativeService.generateNarrative(rel.getId(), ctx.toString());
            }
        } catch (Exception e) {
            log.debug("关系叙事同步失败: {}", e.getMessage());
        }
        return saved;
    }

    // ── 工具 ─────────────────────────────────
    private static List<Object> texts(JsonNode arr) {
        List<Object> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asText("");
            if (!s.isBlank()) list.add(s);
        }
        return list;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
