package com.luxera.companion.cognition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §12.1 —— <b>一次认知的结论</b>。
 *
 * <p>只测两件事: 构造时挡住的三件事, 以及"以后再说"类决策必须带复查时刻这条不变量。
 *
 * <p>这里原本还有一整节"老链 → 新词表"的适配器测试, Phase 6 随适配器一起删了。
 * 那四个适配器没有任何 src/main 调用者, 而它们注释里声称的用途(shadow 期差异率)
 * 实际由 {@code CognitionDecisionRecorder.classify} 用<b>行为</b>对照完成 ——
 * 比的是"会不会写一条消息出去", 不经过词表映射。理由写全在
 * {@code CognitiveDecision} 类文件的末尾。
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
}
