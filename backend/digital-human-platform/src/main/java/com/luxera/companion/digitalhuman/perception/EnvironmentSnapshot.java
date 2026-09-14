package com.luxera.companion.digitalhuman.perception;

/**
 * V10 §10.2 EnvironmentSnapshot: 环境状态快照(轻量值对象)。
 * noiseLevel: 环境噪声 0(安静)~1(嘈杂)。
 */
public record EnvironmentSnapshot(double noiseLevel) {

    public static EnvironmentSnapshot of(double noiseLevel) {
        return new EnvironmentSnapshot(noiseLevel);
    }

    /** 是否嘈杂(会显著降低对通知的感知) */
    public boolean noisy() {
        return noiseLevel >= 0.6;
    }
}
