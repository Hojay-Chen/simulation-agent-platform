package com.luxera.companion.digitalhuman.decision;

/**
 * RelationshipSnapshot: 关系状态快照(轻量值对象, 决策输入之一)。
 * 由 SnapshotFactory 或调用方从 Relationship 提取。
 */
public record RelationshipSnapshot(
        String stage,
        double intimacy,
        double familiarity,
        double tension,
        double connectionPressure
) {

    public static RelationshipSnapshot of(String stage, double intimacy, double familiarity,
                                          double tension, double connectionPressure) {
        return new RelationshipSnapshot(stage, intimacy, familiarity, tension, connectionPressure);
    }

    /** 亲密关系(信任度高, 消息更值得回应) */
    public boolean intimate() {
        return "close".equals(stage) || "deeply_connected".equals(stage);
    }

    /** 联系压力高(沉默越久越想回) */
    public boolean pressured() {
        return connectionPressure >= 0.6;
    }
}
