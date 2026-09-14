package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEvent;

/**
 * V10 §10.2 PerceptionContext: 感知输入 —— 事件 + 数字人全维度状态。
 *
 * 感知是一个"过滤函数": 同一个外部事件, 在不同的生活/心理/设备/环境状态下,
 * 感知等级完全不同(方案 Phase 4 验收: 同一消息在不同环境下可能被感知或完全不知道)。
 */
public record PerceptionContext(
        ExternalEvent event,
        LifeSnapshot life,
        MindSnapshot mind,
        DeviceSnapshot device,
        EnvironmentSnapshot environment
) {

    public static PerceptionContext of(ExternalEvent event, LifeSnapshot life, MindSnapshot mind,
                                       DeviceSnapshot device, EnvironmentSnapshot environment) {
        return new PerceptionContext(event, life, mind, device, environment);
    }
}
