package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.springframework.stereotype.Component;

/**
 * TimeEventStrategy: 时间事件感知(闹钟/排程提醒)。
 * 预排程的时间事件由她自己设定 → 通常高感知; 深夜/沉浸时略降。
 */
@Component
public class TimeEventStrategy implements PerceptionStrategy {

    @Override
    public ExternalEventType eventType() {
        return ExternalEventType.TIME_EVENT;
    }

    @Override
    public double evaluate(PerceptionContext context) {
        double score = 0.8;
        if (context.life() != null && context.life().sleeping()) {
            score = 0.5;   // 睡着时闹钟响会醒, 但感知模糊
        }
        if (context.mind() != null && context.mind().deeplyImmersed()) {
            score -= 0.1;
        }
        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
