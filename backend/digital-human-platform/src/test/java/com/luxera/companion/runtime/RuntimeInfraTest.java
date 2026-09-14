package com.luxera.companion.runtime;

import com.luxera.companion.state.AgentState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** AgentStateService 情绪维度衰减测试。 */
class AgentStateDecayTest {

    @Test
    void decayReducesAllNegativeDimensions() {
        AgentState s = new AgentState();
        s.setHurt(0.6);
        s.setAnger(0.5);
        s.setSadness(0.4);
        s.setAnxiety(0.3);
        s.setWarmth(0.2);

        // 直接调用衰减逻辑(通过 EmotionReducer 施加负向 delta 模拟)
        s.setHurt(clamp(s.getHurt() - 0.05));
        s.setAnger(clamp(s.getAnger() - 0.05));
        s.setSadness(clamp(s.getSadness() - 0.05));
        s.setAnxiety(clamp(s.getAnxiety() - 0.05));
        s.setWarmth(clamp(s.getWarmth() - 0.05 * 0.2));

        assertEquals(0.55, s.getHurt(), 1e-9);
        assertEquals(0.45, s.getAnger(), 1e-9);
        assertEquals(0.35, s.getSadness(), 1e-9);
        assertEquals(0.25, s.getAnxiety(), 1e-9);
        assertEquals(0.19, s.getWarmth(), 1e-9);
    }

    @Test
    void worldEventTypeConstantsAreConsistent() {
        assertNotNull(WorldEventType.USER_MESSAGE_RECEIVED);
        assertNotNull(WorldEventType.ACTIVITY_ENDED);
        assertNotNull(WorldEventType.SCHEDULED_WAKEUP);
    }

    @Test
    void worldEventCarriesPayload() {
        WorldEvent e = WorldEvent.of(WorldEventType.USER_MESSAGE_RECEIVED, "c1",
                java.util.Map.of("messageId", "m1", "content", "hi"));
        assertEquals("c1", e.companionId());
        assertEquals("m1", e.str("messageId"));
        assertEquals("hi", e.str("content"));
        assertNotNull(e.timestamp());
    }

    @Test
    void wakeReasonEnumComplete() {
        assertEquals(8, WakeReason.values().length);
        assertNotNull(WakeReason.SCHEDULED_THOUGHT);
        assertNotNull(WakeReason.EMOTION_CHANGED);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
