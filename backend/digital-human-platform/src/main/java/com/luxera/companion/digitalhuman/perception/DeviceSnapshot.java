package com.luxera.companion.digitalhuman.perception;

/**
 * V10 §10.2 DeviceSnapshot: 数字人设备状态快照(轻量值对象)。
 * notificationMode: sound/vibrate/silent/dnd; distanceFromPerson: 0(在手边)~1(其他房间)。
 */
public record DeviceSnapshot(
        String notificationMode,
        boolean doNotDisturb,
        double distanceFromPerson,
        String phoneLocation
) {

    public static DeviceSnapshot of(String notificationMode, boolean doNotDisturb,
                                    double distanceFromPerson, String phoneLocation) {
        return new DeviceSnapshot(notificationMode, doNotDisturb, distanceFromPerson, phoneLocation);
    }

    /** 声音开启(最强触达) */
    public boolean soundEnabled() {
        return "sound".equals(notificationMode);
    }

    /** 手机是否在手边 */
    public boolean inHand() {
        return "hand".equals(phoneLocation);
    }
}
