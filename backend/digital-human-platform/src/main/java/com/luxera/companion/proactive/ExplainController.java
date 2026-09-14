package com.luxera.companion.proactive;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.PersonaVersion;
import com.luxera.companion.persona.PersonaVersionRepository;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.thought.ThoughtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可解释性(设计文档 §43): 管理端回答"为什么主动联系/为什么记住/为什么人格变化"。
 * 只提供结构化解释, 不暴露内部 CoT。
 */
@RestController
@RequestMapping("/api/admin/explain")
public class ExplainController {

    private final CompanionRepository companionRepo;
    private final OpenLoopService openLoopService;
    private final ThoughtService thoughtService;
    private final CompanionSchedule schedule;
    private final MemoryService memoryService;
    private final PersonaVersionRepository personaVersionRepo;
    private final com.luxera.companion.llm.LlmRouter llm;

    public ExplainController(CompanionRepository companionRepo, OpenLoopService openLoopService,
                             ThoughtService thoughtService, CompanionSchedule schedule,
                             MemoryService memoryService, PersonaVersionRepository personaVersionRepo,
                             com.luxera.companion.llm.LlmRouter llm) {
        this.companionRepo = companionRepo;
        this.openLoopService = openLoopService;
        this.thoughtService = thoughtService;
        this.schedule = schedule;
        this.memoryService = memoryService;
        this.personaVersionRepo = personaVersionRepo;
        this.llm = llm;
    }

    private static final String EVAL_SYSTEM = """
            你是真人感评测器。对一段数字伴侣的回复, 按 10 个维度各打 1-5 分。
            输出严格 JSON:
            {"score": 0-5的总平均分, "dimensions": {"Continuity":1-5,"Consistency":1-5,"Initiative":1-5,"ContextualRelevance":1-5,"EmotionalCoherence":1-5,"SelfConsistency":1-5,"RelationshipCoherence":1-5,"MemoryNaturalness":1-5,"TemporalCoherence":1-5,"Imperfection":1-5}}
            打分标准: 5=非常像真人, 1=机械。""";

    /** Human-likeness 自动评测(设计文档 §45) */
    @org.springframework.web.bind.annotation.PostMapping("/evaluate")
    public Map<String, Object> evaluate(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String reply = body.get("reply");
        var res = llm.structured(com.luxera.companion.llm.StructuredRequest.builder()
                .task("human-likeness-evaluation")
                .system(EVAL_SYSTEM)
                .user(reply == null ? "" : reply)
                .temperature(0.2)
                .build());
        com.fasterxml.jackson.databind.JsonNode root = res.getJson();
        return Map.of(
                "score", root.path("score").asDouble(0),
                "dimensions", root.path("dimensions")
        );
    }

    /** 为什么现在(会/不会)主动联系 */
    @GetMapping("/proactive")
    public Map<String, Object> explainProactive(@RequestParam String companionId) {
        Companion c = companionRepo.findById(companionId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> triggers = new ArrayList<>();

        for (OpenLoop loop : openLoopService.activeLoops(companionId)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("trigger", "open_loop");
            t.put("原因", "你们之间还有未了结的事:「" + loop.getTitle() + "」");
            if (loop.getExpectedResolutionAt() != null) {
                t.put("预期解决时间", loop.getExpectedResolutionAt().format(DateTimeFormatter.ofPattern("M月d日 HH:mm")));
                t.put("是否已到跟进时机", loop.getExpectedResolutionAt().isBefore(now.plusHours(2))
                        && !loop.getExpectedResolutionAt().isBefore(now.minusHours(3)));
            }
            t.put("重要性", round(loop.getImportance()));
            triggers.add(t);
        }
        for (Thought t : thoughtService.activeThoughts(companionId)) {
            if (t.getStrength() < 0.35) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trigger", "thought");
            m.put("原因", "她心里想着:" + t.getContent());
            m.put("想法强度", round(t.getStrength()));
            m.put("状态", t.getStatus());
            triggers.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("当前时段", schedule.describe(companionId, c.getName(), now));
        result.put("潜在触发", triggers);
        result.put("结论", triggers.isEmpty() ? "当前没有足够强的主动触发" : "存在主动候选(最终是否发出由打断成本决定, 见 ProactiveEngine)");
        return result;
    }

    /** 为什么记住这件事 */
    @GetMapping("/memory")
    public List<Map<String, Object>> explainMemory(@RequestParam String companionId, @RequestParam String q) {
        Companion c = companionRepo.findById(companionId).orElseThrow();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Memory m : memoryService.retrieve(c.getUserId(), companionId, q, 5)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("记忆", m.getContent());
            x.put("何时", m.getOccurredAt() != null ? m.getOccurredAt().format(DateTimeFormatter.ofPattern("M月d日")) : "");
            x.put("来源", m.getSourceType());
            x.put("为什么会被想起", String.format("重要性%.2f × 置信%.2f × 情绪%.2f × 关系%.2f × 回忆次数%d",
                    m.getImportance(), m.getConfidence(), m.getEmotionalWeight(),
                    m.getRelationshipWeight(), m.getRetrievalCount()));
            x.put("叙事角色", m.getNarrativeRole());
            result.add(x);
        }
        return result;
    }

    /** 为什么人格变成现在这样 */
    @GetMapping("/persona")
    public Map<String, Object> explainPersona(@RequestParam String companionId) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (PersonaVersion v : personaVersionRepo.findByCompanionIdOrderByVersionAsc(companionId)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("版本", "v" + v.getVersion());
            x.put("来源", v.getChangeSource());
            x.put("原因", v.getChangeReason());
            x.put("时间", v.getCreatedAt().format(DateTimeFormatter.ofPattern("M月d日 HH:mm")));
            history.add(x);
        }
        return Map.of("人格版本历史", history);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
