package com.luxera.companion.digitalhuman.perception;

/**
 * V10 §10.2 LifeSnapshot: 数字人当前生活状态快照(轻量值对象)。
 * 由 SnapshotFactory 从现有运行时状态提取, 供感知与决策使用。
 */
public record LifeSnapshot(
        String activityType,
        double attentionDemand,
        boolean sleeping,
        String description
) {

    public static LifeSnapshot of(String activityType, double attentionDemand, boolean sleeping, String description) {
        return new LifeSnapshot(activityType, attentionDemand, sleeping, description);
    }

    public boolean isBusy() {
        return attentionDemand >= 0.6;
    }
}
