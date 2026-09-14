package com.luxera.companion.behavior;

/**
 * 行为倾向(§十五): 不是最终行为, 而是多个 drive 的强度。
 * 最终行为由 DrivesService 竞争 + Behavior Engine 评分产生。
 */
public record Drives(
        double desireToReply,
        double desireToAvoid,
        double desireToShare,
        double desireToReconnect,
        double desireToRest) {

    public static Drives neutral() {
        return new Drives(0.3, 0.1, 0.1, 0.1, 0.2);
    }
}
