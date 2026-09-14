package com.luxera.companion.proactive;

import com.luxera.companion.experience.ExperienceProcessor;
import com.luxera.companion.life.LifeRuntime;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.PersonaEvolutionService;
import com.luxera.companion.reflection.ReflectionService;
import com.luxera.companion.thought.ThoughtEngine;
import com.luxera.companion.thought.ThoughtMaintenanceJob;
import com.luxera.companion.tool.BirthdayService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 手动触发验收用的管理端点(设计文档 §30) */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ReflectionService reflectionService;
    private final ProactiveEngine proactiveEngine;
    private final BirthdayService birthdayService;
    private final PersonaEvolutionService personaEvolutionService;
    private final LifeRuntime lifeRuntime;
    private final CompanionRepository companionRepo;
    private final ExperienceProcessor experienceProcessor;
    private final ThoughtMaintenanceJob thoughtMaintenanceJob;
    private final ThoughtEngine thoughtEngine;
    private final OpenLoopService openLoopService;
    private final com.luxera.companion.agent.CompanionCognitiveRuntime cognitiveRuntime;
    private final com.luxera.companion.behavior.BehaviorEngine behaviorEngine;
    private final com.luxera.companion.plan.PlanService planService;

    public AdminController(ReflectionService reflectionService, ProactiveEngine proactiveEngine,
                           BirthdayService birthdayService, PersonaEvolutionService personaEvolutionService,
                           LifeRuntime lifeRuntime, CompanionRepository companionRepo,
                           ExperienceProcessor experienceProcessor, ThoughtMaintenanceJob thoughtMaintenanceJob,
                           ThoughtEngine thoughtEngine, OpenLoopService openLoopService,
                           com.luxera.companion.agent.CompanionCognitiveRuntime cognitiveRuntime,
                           com.luxera.companion.behavior.BehaviorEngine behaviorEngine,
                           com.luxera.companion.plan.PlanService planService) {
        this.reflectionService = reflectionService;
        this.proactiveEngine = proactiveEngine;
        this.birthdayService = birthdayService;
        this.personaEvolutionService = personaEvolutionService;
        this.lifeRuntime = lifeRuntime;
        this.companionRepo = companionRepo;
        this.experienceProcessor = experienceProcessor;
        this.thoughtMaintenanceJob = thoughtMaintenanceJob;
        this.thoughtEngine = thoughtEngine;
        this.openLoopService = openLoopService;
        this.cognitiveRuntime = cognitiveRuntime;
        this.behaviorEngine = behaviorEngine;
        this.planService = planService;
    }

    @PostMapping("/reflection/run")
    public Map<String, Object> runReflection() {
        return Map.of("records", reflectionService.runAllDaily());
    }

    @PostMapping("/reflection/run-weekly")
    public Map<String, Object> runWeeklyReflection() {
        return Map.of("records", reflectionService.runAllWeekly());
    }

    @PostMapping("/persona/evolve")
    public Map<String, Object> evolvePersona() {
        List<String> changes = personaEvolutionService.runAll();
        return Map.of("changes", changes, "count", changes.size());
    }

    @PostMapping("/proactive/run")
    public Map<String, Object> runProactive() {
        List<String> actions = proactiveEngine.run();
        return Map.of("actions", actions, "count", actions.size());
    }

    /** 定向触发某伴侣的主动消息(?force=true 模拟隔了一阵没聊, 稳定验证实时推送) */
    @PostMapping("/proactive/run/{companionId}")
    public Map<String, Object> runProactiveFor(@PathVariable String companionId,
                                               @RequestParam(required = false, defaultValue = "false") boolean force) {
        List<String> actions = proactiveEngine.runForCompanion(companionId, force);
        return Map.of("actions", actions, "count", actions.size());
    }

    @PostMapping("/birthday/ensure")
    public Map<String, Object> ensureBirthdays() {
        birthdayService.ensureBirthdayReminders();
        return Map.of("success", true);
    }

    // ── 生命内核 ───────────────────────────

    @PostMapping("/life/tick")
    public Map<String, Object> lifeTick(@RequestParam(required = false) String companionId) {
        int count = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            if (companionId != null && !companionId.equals(c.getId())) continue;
            lifeRuntime.tick(c.getId(), LocalDateTime.now());
            count++;
        }
        return Map.of("ticked", count);
    }

    @PostMapping("/thought/run")
    public Map<String, Object> thoughtRun(@RequestParam(required = false) String companionId) {
        // 手动触发想法维护 + 从未完成事项补触发想法
        thoughtMaintenanceJob.maintain();
        int thoughts = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            if (companionId != null && !companionId.equals(c.getId())) continue;
            for (var loop : openLoopService.activeLoops(c.getId())) {
                thoughts += thoughtEngine.maybeFromConversation(c.getId(), loop.getTitle()) != null ? 1 : 0;
            }
        }
        return Map.of("maintained", true, "thoughtsCreated", thoughts);
    }

    @PostMapping("/memory/consolidate")
    public Map<String, Object> consolidate(@RequestParam(required = false) String companionId) {
        int total = 0;
        List<String> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            if (companionId != null && !companionId.equals(c.getId())) continue;
            int n = experienceProcessor.consolidate(c.getId());
            total += n;
            if (n > 0) results.add(c.getName() + ":" + n);
        }
        return Map.of("consolidated", total, "detail", results);
    }

    @PostMapping("/open-loops/extract")
    public Map<String, Object> extractOpenLoops(@RequestParam String companionId,
                                                @RequestParam String text) {
        var loop = openLoopService.create(companionId, "USER_EVENT", "手动抽取",
                text, 0.6, 0.5, null);
        return Map.of("created", loop != null);
    }

    @PostMapping("/cognitive/tick")
    public Map<String, Object> cognitiveTick() {
        // 统一内核 tick: Life→Emotion→Thought→OpenLoop→Proactive(设计文档 §17)
        int ticked = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            cognitiveRuntime.tick(c.getId(), LocalDateTime.now());
            ticked++;
        }
        return Map.of("ticked", ticked);
    }

    // ── 行为引擎 ─────────────────────────────

    @PostMapping("/behavior/run")
    public Map<String, Object> behaviorRun() {
        behaviorEngine.evaluateAll(LocalDateTime.now());
        return Map.of("run", true);
    }

    @PostMapping("/behavior/run/{companionId}")
    public Map<String, Object> behaviorRunFor(@PathVariable String companionId) {
        var outcome = behaviorEngine.evaluate(companionId, LocalDateTime.now(), "ADMIN");
        return Map.of("action", outcome != null ? String.valueOf(outcome.action()) : "none",
                "trigger", outcome != null ? outcome.trigger() : null,
                "score", outcome != null ? Math.round(outcome.score() * 100) / 100.0 : 0);
    }

    /** V9 验收: 手动打断一个计划(title 模糊匹配) —— 之后用户追问"你不是说…吗"可沿因果链解释 */
    @PostMapping("/plan/interrupt/{companionId}")
    public Map<String, Object> planInterrupt(@PathVariable String companionId,
                                             @RequestParam(required = false) String title,
                                             @RequestParam(required = false) String reason) {
        var plans = planService.activePlans(companionId);
        var target = plans.stream()
                .filter(p -> title == null || title.isBlank()
                        || (p.getTitle() != null && p.getTitle().contains(title)))
                .findFirst().orElse(null);
        if (target == null) {
            return Map.of("interrupted", false, "reason", "没有匹配的进行中计划");
        }
        planService.interrupt(companionId, target.getId(), "admin_test",
                reason == null || reason.isBlank() ? "临时有事, 计划先放一放" : reason);
        return Map.of("interrupted", true, "title", target.getTitle(),
                "explain", planService.explain(companionId, target.getTitle()));
    }
}
