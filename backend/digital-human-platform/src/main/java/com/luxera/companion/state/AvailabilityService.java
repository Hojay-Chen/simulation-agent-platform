package com.luxera.companion.state;

import com.luxera.companion.agent.CompanionSchedule;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 可用状态派生(P1 §四十一~四十三): 从 作息 + 精力/压力/社交电量 派生当前 availability。
 * 不建表 —— 它是状态的"投影", 由 AgentState + CompanionSchedule 实时计算。
 */
@Component
public class AvailabilityService {

    private final CompanionSchedule schedule;

    public AvailabilityService(CompanionSchedule schedule) {
        this.schedule = schedule;
    }

    public CompanionAvailability current(String companionId, LocalDateTime now) {
        return current(companionId, now, null);
    }

    public CompanionAvailability current(String companionId, LocalDateTime now, AgentState state) {
        CompanionSchedule.Activity activity = schedule.activityFor(companionId, now);
        if (activity == CompanionSchedule.Activity.SLEEP) {
            return CompanionAvailability.SLEEPING;
        }
        double energy = state != null ? state.getEnergy() : 0.6;
        double stress = state != null ? state.getStress() : 0.3;
        double social = state != null ? state.getSocialEnergy() : 0.6;

        // 低电量/高压力 → 休息或走神
        if (energy < 0.25) return CompanionAvailability.RESTING;
        if (energy < 0.4) return CompanionAvailability.DISTRACTED;

        // 上班时段
        if (activity == CompanionSchedule.Activity.WORK_BUSY
                || activity == CompanionSchedule.Activity.WORK_AFTERNOON) {
            return CompanionAvailability.BUSY;
        }
        // 通勤/晚间自由 → 可能在外面/见朋友
        if (activity == CompanionSchedule.Activity.EVENING) {
            if (social > 0.7) return CompanionAvailability.SOCIALIZING;
            return CompanionAvailability.TRAVELING;
        }
        if (activity == CompanionSchedule.Activity.LEISURE && social > 0.75) {
            return CompanionAvailability.SOCIALIZING;
        }
        return CompanionAvailability.AVAILABLE;
    }

    /** 中文描述(注入 Prompt, 让回复自然体现她此刻的处境) */
    public String describe(CompanionAvailability a) {
        return switch (a) {
            case AVAILABLE -> "闲着";
            case BUSY -> "正忙着";
            case DISTRACTED -> "有点走神";
            case RESTING -> "正歇着";
            case SLEEPING -> "在睡觉";
            case SOCIALIZING -> "和朋友在一起";
            case TRAVELING -> "在外面/路上";
        };
    }
}
