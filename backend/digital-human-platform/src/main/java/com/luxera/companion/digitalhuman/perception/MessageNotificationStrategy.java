package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.springframework.stereotype.Component;

/**
 * MessageNotificationStrategy: 消息通知感知(V10 §10.4 评分公式落地)。
 *
 * score = 0;
 *   if (soundEnabled)  score += soundScore;        // 声音是最强触达
 *   if (mode==vibrate) score += vibrateScore;
 *   if (device 在手边) score += proximityScore;
 *   if (noise > 0.6)   score -= noisePenalty;      // 环境嘈杂
 *   if (activity 高注意力) score -= attentionPenalty;  // 正在忙
 *   if (sleeping)      score = 0;                  // 睡着(除非被吵醒事件)
 *   if (dnd)           score = 0;                  // 勿扰
 *
 * 全程规则计算, 不调用 LLM。
 */
@Component
public class MessageNotificationStrategy implements PerceptionStrategy {

    /** 触达基础分 */
    private static final double DND_SCORE = 0.0;
    private static final double SILENT_SCORE = 0.15;
    private static final double VIBRATE_SCORE = 0.4;
    private static final double SOUND_SCORE = 0.65;

    /** 修正项 */
    private static final double PROXIMITY_IN_HAND = 0.2;      // 手机在手边
    private static final double PROXIMITY_FAR = -0.25;        // 在其他房间
    private static final double NOISE_PENALTY = 0.2;          // 环境嘈杂
    private static final double ATTENTION_PENALTY = 0.25;     // 活动需要高注意力
    private static final double IMMERSION_PENALTY = 0.2;      // 深度沉浸

    @Override
    public ExternalEventType eventType() {
        return ExternalEventType.DEVICE_NOTIFICATION;
    }

    @Override
    public double evaluate(PerceptionContext context) {
        DeviceSnapshot device = context.device();
        if (device == null) return 0.3;   // 无设备状态时保守估计
        if (device.doNotDisturb() || context.life() != null && context.life().sleeping()) {
            return DND_SCORE;
        }

        double score = switch (device.notificationMode() == null ? "vibrate" : device.notificationMode()) {
            case "sound" -> SOUND_SCORE;
            case "vibrate" -> VIBRATE_SCORE;
            case "silent" -> SILENT_SCORE;
            default -> VIBRATE_SCORE;
        };

        // 距离修正
        if (device.inHand()) {
            score += PROXIMITY_IN_HAND;
        } else if (device.distanceFromPerson() >= 0.7) {
            score += PROXIMITY_FAR;
        }

        // 环境噪声
        if (context.environment() != null && context.environment().noisy()) {
            score -= NOISE_PENALTY;
        }

        // 活动注意力
        if (context.life() != null) {
            if (context.life().isBusy()) {
                score -= ATTENTION_PENALTY;
            }
            if (context.life().sleeping()) {
                return DND_SCORE;
            }
        }

        // 心智沉浸
        if (context.mind() != null && context.mind().deeplyImmersed()) {
            score -= IMMERSION_PENALTY;
        }

        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
