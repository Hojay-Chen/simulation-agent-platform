package com.luxera.companion.life;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.experience.ExperienceProcessor;
import com.luxera.companion.world.WorldEvent;
import com.luxera.companion.world.WorldEventEngine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 生活运行时(设计文档 §5.5 / §17): 事件驱动 + 时间推进 + 关键时刻唤醒。
 * Level 0-1: 纯时间推进 + 规则, 不调用 LLM。
 * §四十六: 生活活动的变化是数字人世界中的事件(世界每时每刻都在运行,
 * 而不是只有用户发消息世界才开始)。
 */
@Component
public class LifeRuntime {

    private final CompanionLifeService lifeService;
    private final LifeSimulationService simulation;
    private final LifeActivityRepository activityRepo;
    private final CompanionSchedule schedule;
    private final ExperienceProcessor experienceProcessor;
    private final WorldEventEngine worldEventEngine;
    private final com.luxera.companion.plan.PlanService planService;
    /** V10 §7.4: 生活事件排程器(计划到点提醒) */
    private final com.luxera.companion.digitalhuman.life.LifeEventScheduler lifeEventScheduler;

    public LifeRuntime(CompanionLifeService lifeService, LifeSimulationService simulation,
                       LifeActivityRepository activityRepo, CompanionSchedule schedule,
                       ExperienceProcessor experienceProcessor, WorldEventEngine worldEventEngine,
                       com.luxera.companion.plan.PlanService planService,
                       com.luxera.companion.digitalhuman.life.LifeEventScheduler lifeEventScheduler) {
        this.lifeService = lifeService;
        this.simulation = simulation;
        this.activityRepo = activityRepo;
        this.schedule = schedule;
        this.experienceProcessor = experienceProcessor;
        this.worldEventEngine = worldEventEngine;
        this.planService = planService;
        this.lifeEventScheduler = lifeEventScheduler;
    }

    /** 推进一个伴侣的连续生活 */
    @Transactional
    public void tick(String companionId, LocalDateTime now) {
        CompanionLife life = lifeService.getOrCreate(companionId);
        LocalDate today = now.toLocalDate();

        // 跨天重置
        if (life.getLifeDate() == null || !life.getLifeDate().equals(today)) {
            finalizeDay(companionId, life.getLifeDate());
            life.setLifeDate(today);
            simulation.ensureDayPlanned(companionId, today);
        }
        simulation.ensureDayPlanned(companionId, today);

        // 推进当前状态
        CompanionSchedule.Activity activity = schedule.activityFor(companionId, now);
        String prevPhase = life.getDayPhase();
        life.setDayPhase(activity.name());
        life.setCurrentActivity(activityTitle(activity));
        life.setCurrentLocation(locationFor(activity));
        life.setWorkStatus(switch (activity) {
            case WORK_BUSY, WORK_AFTERNOON -> "busy";
            case EVENING -> "off";
            default -> "free";
        });
        life.setSleepStatus(activity == CompanionSchedule.Activity.SLEEP ? "asleep" : "awake");
        life.setLifeEnergy(clamp(life.getLifeEnergy() + energyDelta(activity)));
        life.setLastSimulatedAt(now);
        markActivities(companionId, today, now);
        lifeService.save(life);

        // 进入有意义的阶段 → 记录一条生活经历(Level 1) + 世界事件(世界持续运行)
        if (prevPhase != null && !prevPhase.equals(activity.name())) {
            if (notableTransition(activity)) {
                experienceProcessor.recordLifeEvent(companionId, activityTitle(activity), null, 0.4, 0.3);
            }
            worldEventEngine.publish(companionId, WorldEventEngine.TYPE_ACTIVITY_STARTED,
                    WorldEvent.SRC_LIFE, companionId, null,
                    Map.of("activity", activity.name(), "title", activityTitle(activity),
                            "location", locationFor(activity)), 0.3);
        }
    }

    private void markActivities(String companionId, LocalDate date, LocalDateTime now) {
        List<LifeActivity> acts = activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        for (LifeActivity a : acts) {
            String newStatus = a.getStatus();
            if (a.getPlannedEnd() != null && a.getPlannedEnd().isBefore(now)) {
                newStatus = "DONE";
                a.setActualEnd(a.getActualEnd() != null ? a.getActualEnd() : a.getPlannedEnd());
            } else if (a.getPlannedStart() != null && !a.getPlannedStart().isAfter(now)) {
                newStatus = "ACTIVE";
                a.setActualStart(a.getActualStart() != null ? a.getActualStart() : a.getPlannedStart());
            }
            if (!newStatus.equals(a.getStatus())) {
                a.setStatus(newStatus);
                activityRepo.save(a);
            }
            // V9 §5: 生活活动 ↔ Reality 计划状态机(计划是概率性的, 改变有原因)
            syncPlan(companionId, a, a.getStatus(), now);
        }
    }

    /** V9: 活动与计划双向同步 —— 计划成为可被打断/可解释的生活时间轴 */
    private void syncPlan(String companionId, LifeActivity a, String status, LocalDateTime now) {
        try {
            String title = a.getTitle() == null || a.getTitle().isBlank()
                    ? (a.getType() == null ? "活动" : a.getType()) : a.getTitle();
            var active = planService.activePlans(companionId);
            boolean exists = active.stream()
                    .anyMatch(p -> p.getTitle() != null && p.getTitle().contains(title));
            switch (status == null ? "" : status) {
                case "PLANNED" -> {
                    // 规划时就建立计划(概率性: 她打算做, 但可能不去)
                    if (!exists) {
                        var plan = planService.create(companionId, "ACTIVITY", title, 0.6, 0.5,
                                a.getPlannedStart(), null, null);
                        // V10 §7.4: 计划到点提醒(时间触发; 到点由 LifeEventDispatcher 激活计划)
                        if (plan.getExpectedTime() != null) {
                            try {
                                lifeEventScheduler.schedule(
                                        com.luxera.companion.digitalhuman.life.ScheduledLifeEvent.of(
                                                "plan-reminder-" + plan.getId(), companionId,
                                                com.luxera.companion.digitalhuman.life.LifeEventType.PLAN_REMINDER,
                                                plan.getExpectedTime(),
                                                Map.of("planId", plan.getId(), "title", plan.getTitle())));
                            } catch (Exception ignored) {
                                // 排程失败不影响计划本身
                            }
                        }
                    }
                }
                case "ACTIVE" -> {
                    if (!exists) {
                        planService.create(companionId, "ACTIVITY", title, 0.85, 0.4,
                                a.getPlannedStart(), null, null);
                    }
                    // 活动开始 = 计划落地
                    active.stream()
                            .filter(p -> p.getTitle() != null && p.getTitle().contains(title))
                            .findFirst()
                            .ifPresent(p -> planService.activate(companionId, p.getId(), "开始做了"));
                }
                case "DONE" -> {
                    active.stream()
                            .filter(p -> p.getTitle() != null && p.getTitle().contains(title))
                            .findFirst()
                            .ifPresent(p -> planService.complete(companionId, p.getId(), "做完了"));
                }
                default -> { }
            }
        } catch (Exception e) {
            // 计划同步失败不影响生活主流程
        }
    }

    private void finalizeDay(String companionId, LocalDate date) {
        if (date == null) return;
        List<LifeActivity> acts = activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        for (LifeActivity a : acts) {
            if (!"DONE".equals(a.getStatus()) && !"CANCELLED".equals(a.getStatus())) {
                a.setStatus("DONE");
                if (a.getActualEnd() == null) a.setActualEnd(LocalDate.now().atStartOfDay());
                activityRepo.save(a);
            }
        }
    }

    private static String activityTitle(CompanionSchedule.Activity a) {
        return switch (a) {
            case SLEEP -> "睡觉";
            case MORNING -> "起床收拾";
            case WORK_BUSY -> "上午工作";
            case LUNCH -> "午休";
            case WORK_AFTERNOON -> "下午工作";
            case EVENING -> "下班";
            case LEISURE -> "悠闲时光";
            case LATE_NIGHT -> "准备休息";
        };
    }

    private static String locationFor(CompanionSchedule.Activity a) {
        return switch (a) {
            case WORK_BUSY, WORK_AFTERNOON -> "公司";
            case LUNCH -> "公司附近";
            case LEISURE, EVENING -> "常去的咖啡店";
            case SLEEP, LATE_NIGHT -> "家";
            default -> "家附近";
        };
    }

    private static double energyDelta(CompanionSchedule.Activity a) {
        return switch (a) {
            case SLEEP -> 0.04;
            case MORNING, LUNCH -> 0.02;
            case EVENING, LEISURE -> 0.01;
            case WORK_BUSY, WORK_AFTERNOON -> -0.015;
            default -> 0;
        };
    }

    private static boolean notableTransition(CompanionSchedule.Activity a) {
        return a == CompanionSchedule.Activity.LUNCH || a == CompanionSchedule.Activity.EVENING
                || a == CompanionSchedule.Activity.SLEEP || a == CompanionSchedule.Activity.LEISURE;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
