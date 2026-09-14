package com.luxera.companion.digitalhuman.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §5.5 Emotion Continuity Filter: 情绪连贯性过滤。
 *
 * 真人情绪特征:
 * - 近 5 分钟情绪基调连贯(不会突然从开心跳到冷淡)
 * - 情绪变化有渐变过程(阈值: 强度差 > 0.4 视为突变)
 * - 在 ConversationRequest stablePrefix 注入情绪基调提示
 */
@Slf4j
@Component
public class EmotionContinuityFilter {

    private final Map<String, EmotionSnapshot> recentEmotions = new ConcurrentHashMap<>();

    /** 计算情绪连贯性提示(输入为简单原语, 便于单测) */
    public EmotionContinuityHint computeHint(String companionId, String currentEmotion,
                                             double currentIntensity) {
        EmotionSnapshot last = recentEmotions.get(companionId);
        LocalDateTime now = LocalDateTime.now();

        EmotionContinuityHint hint = new EmotionContinuityHint();
        if (last != null && last.timestamp().isAfter(now.minusMinutes(5))) {
            double intensityDiff = Math.abs(currentIntensity - last.intensity());
            if (intensityDiff > 0.4) {
                hint.setWarning(true);
                hint.setMessage("情绪连贯提示: 当前情绪(" + currentEmotion + " 强度 " +
                        String.format("%.2f", currentIntensity) +
                        ")与 5 分钟前(" + last.emotion() + " 强度 " +
                        String.format("%.2f", last.intensity()) + ")反差较大, 请微调表达, 避免突变。");
            }
            hint.setLastEmotion(last.emotion());
            hint.setLastIntensity(last.intensity());
            hint.setMinutesSinceLast(
                    (int) java.time.Duration.between(last.timestamp(), now).toMinutes());
        }

        // 更新缓存
        recentEmotions.put(companionId, new EmotionSnapshot(currentEmotion, currentIntensity, now));
        return hint;
    }

    public static class EmotionSnapshot {
        public String emotion;
        public double intensity;
        public java.time.LocalDateTime timestamp;

        public EmotionSnapshot(String emotion, double intensity, java.time.LocalDateTime timestamp) {
            this.emotion = emotion;
            this.intensity = intensity;
            this.timestamp = timestamp;
        }

        public String emotion() { return emotion; }
        public double intensity() { return intensity; }
        public java.time.LocalDateTime timestamp() { return timestamp; }
    }

    public static class EmotionContinuityHint {
        private boolean warning;
        private String message;
        private String lastEmotion;
        private double lastIntensity;
        private int minutesSinceLast;

        public boolean isWarning() { return warning; }
        public void setWarning(boolean warning) { this.warning = warning; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getLastEmotion() { return lastEmotion; }
        public void setLastEmotion(String lastEmotion) { this.lastEmotion = lastEmotion; }
        public double getLastIntensity() { return lastIntensity; }
        public void setLastIntensity(double lastIntensity) { this.lastIntensity = lastIntensity; }
        public int getMinutesSinceLast() { return minutesSinceLast; }
        public void setMinutesSinceLast(int minutesSinceLast) { this.minutesSinceLast = minutesSinceLast; }
    }
}