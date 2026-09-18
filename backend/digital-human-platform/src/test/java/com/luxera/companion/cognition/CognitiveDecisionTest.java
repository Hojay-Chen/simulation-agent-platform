package com.luxera.companion.cognition;

import com.luxera.companion.digitalhuman.decision.PersonDecision;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.pipeline.MessagePipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §12.1 —— <b>一次认知的结论</b>, 以及"老链走向 → 新词表"的单向适配。
 *
 * <p>适配器这一段值得单独测, 因为它是 Phase 4 能并跑的前提: shadow 期的差异率
 * 就是靠它把老链的 {@code Outcome} 读成新词表算出来的。适配器错一格,
 * 差异率就整体偏掉, 而那个数字正是用来决定要不要切流的。
 */
class CognitiveDecisionTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 14, 0);

    // ─────────────────── 不变量 ───────────────────

    @Nested
    @DisplayName("构造时挡住的三件事")
    class Invariants {

        @Test
        void aDecisionWithoutATypeIsRejected() {
            // 没有类型的决策不是"默认", 是"不知道" —— 一旦允许 null,
            // 它就会一路漂到日志里变成一个空的 decision 字段
            assertThrows(IllegalArgumentException.class,
                    () -> new CognitiveDecision(null, "whatever", null, 1.0));
        }

        @Test
        void aDecisionWithoutAReasonIsRejected() {
            // 理由为空时, 日志里只剩一个动词, 无法归因 —— 而"归因"正是引入这个类的全部理由
            assertThrows(IllegalArgumentException.class,
                    () -> CognitiveDecision.of(DecisionType.DEFER, "  "));
        }

        @Test
        void confidenceIsClampedNotRejected() {
            // 越界的置信度是上游算错, 不是调用方写错 —— 夹住比抛异常好:
            // 抛异常会让一个"数字有点偏"变成"她不回消息了"
            assertEquals(1.0, new CognitiveDecision(DecisionType.REPLY, "ok", null, 7.0).confidence());
            assertEquals(0.0, new CognitiveDecision(DecisionType.REPLY, "ok", null, -3.0).confidence());
        }

        @Test
        void followUpDecisionsWithoutAWakeupTimeAreVisible() {
            assertTrue(CognitiveDecision.of(DecisionType.DEFER, "x").needsWakeup(),
                    "一个没有复查时刻的 DEFER, 与'永远不回'是同一件事 —— 它必须是可见的");
            assertFalse(CognitiveDecision.until(DecisionType.DEFER, "x", T0.plusHours(1)).needsWakeup());
            assertFalse(CognitiveDecision.of(DecisionType.REPLY, "x").needsWakeup(),
                    "REPLY 不是 follow-up 类, 它不需要复查时刻");
        }
    }

    // ─────────────────── 老链 → 新词表 ───────────────────

    @Nested
    @DisplayName("适配器: 老链的走向读成新词表(单向, 有损)")
    class Adapters {

        @Test
        void theFourPipelineOutcomesMapToFourDifferentThings() {
            assertEquals(DecisionType.DO_NOTHING,
                    CognitiveDecision.from(MessagePipeline.PipelineResult.Outcome.IGNORE_NOT_NOTICED, null).type(),
                    "IGNORE_NOT_NOTICED 是'她根本不知道'");
            assertEquals(DecisionType.OBSERVE,
                    CognitiveDecision.from(MessagePipeline.PipelineResult.Outcome.IGNORE, null).type(),
                    "IGNORE 是'她知道, 选择不理' —— 这两个在老链里只差一个单词, 在新词表里是两件事");
            assertEquals(DecisionType.DEFER,
                    CognitiveDecision.from(MessagePipeline.PipelineResult.Outcome.DEFERRED, null).type());
            assertEquals(DecisionType.REPLY,
                    CognitiveDecision.from(MessagePipeline.PipelineResult.Outcome.REPLY, null).type());
        }

        @Test
        void nullOutcomeDoesNotBlowUp() {
            // 适配器是并跑期的观察设施: 它自己炸掉会把被观察的主链一起带走
            assertDoesNotThrow(() -> CognitiveDecision.from((MessagePipeline.PipelineResult.Outcome) null, null));
            assertEquals(DecisionType.DO_NOTHING,
                    CognitiveDecision.from((MessagePipeline.PipelineResult.Outcome) null, null).type());
        }

        @Test
        void brainActionsMapFaithfully() {
            assertEquals(DecisionType.REPLY, CognitiveDecision.fromBrain(BrainDecision.REPLY, null).type());
            assertEquals(DecisionType.REPLY, CognitiveDecision.fromBrain(BrainDecision.SHORT_ACK, null).type(),
                    "一句'嗯'也是一条发出去的消息 —— 按'会不会写消息'分类时它必须算 REPLY");
            assertEquals(DecisionType.READ_MESSAGES,
                    CognitiveDecision.fromBrain(BrainDecision.CHECK_PHONE_FIRST, null).type(),
                    "老链拿它当中间步骤, V11 词表里'读'本身就是一个动作");
            assertEquals(DecisionType.DEFER, CognitiveDecision.fromBrain(BrainDecision.READ_NO_REPLY, null).type());
            assertEquals(DecisionType.OBSERVE, CognitiveDecision.fromBrain(BrainDecision.IGNORE, null).type());
        }

        @Test
        void anUnknownBrainActionBecomesObserveNotACrash() {
            CognitiveDecision d = CognitiveDecision.fromBrain("SOMETHING_NEW", null);
            assertEquals(DecisionType.OBSERVE, d.type());
            assertTrue(d.reason().contains("SOMETHING_NEW"), "未知动作必须把原值带进理由, 否则无从查起");
        }

        @Test
        void delayReplyKeepsItsDelay() {
            var d = CognitiveDecision.from(new PersonDecision.DelayReplyDecision("在忙", 30), T0);
            assertEquals(DecisionType.DEFER, d.type());
            assertEquals(T0.plusMinutes(30), d.nextWakeupAt(),
                    "老决策里唯一自带时长的那一个 —— 丢掉它等于把'30 分钟后回'变成'永远不回'");
        }

        @Test
        void aDelayWithoutANowLeavesTheWakeupUnresolved() {
            // 与其编一个假时刻, 不如让 needsWakeup() 显形: 老链的 DEFER 到今天也没有
            // 接进 V11 的唤醒系统(Phase 5 的事)
            var d = CognitiveDecision.from(new PersonDecision.DelayReplyDecision("在忙", 30));
            assertTrue(d.needsWakeup());
        }

        @Test
        void everySealedPersonDecisionIsMapped() {
            // sealed 接口加了新实现而适配器没跟上时, 不能抛异常(那会带走主链),
            // 但必须留一个能被看出来的痕迹
            assertEquals(DecisionType.OBSERVE,
                    CognitiveDecision.from(new PersonDecision.IgnoreDecision("x"), T0).type());
            assertEquals(DecisionType.READ_MESSAGES,
                    CognitiveDecision.from(new PersonDecision.InspectDeviceDecision("x"), T0).type());
            assertEquals(DecisionType.REPLY,
                    CognitiveDecision.from(new PersonDecision.ReplyDecision("x"), T0).type());
            assertEquals(DecisionType.PERFORM_ACTION,
                    CognitiveDecision.from(new PersonDecision.ChangeActivityDecision("x", "sleep"), T0).type());
        }

        @Test
        void aNullPersonDecisionIsDoNothing() {
            assertEquals(DecisionType.DO_NOTHING, CognitiveDecision.from((PersonDecision) null, T0).type());
        }
    }
}
