package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.phone.PhoneState;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * SnapshotFactory(Adapter Pattern):
 * 从现有运行时状态(AgentState/PhoneState/CompanionSchedule)提取 V10 感知快照。
 *
 * 让 PerceptionRuntime/DecisionPolicyEngine 在真实运行时可直接工作,
 * 而无需感知底层存储 —— 未来替换状态来源只改这里。
 */
@Component
public class SnapshotFactory {

    private final AgentStateService agentStateService;
    private final PhoneStateService phoneStateService;
    private final CompanionSchedule schedule;

    public SnapshotFactory(AgentStateService agentStateService, PhoneStateService phoneStateService,
                           CompanionSchedule schedule) {
        this.agentStateService = agentStateService;
        this.phoneStateService = phoneStateService;
        this.schedule = schedule;
    }

    public LifeSnapshot life(String companionId, LocalDateTime now) {
        CompanionSchedule.Activity activity = schedule.activityFor(companionId, now);
        boolean sleeping = activity == CompanionSchedule.Activity.SLEEP;
        double attentionDemand = switch (activity) {
            case WORK_BUSY, WORK_AFTERNOON -> 0.8;
            case LUNCH, EVENING, MORNING -> 0.45;
            case LEISURE, LATE_NIGHT -> 0.25;
            case SLEEP -> 0.0;
        };
        return LifeSnapshot.of(activity.name(), attentionDemand, sleeping,
                schedule.describe(companionId, "她", now));
    }

    public MindSnapshot mind(String companionId) {
        AgentState state = agentStateService.get(companionId);
        if (state == null) {
            return MindSnapshot.of(0.6, 0.3, 0.72);
        }
        double arousal = state.getStress() * 0.6 + state.getJoy() * 0.3 + state.getAnxiety() * 0.4;
        return MindSnapshot.of(state.getFocus(), clamp01(arousal), state.getEnergy());
    }

    public DeviceSnapshot device(String companionId, LocalDateTime now) {
        PhoneState phone = phoneStateService.current(companionId, now);
        if (phone == null) {
            return DeviceSnapshot.of("vibrate", false, 0.1, "hand");
        }
        double distance = switch (phone.getPhoneLocation() == null ? "hand" : phone.getPhoneLocation()) {
            case "hand" -> 0.1;
            case "bag" -> 0.4;
            case "desk" -> 0.3;
            case "other_room" -> 0.8;
            default -> 0.2;
        };
        return DeviceSnapshot.of(phone.getNotificationMode(), phone.isDoNotDisturb(),
                distance, phone.getPhoneLocation());
    }

    public EnvironmentSnapshot environment() {
        // 环境感知来源(如智能家居/麦克风)尚未接入, 默认安静
        return EnvironmentSnapshot.of(0.2);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
