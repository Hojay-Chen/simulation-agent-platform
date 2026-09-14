package com.luxera.companion.digitalhuman.perception;

/**
 * V10 §11 MindSnapshot: 数字人当前心理状态快照(轻量值对象)。
 * focus: 当前注意力占用(0~1); arousal: 情绪唤醒度(0~1); energy: 精力(0~1)。
 */
public record MindSnapshot(
        double focus,
        double arousal,
        double energy
) {

    public static MindSnapshot of(double focus, double arousal, double energy) {
        return new MindSnapshot(focus, arousal, energy);
    }

    /** 是否处于深度沉浸(注意力被当前活动完全占用) */
    public boolean deeplyImmersed() {
        return focus >= 0.75;
    }

    /** 是否疲惫(影响是否延迟处理) */
    public boolean exhausted() {
        return energy < 0.4;
    }
}
