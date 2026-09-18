package com.luxera.companion.cognition;

import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.state.CompanionAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §11.3 / Phase 4 验收 —— <b>她的行动空间</b>。
 *
 * <p>本文件钉的是文档 §4 里那句验收:"busy 时收到消息 → DEFER/IGNORE 且不回复;
 * 到点唤醒 → READ → REPLY"。
 *
 * <p>它是纯函数测试, 所以没有 mock、没有 Spring —— 每个用例只回答
 * "给这些事实, 她做什么"。这不是为了跑得快, 而是因为决策是 Phase 4 里唯一一处
 * 会直接改变 53 个 agent 行为的新逻辑, 它必须能被穷举。
 */
class MindDecisionPlannerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 14, 0);

    private MindDecisionPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new MindDecisionPlanner(new CognitiveWakeupService());
    }

    // ─────────────────── 构造输入的小工具 ───────────────────

    /**
     * 注意力: {@code inspect} 决定"她会不会真去拿手机"。
     *
     * <p>阈值是硬编码在 planner 里的 0.3(注意)与 0.45(打开), 这里刻意用<b>阈值两侧</b>
     * 的值而不是 0/1 —— 用 0 和 1 的用例即使阈值被改成 0.9 也照样通过,
     * 那样的测试测的是"这个方法被调用过", 不是"分界线在哪"。
     */
    private AttentionService.Attention attention(double notice, double inspect) {
        return new AttentionService.Attention(0.5, 0.5, notice, inspect, 1200L);
    }

    private MindDecisionPlanner.DecisionInput input(CognitiveWakeupService.WakeLevel wake,
                                                    AttentionService.Attention att,
                                                    CompanionAvailability availability) {
        return MindDecisionPlanner.DecisionInput.of(wake, att, availability, false, false, 0.0);
    }

    private DecisionType typeOf(MindDecisionPlanner.DecisionInput in) {
        return planner.decide(in, T0).type();
    }

    // ─────────────────── 验收: busy ───────────────────

    @Nested
    @DisplayName("验收: 在忙时收到消息 → 不回, 且留下可复查的痕迹")
    class Busy {

        @Test
        void busyWithoutUrgencyDefersInsteadOfReplying() {
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.8, 0.9),
                    CompanionAvailability.BUSY, false, false, 0.0);

            CognitiveDecision d = planner.decide(in, T0);

            assertEquals(DecisionType.DEFER, d.type());
            assertFalse(d.type().producesOutboundMessage(),
                    "DEFER 的定义就是'不写消息出去' —— 它一旦能写, 切流后她会一边押后一边回, 那不是押后");
            assertEquals("busy_busy", d.reason(), "理由必须带上具体是哪种忙, 否则日志里只剩一个 DEFER");
            assertFalse(d.needsWakeup(), "DEFER 必须给出复查时刻 —— 没有复查时刻的押后与'永远不回'无法区分");
            assertEquals(T0.plusMinutes(MindDecisionPlanner.DEFAULT_DEFER_MINUTES), d.nextWakeupAt());
        }

        @Test
        void beingUrgedOverridesBusy() {
            // V10 的既有语义: CompanionAvailability 的注释写着"Busy ≠ 不回复"。
            // 一个只因为"在忙"就再也不回消息的数字人不是更像人, 是更像坏掉的客服。
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.8, 0.9),
                    CompanionAvailability.BUSY, false, /* urged */ true, 0.0);

            assertEquals(DecisionType.REPLY, typeOf(in), "被连发催问时, 忙也要回");
        }

        @Test
        void strongEmotionOverridesBusy() {
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.8, 0.9),
                    CompanionAvailability.BUSY, false, false, /* 情绪强烈 */ 0.8);

            assertEquals(DecisionType.REPLY, typeOf(in), "对方情绪很重时, 忙不是不回的理由");
        }

        @Test
        void restingIsNotBusy() {
            // 这一条很容易写反, 而且写反了很难发现: 表面上是"她在休息别打扰",
            // 实际效果是"她闲下来的时候谁也不理"
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.8, 0.9),
                    CompanionAvailability.RESTING, false, false, 0.0);

            assertEquals(DecisionType.REPLY, typeOf(in));
        }
    }

    // ─────────────────── 验收: 唤醒 → 读 → 回 ───────────────────

    @Nested
    @DisplayName("验收: 到点唤醒 → READ → REPLY")
    class Waking {

        @Test
        void awakeAvailableAndLookingMeansReply() {
            var in = input(CognitiveWakeupService.WakeLevel.DELIBERATION,
                    attention(0.9, 0.9), CompanionAvailability.AVAILABLE);
            assertEquals(DecisionType.REPLY, typeOf(in));
        }

        @Test
        void noticedButNotPickingUpThePhoneMeansReadWithoutReply() {
            // 她瞥见了通知, 没点开 —— 对外就是一个"看到了但不回"
            var in = input(CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.9, /* 不打开 */ 0.2), CompanionAvailability.AVAILABLE);

            CognitiveDecision d = planner.decide(in, T0);
            assertEquals(DecisionType.OBSERVE, d.type());
            assertFalse(d.type().producesOutboundMessage());
        }

        @Test
        void sleepingIsWokenByADeepThinkingMessage() {
            var in = input(CognitiveWakeupService.WakeLevel.DEEP_THINKING,
                    attention(0.9, 0.9), CompanionAvailability.SLEEPING);
            assertEquals(DecisionType.REPLY, typeOf(in),
                    "深夜的一条重消息(被裁员/关系冲突)是能把人吵醒的 —— 这是 V10 既有的 forceNoticed 语义");
        }

        @Test
        void sleepingAgentWaitsUntilMorning() {
            LocalDateTime night = LocalDateTime.of(2026, 9, 18, 23, 30);
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.9, 0.9), CompanionAvailability.SLEEPING, /* sleeping */ true, false, 0.0);

            CognitiveDecision d = planner.decide(in, night);

            assertEquals(DecisionType.WAIT, d.type());
            assertEquals(LocalDateTime.of(2026, 9, 19, 8, 0), d.nextWakeupAt(),
                    "是'等她醒过来'而不是'八小时后再看' —— 深夜两点 + 8 小时 = 上午十点, 而她十点可能还在睡");
        }

        @Test
        void earlyMorningWaitLandsOnTheSameMorning() {
            LocalDateTime dawn = LocalDateTime.of(2026, 9, 18, 5, 30);
            var in = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.ATTENTION,
                    attention(0.9, 0.9), CompanionAvailability.SLEEPING, true, false, 0.0);

            assertEquals(LocalDateTime.of(2026, 9, 18, 8, 0), planner.decide(in, dawn).nextWakeupAt(),
                    "凌晨五点半说'等早上'指的是今天早上 —— 推到明天等于让她多睡一整天");
        }

        @Test
        void wokenUpButNotYetLookingMeansReadFirst() {
            // 睡着 + 被重要消息吵醒 + 还没拿手机。这里是 WAIT 与 READ_MESSAGES 的分界:
            // 前者是"她不在场", 后者是"她醒了, 正准备看" —— 两者都不回消息,
            // 但前者不该去读(读了也没意义), 后者必须读(读了她才知道要不要回)
            var sleepingIn = MindDecisionPlanner.DecisionInput.of(
                    CognitiveWakeupService.WakeLevel.DELIBERATION,
                    attention(0.9, /* 还没拿手机 */ 0.2), CompanionAvailability.SLEEPING,
                    true, false, 0.0);

            assertEquals(DecisionType.READ_MESSAGES, typeOf(sleepingIn),
                    "V11 里'读'是一个独立动作 —— 老链把它和感知揉在一起, 于是无法表达'醒了但还没看'");
        }
    }

    // ─────────────────── 不该被打断的场合 ───────────────────

    @Nested
    @DisplayName("不该被打断的场合: 安静, 但每一种安静的账要能分开算")
    class Quiet {

        @Test
        void belowTheWakeThresholdIsDoNothing() {
            var in = input(CognitiveWakeupService.WakeLevel.MICRO_WAKE,
                    attention(0.9, 0.9), CompanionAvailability.AVAILABLE);
            assertEquals(DecisionType.DO_NOTHING, typeOf(in));
        }

        @Test
        void neverNoticedIsDoNothingNotObserve() {
            // 这两者的区别是整个 V11 的起点: "她无视了你" 与 "她根本不知道" 在现象上一样,
            // 在语义上完全不同。V11 之前的系统答不上来是哪一个。
            var in = input(CognitiveWakeupService.WakeLevel.DELIBERATION,
                    attention(0.1, 0.9), CompanionAvailability.AVAILABLE);

            CognitiveDecision d = planner.decide(in, T0);
            assertEquals(DecisionType.DO_NOTHING, d.type());
            assertEquals("not_noticed", d.reason());
        }

        @Test
        void pausedAgentIsDoNothingWithItsOwnReason() {
            // 顺序很重要: 暂停要排在"忙"之前。反过来的话, 一个被暂停的 agent 会被判定为
            // "忙, 押后" —— 于是日志里全是 DEFER, 而真相是它根本不在
            var in = new MindDecisionPlanner.DecisionInput(
                    CognitiveWakeupService.WakeLevel.DEEP_THINKING,
                    attention(0.9, 0.9), CompanionAvailability.AVAILABLE, false, false, 0.0, true);

            CognitiveDecision d = planner.decide(in, T0);
            assertEquals(DecisionType.DO_NOTHING, d.type());
            assertEquals("agent_paused", d.reason());
        }

        @Test
        void nullAttentionCountsAsNotNoticed() {
            var in = input(CognitiveWakeupService.WakeLevel.ATTENTION, null, CompanionAvailability.AVAILABLE);
            assertEquals("not_noticed", planner.decide(in, T0).reason());
        }

        @Test
        void nullInputIsHandled() {
            assertDoesNotThrow(() -> planner.decide(null, T0));
            assertEquals(DecisionType.DO_NOTHING, planner.decide(null, T0).type());
        }
    }

    // ─────────────────── 词表本身的不变量 ───────────────────

    @Nested
    @DisplayName("行动空间: 会写消息出去的只有两个")
    class Vocabulary {

        @Test
        void onlyReplyAndInitiateWriteMessages() {
            for (DecisionType t : DecisionType.values()) {
                boolean expected = t == DecisionType.REPLY || t == DecisionType.INITIATE_CONVERSATION;
                assertEquals(expected, t.producesOutboundMessage(), t + " 的 outbound 判定错了");
            }
        }

        @Test
        void observeIsNotActive() {
            // 从外面看, "看到了不想理" 与 "没看到" 都是"她什么都没做"。
            // 混进活跃度只会让那个指标失去意义
            assertFalse(DecisionType.OBSERVE.isActive());
            assertFalse(DecisionType.DO_NOTHING.isActive());
            assertTrue(DecisionType.REPLY.isActive());
        }

        @Test
        void deferAndWaitBothNeedFollowUp() {
            assertTrue(DecisionType.DEFER.needsFollowUp());
            assertTrue(DecisionType.WAIT.needsFollowUp());
            assertFalse(DecisionType.REPLY.needsFollowUp(),
                    "回了就了结了 —— 让它也需要复查会让同一句话被回两次");
        }

        @Test
        void everyDecisionThePlannerCanProduceIsInTheVocabulary() {
            // 穷举 planner 的输入空间, 断言产出的类型都在词表里且理由非空。
            // 这条看着像废话, 但它挡的是"新加了一个分支却返回了一个没人处理的类型" ——
            // 那种错在运行时会表现成 applyNonReplyDecision 落到 default 分支静默处理
            for (CompanionAvailability a : CompanionAvailability.values()) {
                for (CognitiveWakeupService.WakeLevel w : CognitiveWakeupService.WakeLevel.values()) {
                    for (boolean urged : new boolean[]{false, true}) {
                        for (double att : new double[]{0.1, 0.9}) {
                            var in = MindDecisionPlanner.DecisionInput.of(w, attention(att, att), a,
                                    false, urged, 0.0);
                            CognitiveDecision d = planner.decide(in, T0);
                            assertNotNull(d.type());
                            assertFalse(d.reason().isBlank(), "理由不能为空: " + d);
                        }
                    }
                }
            }
        }
    }
}
