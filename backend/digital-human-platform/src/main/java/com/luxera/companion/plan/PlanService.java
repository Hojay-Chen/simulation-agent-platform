package com.luxera.companion.plan;

import com.luxera.companion.cognitive.CognitiveSessionService;
import com.luxera.companion.world.WorldEvent;
import com.luxera.companion.world.WorldEventEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V9 §5 PlanService: 计划生命周期(Reality Ledger 的核心状态机)。
 *
 * 计划可以没有, 也可以改变; 改变必须有原因(PlanRevision)。
 * 突发事件按 interrupt 权重覆盖低优先级计划(high flexibility → 被 SUPERSEDED)。
 * 计划状态变化同时发布世界事件 + 同步 Cognitive Session 的 active_plans 摘要。
 */
@Slf4j
@Service
public class PlanService {

    private final PlanRepository planRepo;
    private final PlanRevisionRepository revisionRepo;
    private final WorldEventEngine worldEventEngine;
    private final CognitiveSessionService cognitiveSessionService;

    public PlanService(PlanRepository planRepo, PlanRevisionRepository revisionRepo,
                       WorldEventEngine worldEventEngine, CognitiveSessionService cognitiveSessionService) {
        this.planRepo = planRepo;
        this.revisionRepo = revisionRepo;
        this.worldEventEngine = worldEventEngine;
        this.cognitiveSessionService = cognitiveSessionService;
    }

    /** 创建计划(confidence: 计划是概率性的, 无计划也是合法状态) */
    @Transactional
    public Plan create(String companionId, String type, String title,
                       double confidence, double flexibility,
                       LocalDateTime expectedTime, String triggerCondition,
                       String parentPlanId) {
        Plan p = new Plan();
        p.setCompanionId(companionId);
        p.setType(type);
        p.setTitle(title);
        p.setConfidence(clamp(confidence));
        p.setFlexibility(clamp(flexibility));
        p.setExpectedTime(expectedTime);
        p.setTriggerCondition(triggerCondition);
        p.setParentPlanId(parentPlanId);
        planRepo.save(p);
        recordRevision(p, "CREATED", null, Plan.STATUS_PLANNED, "计划了:" + title);
        syncActivePlans(companionId);
        return p;
    }

    @Transactional
    public void activate(String companionId, String planId, String reason) {
        planRepo.findById(planId).ifPresent(p -> {
            String from = p.getStatus();
            p.setStatus(Plan.STATUS_ACTIVE);
            planRepo.save(p);
            recordRevision(p, "ACTIVATED", from, Plan.STATUS_ACTIVE, reason);
            syncActivePlans(companionId);
            worldEventEngine.publish(companionId, "PLAN_ACTIVATED", WorldEvent.SRC_LIFE,
                    p.getId(), null, Map.of("title", p.getTitle()), 0.4);
        });
    }

    @Transactional
    public void complete(String companionId, String planId, String reason) {
        planRepo.findById(planId).ifPresent(p -> {
            String from = p.getStatus();
            p.setStatus(Plan.STATUS_COMPLETED);
            planRepo.save(p);
            recordRevision(p, "COMPLETED", from, Plan.STATUS_COMPLETED, reason);
            syncActivePlans(companionId);
            worldEventEngine.publish(companionId, "PLAN_COMPLETED", WorldEvent.SRC_LIFE,
                    p.getId(), null, Map.of("title", p.getTitle()), 0.3);
        });
    }

    @Transactional
    public void cancel(String companionId, String planId, String reason) {
        planRepo.findById(planId).ifPresent(p -> {
            String from = p.getStatus();
            p.setStatus(Plan.STATUS_CANCELLED);
            p.setRevisionReason(reason);
            planRepo.save(p);
            recordRevision(p, "CANCELLED", from, Plan.STATUS_CANCELLED, reason);
            syncActivePlans(companionId);
            worldEventEngine.publish(companionId, "PLAN_CANCELLED", WorldEvent.SRC_LIFE,
                    p.getId(), null, Map.of("title", p.getTitle(), "reason", reason == null ? "" : reason), 0.3);
        });
    }

    /**
     * 突发事件打断计划: 该计划被 SUPERSEDED 并记录打断来源(interrupted_by)。
     * 用户追问"你不是说要…吗"时, 沿 revision 链可以自然解释。
     */
    @Transactional
    public void interrupt(String companionId, String planId, String interruptedBy, String reason) {
        planRepo.findById(planId).ifPresent(p -> {
            String from = p.getStatus();
            p.setStatus(Plan.STATUS_SUPERSEDED);
            p.setInterruptedBy(interruptedBy);
            p.setRevisionReason(reason);
            planRepo.save(p);
            recordRevision(p, "INTERRUPTED", from, Plan.STATUS_SUPERSEDED, reason);
            syncActivePlans(companionId);
            worldEventEngine.publish(companionId, "PLAN_INTERRUPTED", WorldEvent.SRC_LIFE,
                    p.getId(), interruptedBy, Map.of("title", p.getTitle(), "reason", reason == null ? "" : reason), 0.45);
        });
    }

    /** 进行中的计划 */
    @Transactional(readOnly = true)
    public List<Plan> activePlans(String companionId) {
        return planRepo.findActive(companionId);
    }

    /** 到预期时间还没开始的低概率计划 → 自然取消(概率性: 不是所有计划都会发生) */
    @Transactional
    public int expireStalePlans(String companionId, LocalDateTime now) {
        int n = 0;
        for (Plan p : planRepo.findByCompanionIdAndStatusAndExpectedTimeBefore(
                companionId, Plan.STATUS_PLANNED, now.minusHours(2))) {
            if (p.getConfidence() < 0.75) {
                cancel(companionId, p.getId(), "过了预期时间也没去做, 就算了");
                n++;
            }
        }
        return n;
    }

    /** 沿 revision 链解释"为什么计划变了"(供用户追问时注入上下文) */
    @Transactional(readOnly = true)
    public String explain(String companionId, String planTitle) {
        Plan p = planRepo.findActive(companionId).stream()
                .filter(x -> x.getTitle() != null && x.getTitle().contains(planTitle))
                .findFirst().orElse(null);
        if (p == null) {
            // 找最近被中断/取消的同名计划
            List<Plan> all = planRepo.findByCompanionIdAndStatusOrderByExpectedTimeAsc(companionId, Plan.STATUS_SUPERSEDED);
            p = all.stream().filter(x -> x.getTitle() != null && x.getTitle().contains(planTitle))
                    .findFirst().orElse(null);
            if (p == null) return null;
            List<PlanRevision> revs = revisionRepo.findByPlanIdOrderByOccurredAtAsc(p.getId());
            if (revs.isEmpty()) return null;
            PlanRevision last = revs.get(revs.size() - 1);
            return "本来是打算" + p.getTitle() + "的, 后来" + (last.getReason() == null ? "没顾上" : last.getReason());
        }
        return "还想着" + p.getTitle();
    }

    /** 最近被打断的计划(Reality Consistency 校验用) */
    @Transactional(readOnly = true)
    public List<Plan> supersededRecently(String companionId, LocalDateTime since) {
        List<Plan> out = new ArrayList<>();
        for (Plan p : planRepo.findByCompanionIdAndStatusOrderByExpectedTimeAsc(companionId, Plan.STATUS_SUPERSEDED)) {
            if (p.getUpdatedAt() != null && p.getUpdatedAt().isAfter(since)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 最近一次计划变更的解释(用户追问"你不是说…吗"时注入; 无变更返回 null) */
    @Transactional(readOnly = true)
    public String recentExplanation(String companionId, LocalDateTime now) {
        Plan latest = null;
        for (Plan p : planRepo.findByCompanionIdAndStatusOrderByExpectedTimeAsc(companionId, Plan.STATUS_SUPERSEDED)) {
            if (p.getUpdatedAt() != null && p.getUpdatedAt().isAfter(now.minusHours(48))) {
                latest = p;
                break;
            }
        }
        if (latest == null) {
            for (Plan p : planRepo.findByCompanionIdAndStatusOrderByExpectedTimeAsc(companionId, Plan.STATUS_CANCELLED)) {
                if (p.getUpdatedAt() != null && p.getUpdatedAt().isAfter(now.minusHours(48))) {
                    latest = p;
                    break;
                }
            }
        }
        if (latest == null) return null;
        return explain(companionId, latest.getTitle());
    }

    /** 计划摘要(供 Cognitive Session / 上下文注入) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> planBriefs(String companionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Plan p : activePlans(companionId)) {
            out.add(CognitiveSessionService.planBrief(
                    p.getTitle(), p.getStatus(),
                    p.getExpectedTime() == null ? null : p.getExpectedTime().toString()));
        }
        return out;
    }

    private void recordRevision(Plan p, String action, String from, String to, String reason) {
        PlanRevision r = new PlanRevision();
        r.setPlanId(p.getId());
        r.setAction(action);
        r.setFromStatus(from);
        r.setToStatus(to);
        r.setReason(reason);
        revisionRepo.save(r);
    }

    /** 同步 Cognitive Session 的进行中计划摘要 */
    private void syncActivePlans(String companionId) {
        try {
            cognitiveSessionService.setActivePlans(companionId, planBriefs(companionId));
        } catch (Exception e) {
            log.debug("[PlanService] 同步认知计划摘要失败: {}", e.getMessage());
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
