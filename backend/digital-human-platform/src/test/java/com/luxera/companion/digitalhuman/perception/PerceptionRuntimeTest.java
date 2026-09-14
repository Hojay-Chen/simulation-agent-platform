package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §10 Perception Runtime 测试(规则 + 评分, 无 LLM):
 * 验收: 同一消息在不同环境下可能被感知或完全不知道(方案 Phase 4 验收)。
 */
class PerceptionRuntimeTest {

    private final PerceptionRuntime runtime = new PerceptionRuntime(List.of(
            new MessageNotificationStrategy(), new LifeEventStrategy(), new TimeEventStrategy()));

    private ExternalEvent notificationEvent() {
        return ExternalEvent.of("p1", ExternalEventType.DEVICE_NOTIFICATION,
                Map.of("source", "chat-platform"));
    }

    private PerceptionContext ctx(LifeSnapshot life, MindSnapshot mind, DeviceSnapshot device) {
        return PerceptionContext.of(notificationEvent(), life, mind, device, EnvironmentSnapshot.of(0.2));
    }

    // ── 设备触达 ──────────────────────────────

    @Test
    void dndOrSleepingMeansNothingPerceived() {
        DeviceSnapshot dnd = DeviceSnapshot.of("sound", true, 0.1, "hand");
        PerceptionResult r = runtime.perceive(notificationEvent(),
                ctx(LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8), dnd));
        assertEquals(PerceptionLevel.NONE, r.level(), "勿扰 → 完全感知不到");

        DeviceSnapshot sound = DeviceSnapshot.of("sound", false, 0.1, "hand");
        PerceptionResult sleeping = runtime.perceive(notificationEvent(),
                ctx(LifeSnapshot.of("SLEEP", 0, true, "睡觉"), MindSnapshot.of(0.2, 0.1, 0.5), sound));
        assertEquals(PerceptionLevel.NONE, sleeping.level(), "睡着 → 完全感知不到");
    }

    @Test
    void soundInHandFocusedWhileBusy() {
        // 声音 + 手机在手 + 休闲活动 → FOCUSED
        DeviceSnapshot sound = DeviceSnapshot.of("sound", false, 0.1, "hand");
        PerceptionResult r = runtime.perceive(notificationEvent(),
                ctx(LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8), sound));
        assertTrue(r.level() == PerceptionLevel.FOCUSED || r.level() == PerceptionLevel.AWARE,
                "声音+在手 → 至少 AWARE, 实际 " + r.level());
    }

    @Test
    void silentAndFarMeansSubconsciousOrNone() {
        // 静音 + 手机在其他房间 + 嘈杂环境 → 感知极弱
        DeviceSnapshot silent = DeviceSnapshot.of("silent", false, 0.8, "other_room");
        PerceptionContext noisy = PerceptionContext.of(notificationEvent(),
                LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8),
                silent, EnvironmentSnapshot.of(0.8));
        PerceptionResult r = runtime.perceive(notificationEvent(), noisy);
        assertFalse(r.triggersCognition(), "静音+远+嘈杂 → 不应触发认知, 实际 " + r.level());
    }

    @Test
    void busyWorkReducesPerception() {
        // 工作忙 + 震动 → 感知降级(能感觉到但不会专注)
        DeviceSnapshot vibrate = DeviceSnapshot.of("vibrate", false, 0.1, "hand");
        PerceptionResult r = runtime.perceive(notificationEvent(),
                ctx(LifeSnapshot.of("WORK_BUSY", 0.8, false, "忙工作"), MindSnapshot.of(0.7, 0.3, 0.6), vibrate));
        assertTrue(r.level() == PerceptionLevel.SUBCONSCIOUS || r.level() == PerceptionLevel.AWARE,
                "忙工作时震动 → SUBCONSCIOUS~AWARE, 实际 " + r.level());
    }

    // ── 事件类型策略 ──────────────────────────

    @Test
    void lifeEventIsHighlyPerceived() {
        ExternalEvent lifeEvent = ExternalEvent.of("p1", ExternalEventType.LIFE_EVENT,
                Map.of("source", "life", "activity", "LUNCH"));
        PerceptionContext ctx = PerceptionContext.of(lifeEvent,
                LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8),
                DeviceSnapshot.of("vibrate", false, 0.1, "hand"), EnvironmentSnapshot.of(0.2));
        PerceptionResult r = runtime.perceive(lifeEvent, ctx);
        assertTrue(r.triggersCognition(), "生活事件 → 高感知, 实际 " + r.level());
        assertEquals("LifeEventStrategy", r.strategy());
    }

    @Test
    void timeEventStrategyUsed() {
        ExternalEvent timeEvent = ExternalEvent.of("p1", ExternalEventType.TIME_EVENT,
                Map.of("source", "scheduler"));
        PerceptionContext ctx = PerceptionContext.of(timeEvent,
                LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8),
                DeviceSnapshot.of("vibrate", false, 0.1, "hand"), EnvironmentSnapshot.of(0.2));
        PerceptionResult r = runtime.perceive(timeEvent, ctx);
        assertEquals("TimeEventStrategy", r.strategy());
        assertTrue(r.perceived());
    }

    @Test
    void unmappedEventTypeIsNone() {
        ExternalEvent appEvent = ExternalEvent.of("p1", ExternalEventType.APPLICATION_EVENT,
                Map.of("source", "app"));
        PerceptionContext ctx = PerceptionContext.of(appEvent,
                LifeSnapshot.of("LEISURE", 0.25, false, "休闲"), MindSnapshot.of(0.5, 0.2, 0.8),
                DeviceSnapshot.of("sound", false, 0.1, "hand"), EnvironmentSnapshot.of(0.2));
        assertEquals(PerceptionLevel.NONE, runtime.perceive(appEvent, ctx).level(),
                "未注册策略的事件类型 → NONE");
    }

    @Test
    void levelThresholdsMatchSpec() {
        // V10 §10.4: <0.2 NONE / 0.2~0.5 SUBCONSCIOUS / 0.5~0.8 AWARE / >0.8 FOCUSED
        assertEquals(PerceptionLevel.NONE, PerceptionRuntime.level(0.1));
        assertEquals(PerceptionLevel.SUBCONSCIOUS, PerceptionRuntime.level(0.35));
        assertEquals(PerceptionLevel.SUBCONSCIOUS, PerceptionRuntime.level(0.49));
        assertEquals(PerceptionLevel.AWARE, PerceptionRuntime.level(0.65));
        assertEquals(PerceptionLevel.AWARE, PerceptionRuntime.level(0.79));
        assertEquals(PerceptionLevel.FOCUSED, PerceptionRuntime.level(0.85));
        assertEquals(PerceptionLevel.FOCUSED, PerceptionRuntime.level(1.0));
    }
}
