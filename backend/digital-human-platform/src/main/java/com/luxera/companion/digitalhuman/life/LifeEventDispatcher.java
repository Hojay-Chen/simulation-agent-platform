package com.luxera.companion.digitalhuman.life;

import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.life.LifeActivity;
import com.luxera.companion.life.LifeActivityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V10 §7.4 LifeEventDispatcher: 生活事件分发执行。
 *
 * ACTIVITY_END(活动结束): 幂等收尾 —— 活动仍在进行(ACTIVE/PLANNED)才结束,
 * 写 Reality Ledger(ACTIVITY_ENDED), 生活"准点"变化而非等下一个 tick。
 * 与 LifeTickJob 轮询兜底双路径共存: 先到先收尾, 后到者幂等跳过。
 */
@Slf4j
@Component
public class LifeEventDispatcher {

    private final LifeActivityRepository activityRepository;
    private final RealityLedger realityLedger;
    private final com.luxera.companion.plan.PlanService planService;

    public LifeEventDispatcher(LifeActivityRepository activityRepository, RealityLedger realityLedger,
                               com.luxera.companion.plan.PlanService planService) {
        this.activityRepository = activityRepository;
        this.realityLedger = realityLedger;
        this.planService = planService;
    }

    /** 分发一条到点事件; 返回是否成功(失败由 LifeScheduleJob 标记 FAILED) */
    public boolean dispatch(LifeScheduleRecord record) {
        LifeEventType type = LifeEventType.valueOf(record.getEventType());
        return switch (type) {
            case ACTIVITY_END -> handleActivityEnd(record);
            case PLAN_REMINDER -> handlePlanReminder(record);
        };
    }

    /** 活动结束: 幂等收尾(ACTIVE/PLANNED → DONE) + Reality Ledger */
    private boolean handleActivityEnd(LifeScheduleRecord record) {
        String activityId = record.getPayload() == null ? null
                : record.getPayload().get("activityId") == null ? null
                : record.getPayload().get("activityId").toString();
        if (activityId == null) {
            return false;
        }
        LifeActivity activity = activityRepository.findById(activityId).orElse(null);
        if (activity == null) {
            log.warn("[LifeScheduler] 活动不存在, 跳过: {}", activityId);
            return false;
        }
        if ("DONE".equals(activity.getStatus())) {
            // 已被 tick 或先前事件收尾 → 幂等跳过(视为成功)
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        activity.setStatus("DONE");
        if (activity.getActualEnd() == null) {
            activity.setActualEnd(now);
        }
        activityRepository.save(activity);

        realityLedger.append(record.getPersonId(), RealityEventType.ACTIVITY_ENDED, Map.of(
                "activityId", activity.getId(),
                "type", activity.getType() == null ? "" : activity.getType(),
                "title", activity.getTitle() == null ? "" : activity.getTitle(),
                "endedAt", now.toString()));
        log.info("[LifeScheduler] {} 活动结束(时间触发): {}", record.getPersonId(), activity.getTitle());
        return true;
    }

    /**
     * 计划提醒: 计划到点 → 激活执行(PLANNED → EXECUTING, V10 §7.3 Plan 状态机)。
     * 计划是概率性的(可能不去); 激活不等于完成 —— 是否真的执行由后续行为决策决定。
     */
    private boolean handlePlanReminder(LifeScheduleRecord record) {
        String planId = record.getPayload() == null ? null
                : record.getPayload().get("planId") == null ? null
                : record.getPayload().get("planId").toString();
        if (planId == null) {
            return false;
        }
        try {
            planService.activate(record.getPersonId(), planId, "计划到点,开始执行");
            log.info("[LifeScheduler] {} 计划到点激活: {}", record.getPersonId(), planId);
            return true;
        } catch (Exception e) {
            log.warn("[LifeScheduler] 计划激活失败 plan={}: {}", planId, e.getMessage());
            return false;
        }
    }
}
