package com.luxera.companion.runtime.agent.brain;

import com.luxera.companion.behavior.Drives;
import com.luxera.companion.interaction.InteractionDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §44 Decision Validator 测试:
 * - 睡眠时不应回复 → READ_NO_REPLY
 * - 忙(开会)时不应立即回复 → READ_NO_REPLY
 * - 手机不在身边不能 CHECK_PHONE_FIRST → READ_NO_REPLY
 * - 完全没注意到消息不应回复 → IGNORE
 * - 冲突中情绪激动不应 END_CONVERSATION → READ_NO_REPLY
 * - 一致决策返回 ok
 */
class DecisionValidatorTest {

    private final DecisionValidator validator = new DecisionValidator();

    private static BrainContext ctx(String activity, String availability, boolean phoneNearby,
                                    double notice, double hurt, double anger) {
        return new BrainContext(
                "c1", "u1", "m1", "消息", List.of("用户: hi"),
                activity, availability, 0.6, 0.3, 0.6, hurt, anger, 0, 0, 0.1,
                "平静", notice, 0.5, phoneNearby, "vibrate",
                "close", 0.6, null, null, null, false, false, null);
    }

    @Test
    void wokenUpShouldNotIgnore() {
        // 被重要消息吵醒(activityDesc 含"吵醒" + DISTRACTED) → IGNORE/READ_NO_REPLY 修正为 SHORT_ACK
        var r1 = validator.validate(BrainDecision.IGNORE, ctx("刚被消息吵醒,还没完全清醒", "DISTRACTED",
                false, 0.2, 0.1, 0.1));
        assertFalse(r1.valid());
        assertEquals(BrainDecision.SHORT_ACK, r1.correctedAction());
        var r2 = validator.validate(BrainDecision.READ_NO_REPLY, ctx("刚被消息吵醒,还没完全清醒", "DISTRACTED",
                false, 0.2, 0.1, 0.1));
        assertEquals(BrainDecision.SHORT_ACK, r2.correctedAction());
        // 未被吵醒时不受此约束
        var r3 = validator.validate(BrainDecision.IGNORE, ctx("在悠闲地享受自己的时间", "DISTRACTED",
                false, 0.2, 0.1, 0.1));
        assertTrue(r3.valid());
    }

    @Test
    void wokenUpForcesShortAckEvenWhenPhoneFar() {
        // 被吵醒 + 手机不在身边: CHECK_PHONE_FIRST 本会被约束3 修正为 READ_NO_REPLY,
        // 但约束6 必须强制为 SHORT_ACK(被吵醒优先)
        var r = validator.validate(BrainDecision.CHECK_PHONE_FIRST, ctx("刚被消息吵醒,还没完全清醒", "DISTRACTED",
                false, 0.2, 0.1, 0.1));
        assertFalse(r.valid());
        assertEquals(BrainDecision.SHORT_ACK, r.correctedAction(), "被吵醒时手机远也必须回应");
    }

    @Test
    void consistentDecisionIsOk() {
        var r = validator.validate(BrainDecision.REPLY, ctx("在悠闲地享受自己的时间", "LEISURE",
                true, 0.8, 0.1, 0.1));
        assertTrue(r.valid());
        assertEquals("决策一致", r.reason());
    }

    @Test
    void sleepingShouldNotReply() {
        var r = validator.validate(BrainDecision.REPLY, ctx("正在休息", "SLEEP",
                false, 0.9, 0.1, 0.1));
        assertFalse(r.valid());
        assertEquals(BrainDecision.READ_NO_REPLY, r.correctedAction());
    }

    @Test
    void busyMeetingShouldNotReply() {
        var r = validator.validate(BrainDecision.REPLY, ctx("正在开会", "WORK_BUSY",
                true, 0.7, 0.1, 0.1));
        assertFalse(r.valid());
        assertEquals(BrainDecision.READ_NO_REPLY, r.correctedAction());
    }

    @Test
    void noPhoneCannotCheckPhoneFirst() {
        var r = validator.validate(BrainDecision.CHECK_PHONE_FIRST, ctx("休闲", "LEISURE",
                false, 0.7, 0.1, 0.1));
        assertFalse(r.valid());
        assertEquals(BrainDecision.READ_NO_REPLY, r.correctedAction());
    }

    @Test
    void notNoticedShouldNotReply() {
        var r = validator.validate(BrainDecision.REPLY, ctx("休闲", "LEISURE",
                true, 0.05, 0.1, 0.1));
        assertFalse(r.valid());
        assertEquals(BrainDecision.IGNORE, r.correctedAction());
    }

    @Test
    void conflictShouldNotEndConversation() {
        var r = validator.validate(BrainDecision.END_CONVERSATION, ctx("休闲", "LEISURE",
                true, 0.8, 0.5, 0.5));
        assertFalse(r.valid());
        assertEquals(BrainDecision.READ_NO_REPLY, r.correctedAction());
    }
}
