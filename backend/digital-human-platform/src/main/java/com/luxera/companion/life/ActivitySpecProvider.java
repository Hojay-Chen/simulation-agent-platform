package com.luxera.companion.life;

import org.springframework.stereotype.Component;

/**
 * §6 Activity Model: 把活动类型映射为具体的 属性。
 * 注意力占用、可打断性、手机可用性、情绪影响 —— 这些决定
 * "消息到达时她会不会注意到 / 会不会看 / 会不会回"。
 */
@Component
public class ActivitySpecProvider {

    /** 活动属性 */
    public record ActivitySpec(double attentionDemand, double interruptibility,
                               double phoneAvailability, double moodEffect) {}

    /** 按活动类型返回 属性; 未知类型给中性值 */
    public ActivitySpec specFor(String type) {
        if (type == null) return new ActivitySpec(0.5, 0.5, 0.6, 0);
        return switch (type) {
            case "SLEEP" -> new ActivitySpec(0.95, 0.05, 0.05, -0.02);
            case "WAKE_UP", "MORNING" -> new ActivitySpec(0.30, 0.70, 0.60, 0.03);
            case "WORK", "WORK_BUSY", "WORK_AFTERNOON" -> new ActivitySpec(0.85, 0.20, 0.30, -0.05);
            case "MEETING" -> new ActivitySpec(0.92, 0.12, 0.20, -0.08);
            case "STUDY" -> new ActivitySpec(0.80, 0.25, 0.25, 0.02);
            case "COMMUTE" -> new ActivitySpec(0.45, 0.55, 0.70, 0.0);
            case "MEAL", "EATING", "LUNCH" -> new ActivitySpec(0.30, 0.65, 0.60, 0.06);
            case "SHOWER" -> new ActivitySpec(0.90, 0.02, 0.0, 0.01);
            case "EXERCISE" -> new ActivitySpec(0.60, 0.35, 0.15, 0.10);
            case "SHOPPING" -> new ActivitySpec(0.40, 0.60, 0.60, 0.05);
            case "ENTERTAINMENT", "LEISURE", "FREE_TIME" -> new ActivitySpec(0.20, 0.80, 0.85, 0.08);
            case "SOCIAL" -> new ActivitySpec(0.55, 0.45, 0.40, 0.12);
            case "REST", "EVENING", "LATE_NIGHT" -> new ActivitySpec(0.35, 0.60, 0.70, 0.03);
            case "PHONE_BROWSING" -> new ActivitySpec(0.15, 0.90, 1.0, 0.04);
            case "HOUSEWORK" -> new ActivitySpec(0.40, 0.65, 0.50, 0.01);
            case "MEETING_BREAK" -> new ActivitySpec(0.25, 0.80, 0.80, 0.02);
            default -> new ActivitySpec(0.5, 0.5, 0.6, 0);
        };
    }

    /** 活动类型对应的中文名(注入 Prompt) */
    public String titleFor(String type) {
        if (type == null) return "日常";
        return switch (type) {
            case "SLEEP" -> "睡觉";
            case "WAKE_UP", "MORNING" -> "刚起床收拾";
            case "WORK", "WORK_BUSY", "WORK_AFTERNOON" -> "工作";
            case "MEETING" -> "开会";
            case "STUDY" -> "学习";
            case "COMMUTE" -> "通勤路上";
            case "MEAL", "EATING", "LUNCH" -> "吃饭";
            case "SHOWER" -> "洗澡";
            case "EXERCISE" -> "运动";
            case "SHOPPING" -> "逛街";
            case "ENTERTAINMENT", "LEISURE", "FREE_TIME" -> "休闲";
            case "SOCIAL" -> "和朋友在一起";
            case "REST", "EVENING", "LATE_NIGHT" -> "休息";
            case "PHONE_BROWSING" -> "刷手机";
            case "HOUSEWORK" -> "做家务";
            default -> "日常";
        };
    }
}
