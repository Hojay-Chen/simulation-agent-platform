package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.perception.LifeSnapshot;
import com.luxera.companion.digitalhuman.perception.MindSnapshot;
import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §13 Decision Policy 测试:
 * 验收: Agent 可以决定忽略、查看、立即回复或稍后回复(方案 Phase 5 验收)。
 */
class DecisionPolicyEngineTest {

    private final DecisionPolicyEngine engine = new DecisionPolicyEngine(List.of(
            new IgnorePolicy(), new ReplyLaterPolicy(), new ReplyNowPolicy(),
            new ChangeActivityPolicy(), new InspectDevicePolicy()));

    private DecisionContext ctx(PerceptionLevel level, double importance,
                                LifeSnapshot life, MindSnapshot mind, RelationshipSnapshot rel) {
        ExternalEvent event = ExternalEvent.of("p1", ExternalEventType.DEVICE_NOTIFICATION,
                Map.of("source", "chat-platform"));
        return DecisionContext.of("p1", event, level, importance, life, mind, rel);
    }

    private static LifeSnapshot leisure() {
        return LifeSnapshot.of("LEISURE", 0.25, false, "休闲");
    }

    private static LifeSnapshot busy() {
        return LifeSnapshot.of("WORK_BUSY", 0.8, false, "忙工作");
    }

    private static MindSnapshot fresh() {
        return MindSnapshot.of(0.5, 0.2, 0.8);
    }

    private static MindSnapshot tired() {
        return MindSnapshot.of(0.4, 0.1, 0.3);
    }

    // ── 忽略 ─────────────────────────────────

    @Test
    void nonePerceptionAlwaysIgnored() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.NONE, 0.9, leisure(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.IgnoreDecision, "没感知到 → 忽略(即使重要性高), 实际 " + d);
    }

    @Test
    void subconsciousWithLowImportanceIgnored() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.SUBCONSCIOUS, 0.3, leisure(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.IgnoreDecision, "潜意识+不重要 → 忽略");
    }

    @Test
    void trivialAndExhaustedIgnored() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.AWARE, 0.2, leisure(), tired(), null)).decision();
        assertTrue(d instanceof PersonDecision.IgnoreDecision, "琐碎+疲惫 → 懒得理");
    }

    // ── 立即回复 ─────────────────────────────

    @Test
    void focusedAndImportantRepliesNow() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.FOCUSED, 0.7, leisure(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.ReplyDecision, "FOCUSED+重要 → 立即回, 实际 " + d);
    }

    @Test
    void focusedIntimatePressuredRepliesNow() {
        RelationshipSnapshot rel = RelationshipSnapshot.of("deeply_connected", 0.8, 0.6, 0.1, 0.7);
        PersonDecision d = engine.decide(ctx(PerceptionLevel.FOCUSED, 0.45, leisure(), fresh(), rel)).decision();
        assertTrue(d instanceof PersonDecision.ReplyDecision, "亲密+联系压力 → 立即回");
    }

    // ── 延迟回复 ─────────────────────────────

    @Test
    void focusedButBusyDefers() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.FOCUSED, 0.6, busy(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.DelayReplyDecision, "FOCUSED+忙 → 稍后回, 实际 " + d);
        PersonDecision.DelayReplyDecision delay = (PersonDecision.DelayReplyDecision) d;
        assertTrue(delay.delayMinutes() > 0 && delay.delayMinutes() <= 120);
    }

    @Test
    void focusedButExhaustedDefers() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.FOCUSED, 0.5, leisure(), tired(), null)).decision();
        assertTrue(d instanceof PersonDecision.DelayReplyDecision, "FOCUSED+疲惫 → 稍后回, 实际 " + d);
    }

    // ── 查看设备 ─────────────────────────────

    @Test
    void awareWithDecentImportanceInspects() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.AWARE, 0.5, leisure(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.InspectDeviceDecision, "AWARE+可看性 → 先查看, 实际 " + d);
    }

    @Test
    void focusedNormalImportanceInspects() {
        PersonDecision d = engine.decide(ctx(PerceptionLevel.FOCUSED, 0.35, leisure(), fresh(), null)).decision();
        assertTrue(d instanceof PersonDecision.InspectDeviceDecision, "FOCUSED+普通重要性 → 先查看再说");
    }

    // ── 生活事件 ─────────────────────────────

    @Test
    void lifeEventChangesActivity() {
        ExternalEvent lifeEvent = ExternalEvent.of("p1", ExternalEventType.LIFE_EVENT,
                Map.of("source", "life", "activity", "LUNCH"));
        DecisionContext c = DecisionContext.of("p1", lifeEvent, PerceptionLevel.AWARE, 0.6,
                leisure(), fresh(), null);
        PersonDecision d = engine.decide(c).decision();
        assertTrue(d instanceof PersonDecision.ChangeActivityDecision, "生活事件 → 调整活动, 实际 " + d);
    }

    // ── 回退 ─────────────────────────────────

    @Test
    void fallbackIsConservativeIgnore() {
        // SUBCONSCIOUS + 中重要性 → 无策略匹配(重要性 0.5 不满足 Ignore<0.5? 边界)
        PersonDecision d = engine.decide(ctx(PerceptionLevel.SUBCONSCIOUS, 0.5, leisure(), fresh(), null)).decision();
        assertEquals("fallback", engine.decide(ctx(PerceptionLevel.SUBCONSCIOUS, 0.5, leisure(), fresh(), null)).policy());
        assertTrue(d instanceof PersonDecision.IgnoreDecision, "回退必须是保守忽略");
    }
}
