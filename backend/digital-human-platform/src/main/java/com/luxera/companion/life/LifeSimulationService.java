package com.luxera.companion.life;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.digitalhuman.life.LifeEventType;
import com.luxera.companion.digitalhuman.life.LifeEventScheduler;
import com.luxera.companion.digitalhuman.life.ScheduledLifeEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** 生活模拟: 按作息生成当天活动(Level 0, 不调用 LLM) */
@Service
public class LifeSimulationService {

    private final CompanionSchedule schedule;
    private final LifeActivityRepository activityRepo;
    private final ActivitySpecProvider specProvider;
    /** V10 §7.4: 生活事件排程器(活动结束时间触发) */
    private final LifeEventScheduler lifeEventScheduler;

    public LifeSimulationService(CompanionSchedule schedule, LifeActivityRepository activityRepo,
                                 ActivitySpecProvider specProvider,
                                 LifeEventScheduler lifeEventScheduler) {
        this.schedule = schedule;
        this.activityRepo = activityRepo;
        this.specProvider = specProvider;
        this.lifeEventScheduler = lifeEventScheduler;
    }

    /** 若当天还没有活动, 则按作息生成 PLANNED 活动 */
    @Transactional
    public void ensureDayPlanned(String companionId, LocalDate date) {
        boolean exists = !activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .isEmpty();
        if (exists) return;

        List<CompanionSchedule.TimeBlock> blocks = schedule.dayBlocks(companionId, date);
        for (CompanionSchedule.TimeBlock b : blocks) {
            LifeActivity a = new LifeActivity();
            a.setCompanionId(companionId);
            a.setType(b.type());
            a.setTitle(b.title());
            a.setPlannedStart(date.atTime(b.start()));
            a.setPlannedEnd(endTime(date, b.start(), b.end()));
            a.setImportance(blockImportance(b.type()));
            a.setEmotionalSignificance(blockEmotional(b.type()));
            a.setStatus("PLANNED");
            a.setSource("SIMULATED_LIFE_EVENT");
            // §6: 具体活动属性(注意力占用/可打断性/手机可用性/情绪影响)
            var spec = specProvider.specFor(b.type());
            a.setAttentionDemand(spec.attentionDemand());
            a.setInterruptibility(spec.interruptibility());
            a.setPhoneAvailability(spec.phoneAvailability());
            a.setMoodEffect(spec.moodEffect());
            activityRepo.save(a);

            // V10 §7.4: 时间触发 —— 活动创建即排程"活动结束"事件(到点准点收尾,
            // 不依赖轮询 tick 发现超时; 幂等 scheduleId)
            if (a.getPlannedEnd() != null) {
                try {
                    lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                            "activity-end-" + a.getId(), companionId, LifeEventType.ACTIVITY_END,
                            a.getPlannedEnd(),
                            Map.of("activityId", a.getId(), "type", b.type(), "title", b.title())));
                } catch (Exception e) {
                    // 排程失败不影响活动创建(tick 轮询兜底)
                }
            }
        }
    }

    private static LocalDateTime endTime(LocalDate date, LocalTime start, LocalTime end) {
        if (end.isBefore(start)) {
            return date.plusDays(1).atTime(end);
        }
        return date.atTime(end);
    }

    private static double blockImportance(String type) {
        return switch (type) {
            case "WORK", "MEAL", "SLEEP" -> 0.5;
            case "LEISURE", "SOCIAL", "HOBBY" -> 0.4;
            default -> 0.3;
        };
    }

    private static double blockEmotional(String type) {
        return switch (type) {
            case "SOCIAL", "HOBBY" -> 0.6;
            case "WORK" -> 0.3;
            default -> 0.4;
        };
    }
}
