package com.luxera.companion.attention;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.phone.PhoneState;
import com.luxera.companion.state.AgentState;
import org.springframework.stereotype.Component;

/**
 * 注意力场(§六/§七): 不是"忙就不看手机", 而是"当前活动决定注意手机的概率分布"。
 * 长时间工作注意力下降 → 手机响更容易被注意到(注意力低谷时反而容易分心)。
 * 输出: 一条用户消息被"注意到"并"查看"的概率, 以及查看的延迟。
 */
@Component
public class AttentionService {

    /** 注意力状态: 由作息+精力+手机派生 */
    public Attention compute(CompanionSchedule.Activity activity, AgentState state,
                             PhoneState phone, double messageSalience) {
        double energy = state != null ? state.getEnergy() : 0.6;
        double stress = state != null ? state.getStress() : 0.3;

        // 基础任务注意力(活动对注意力的占用)
        double taskAttention = switch (activity) {
            case WORK_BUSY, WORK_AFTERNOON -> 0.85;
            case MORNING -> 0.4;
            case LUNCH -> 0.3;
            case EVENING -> 0.25;
            case LEISURE -> 0.15;
            case LATE_NIGHT -> 0.35;
            case SLEEP -> 0.95;   // 睡觉时注意力几乎全被占用
        };
        // 长时间工作(用 stress 代理疲劳): 注意力下降 → 反而容易分心
        double fatigueFactor = 0.15 + stress * 0.4;
        double effectiveTask = taskAttention * (1 - fatigueFactor * 0.3);

        // 消息"可见度" = 手机通知触达度 × 消息本身显著性
        double phoneFactor = phone != null ? phoneNotificationFactor(phone) : 0.5;
        double visible = phoneFactor * (0.4 + messageSalience * 0.6);

        // 注意到概率: 消息越可见, 任务注意力越低(越空闲), 越容易注意到
        double noticeProbability = visible * (1 - effectiveTask * 0.7) + visible * 0.3;
        // 查看概率: 注意到后, 是否真的打开聊天(空闲/手机在手/消息紧急 → 高)
        double inspectProbability = 0.3
                + (1 - effectiveTask) * 0.4
                + visible * 0.2
                + messageSalience * 0.2;

        // 查看延迟: 忙/疲劳 → 慢; 空闲 → 快
        long inspectDelay = (long) (600 + (1 - inspectProbability) * 3000 + effectiveTask * 2000);

        return new Attention(taskAttention, effectiveTask, noticeProbability,
                inspectProbability, inspectDelay);
    }

    private static double phoneNotificationFactor(PhoneState phone) {
        String mode = phone.getNotificationMode() == null ? "vibrate" : phone.getNotificationMode();
        double base = switch (mode) {
            case "dnd" -> 0.0;
            case "silent" -> 0.2;
            case "vibrate" -> 0.5;
            case "sound" -> 0.75;
            default -> 0.5;
        };
        String loc = phone.getPhoneLocation() == null ? "hand" : phone.getPhoneLocation();
        switch (loc) {
            case "hand" -> base += 0.25;
            case "desk" -> { }
            case "bag" -> base -= 0.1;
            case "other_room" -> base -= 0.3;
            default -> { }
        }
        return Math.max(0, Math.min(1, base));
    }

    /** 注意力计算结果 */
    public record Attention(double taskAttention, double effectiveTaskAttention,
                            double noticeProbability, double inspectProbability,
                            long inspectDelayMs) {}
}
