package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.springframework.stereotype.Component;

/**
 * LifeEventStrategy: 生活事件感知。
 * 与自己直接相关的事件(活动开始/结束/计划)天然高感知 —— 真人对自己生活的变化很敏感。
 */
@Component
public class LifeEventStrategy implements PerceptionStrategy {

    @Override
    public ExternalEventType eventType() {
        return ExternalEventType.LIFE_EVENT;
    }

    @Override
    public double evaluate(PerceptionContext context) {
        // 生活事件与自己直接相关: 基础感知高; 深度沉浸/嘈杂会略降
        double score = 0.75;
        if (context.mind() != null && context.mind().deeplyImmersed()) {
            score -= 0.15;
        }
        if (context.environment() != null && context.environment().noisy()) {
            score -= 0.1;
        }
        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
