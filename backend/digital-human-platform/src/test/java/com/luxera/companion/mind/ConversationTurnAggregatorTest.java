package com.luxera.companion.mind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §8 —— 回合聚合的<b>验收测试</b>。
 *
 * <p>本文件的第一条用例({@link Acceptance#threeMessagesInOneQuietWindowBecomeOneTurn})
 * 就是 Phase 3 的验收标准本身: 对方连发三句 —— "今天好累 / 老师讲得好快 / 我都没听懂" ——
 * 在 V10 里会变成三次完整的认知(三份情绪、三次唤醒、三句回复), 在 V11 里必须是
 * <b>一个</b>认知回合。
 *
 * <p>它能在毫秒内跑完, 而真实行为要等 4 秒 —— 因为整类没有一次 {@code sleep}、
 * 没有一次时钟读取: 所有判断都吃传进来的 {@code now}。这不是测试技巧, 是这个类的
 * 设计要求(见 {@code ConversationTurnAggregator} 的类注释): 一个必须靠真等才能验证的
 * 时序逻辑, 在 CI 上要么很慢, 要么很不稳。
 *
 * <p>窗口刻意<b>不</b>用出厂默认值来写用例, 而是显式构造(4 秒静默 / 45 秒硬上限 / 8 条),
 * 这样改默认值不会悄悄改变这些用例的含义。
 */
class ConversationTurnAggregatorTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";
    private static final String CONV2 = "conv-2";

    /** 全部用例的时间原点 —— 固定值, 不读时钟。 */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 10, 0, 0);

    private ConversationTurnAggregator agg;

    @BeforeEach
    void setUp() {
        agg = new ConversationTurnAggregator(4_000, 45_000, 8);
    }

    private ConversationTurnAggregator.Delivery delivery(String conv, LocalDateTime at, String... ids) {
        return new ConversationTurnAggregator.Delivery(AGENT, USER, conv, List.of(ids), at);
    }

    // ─────────────────────────── 验收 ───────────────────────────

    @Nested
    @DisplayName("验收: 一个静默窗口内的三句话 = 一个回合")
    class Acceptance {

        @Test
        void threeMessagesInOneQuietWindowBecomeOneTurn() {
            agg.accept(delivery(CONV, T0, "m1"));
            agg.accept(delivery(CONV, T0.plusSeconds(1), "m2"));
            agg.accept(delivery(CONV, T0.plusSeconds(2), "m3"));

            // 静默窗口还没走完(最后一条是 T0+2s, 窗口到 T0+6s)—— 谁都不该被封口
            assertTrue(agg.sealDue(T0.plusNanos(2_500_000_000L)).isEmpty(),
                    "静默窗口内的消息不该被提前封口, 否则对方话没说完她就开口了");
            assertEquals(1, agg.openTurns().size(), "三句话应该攒在同一个回合里");
            assertEquals(3, agg.openTurns().get(0).size());

            List<ConversationTurnAggregator.Turn> due = agg.sealDue(T0.plusSeconds(6));
            assertEquals(1, due.size(), "三句话必须并成 1 个认知回合, 而不是 3 个");
            assertEquals(List.of("m1", "m2", "m3"), due.get(0).messageIds(),
                    "回合里保留消息的原始顺序 —— 认知链要按对方说的顺序理解他");
            assertEquals(ConversationTurnAggregator.State.SEALED, due.get(0).state());
            assertFalse(due.get(0).forcedBySize());
            assertFalse(due.get(0).forcedByAge());

            ConversationTurnAggregator.Stats s = agg.stats();
            assertEquals(1, s.turnsOpened());
            assertEquals(1, s.turnsSealed());
            assertEquals(3, s.messagesAggregated());
            assertEquals(3.0, s.messagesPerTurn(), 0.001,
                    "messagesPerTurn 就是这个改动的全部收益, 它必须等于 3");
            assertEquals(0, s.openTurns());
        }

        @Test
        void threeMessagesTenMinutesApartBecomeThreeTurns() {
            // 同一个人的三句话, 但隔了十分钟 —— 那是三件事, 不是一次说完
            agg.accept(delivery(CONV, T0, "m1"));
            assertEquals(1, agg.sealDue(T0.plusSeconds(5)).size());

            agg.accept(delivery(CONV, T0.plusMinutes(10), "m2"));
            assertEquals(1, agg.sealDue(T0.plusMinutes(10).plusSeconds(5)).size());

            agg.accept(delivery(CONV, T0.plusMinutes(20), "m3"));
            assertEquals(1, agg.sealDue(T0.plusMinutes(20).plusSeconds(5)).size());

            assertEquals(3, agg.stats().turnsOpened());
            assertEquals(3, agg.stats().turnsSealed());
            assertEquals(1.0, agg.stats().messagesPerTurn(), 0.001,
                    "窗口是合并的<下界>而不是<上限>: 隔得远的话, 合并率就该回到 1");
        }

        @Test
        void sealedTurnLeavesTheMapSoTheNextMessageOpensANewOne() {
            agg.accept(delivery(CONV, T0, "m1"));
            agg.sealDue(T0.plusSeconds(5));

            ConversationTurnAggregator.Acceptance again = agg.accept(delivery(CONV, T0.plusSeconds(6), "m2"));
            assertTrue(again.openedNewTurn(), "封口之后再来消息必须开新回合, 而不是续上一个");
            assertEquals(1, again.turnSize());
            assertEquals(2, agg.stats().turnsOpened());
        }

        @Test
        void firstTurnOpensImmediatelyAndSecondBatchStartsTheQuietWindow() {
            ConversationTurnAggregator.Acceptance first = agg.accept(delivery(CONV, T0, "m1"));
            assertTrue(first.openedNewTurn());
            assertFalse(first.forced());
            assertEquals(ConversationTurnAggregator.State.OPEN, agg.openTurns().get(0).state());

            agg.accept(delivery(CONV, T0.plusSeconds(3), "m2"));
            // "对方还在说"这个信号契约里没有, 于是退化成"来过几批"—— 一个能被观察到的形状
            assertEquals(ConversationTurnAggregator.State.QUIET_WAIT, agg.openTurns().get(0).state());
            assertEquals(T0, agg.openTurns().get(0).openedAt(), "扩展不重置 openedAt");
        }
    }

    // ─────────────────────────── 两个强制封口 ───────────────────────────

    @Nested
    @DisplayName("两个上限: 攒满 / 拖太久")
    class ForcedSeals {

        @Test
        void eighthMessageSealsTheTurnOnTheSpot() {
            ConversationTurnAggregator.Acceptance acc = null;
            for (int i = 1; i <= 8; i++) {
                // 每句只隔 1 秒 —— 静默窗口(4s)永远走不完, 只有条数能救她
                acc = agg.accept(delivery(CONV, T0.plusSeconds(i), "m" + i));
            }
            assertNotNull(acc);
            assertTrue(acc.forced(), "攒到 maxMessages(8) 就该当场封口, 不再等静默窗口");
            assertTrue(acc.sealedNow().forcedBySize());
            assertFalse(acc.sealedNow().forcedByAge());
            assertEquals(8, acc.sealedNow().size());
            assertEquals(1, agg.stats().forcedBySize());
            assertEquals(0, agg.stats().openTurns(), "当场封口的回合不该留在 map 里");
        }

        @Test
        void aNonStopTalkerStillGetsSealedByTheHardLimit() {
            // 每 3 秒一句(间隔都短于 4 秒静默窗口), 且最后一句离硬上限只有 3 秒 ——
            // 于是"到期"这件事只可能来自硬上限, 不来自静默
            ConversationTurnAggregator agg10 = new ConversationTurnAggregator(4_000, 10_000, 100);
            agg10.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m1"), T0));
            agg10.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m2"), T0.plusSeconds(3)));
            agg10.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m3"), T0.plusSeconds(7)));

            List<ConversationTurnAggregator.Turn> due = agg10.sealDue(T0.plusSeconds(10));
            assertEquals(1, due.size(), "到硬上限必须封口 —— 否则'她一直没回'会由聚合器亲手制造出来");
            assertTrue(due.get(0).forcedByAge(), "静默窗口要 T0+11 才到期, 所以这次封口只可能是硬上限");
            assertEquals(3, due.get(0).size());
            assertEquals(10_000, agg10.window().maxWindowMs());
        }

        @Test
        void hardLimitIsMeasuredFromTheFirstMessageNotTheLatest() {
            // 一个不断发消息的人不能把她的回合无限推迟 —— 硬上限锚在 openedAt 上
            ConversationTurnAggregator agg10 = new ConversationTurnAggregator(4_000, 10_000, 100);
            agg10.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m1"), T0));
            // 第 4 条到达时已经超过 10 秒(即使它离上一条只隔 3 秒)
            agg10.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m2"), T0.plusSeconds(9)));
            ConversationTurnAggregator.Acceptance acc = agg10.accept(
                    new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m3"), T0.plusSeconds(12)));
            assertTrue(acc.forced());
            assertTrue(acc.sealedNow().forcedByAge());
            assertEquals(T0, acc.sealedNow().openedAt());
        }
    }

    // ─────────────────────────── 边界与幂等 ───────────────────────────

    @Nested
    @DisplayName("边界: 不同的线互不干扰, 重投不重复计数")
    class Edges {

        @Test
        void twoConversationsAreTwoTurns() {
            agg.accept(delivery(CONV, T0, "a1"));
            agg.accept(delivery(CONV2, T0, "b1"));

            assertEquals(2, agg.openTurns().size(), "一条线一个回合 —— 两个人同时找她不该被并成一次");
            List<ConversationTurnAggregator.Turn> due = agg.sealDue(T0.plusSeconds(5));
            assertEquals(2, due.size());
            assertEquals(1, due.get(0).size());
            assertEquals(1, due.get(1).size());
            assertEquals(1.0, agg.stats().messagesPerTurn(), 0.001);
        }

        @Test
        void dueTurnsComeOutInTheOrderTheyWereOpened() {
            agg.accept(delivery(CONV2, T0.plusSeconds(2), "b1"));   // 后开口的
            agg.accept(delivery(CONV, T0, "a1"));                   // 先开口的

            List<ConversationTurnAggregator.Turn> due = agg.sealDue(T0.plusSeconds(10));
            assertEquals(2, due.size());
            assertEquals(CONV, due.get(0).conversationId(),
                    "先开口的那条线先被回应 —— 总先回最新那条会让先说话的人永远等不到回复");
            assertEquals(CONV2, due.get(1).conversationId());
        }

        @Test
        void replayingTheSameMessageIdsDoesNotInflateTheTurn() {
            agg.accept(delivery(CONV, T0, "m1", "m2"));
            ConversationTurnAggregator.Acceptance replay = agg.accept(delivery(CONV, T0.plusSeconds(1), "m2"));

            assertFalse(replay.openedNewTurn());
            assertEquals(2, replay.turnSize(), "同一条消息重投不该被算两次 —— 她不会把同一句话读两遍");
            assertEquals(2, agg.stats().messagesAggregated());
        }

        @Test
        void aBatchDeliveryCountsAsItsOwnMessages() {
            ConversationTurnAggregator.Acceptance acc = agg.accept(delivery(CONV, T0, "m1", "m2", "m3"));
            assertEquals(3, acc.turnSize());
            assertEquals(1, agg.stats().turnsOpened());
            assertEquals(3, agg.stats().messagesAggregated());
        }

        @Test
        void anEmptyOrUnaddressableDeliveryIsANoOp() {
            assertNull(agg.accept(delivery(CONV, T0)).turnId());
            assertNull(agg.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, "", List.of("m1"), T0)).turnId());
            assertNull(agg.accept(new ConversationTurnAggregator.Delivery("", USER, CONV, List.of("m1"), T0)).turnId());
            assertNull(agg.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of(), T0)).turnId());
            assertNull(agg.accept(null).turnId());

            ConversationTurnAggregator.Stats s = agg.stats();
            assertEquals(0, s.turnsOpened());
            assertEquals(0, s.messagesAggregated());
            assertEquals(0.0, s.messagesPerTurn(), 0.001, "没有任何回合时合并率是 0, 不是 NaN");
        }

        @Test
        void turnIdIsDeterministicSoLogsMindAndLadderCanBeJoined() {
            agg.accept(delivery(CONV, T0, "m1"));
            String id = agg.openTurns().get(0).turnId();
            assertEquals(CONV + "#m1", id, "回合 id 必须能被重新算出来, 否则三处的记录对不上号");
        }

        @Test
        void nullTimestampsFallBackToNowInsteadOfBlowingUp() {
            ConversationTurnAggregator.Acceptance acc = agg.accept(
                    new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m1"), null));
            assertNotNull(acc.turnId());
            assertNotNull(agg.openTurns().get(0).openedAt());
        }

        @Test
        void cognizedIsCountedSeparatelyFromSealed() {
            agg.accept(delivery(CONV, T0, "m1"));
            agg.sealDue(T0.plusSeconds(5));
            assertEquals(0, agg.stats().turnsCognized(), "封口是时间到了, 认知是消息真的被读到了");

            agg.noteCognized();
            assertEquals(1, agg.stats().turnsCognized());
            assertEquals(1, agg.stats().turnsSealed());
        }

        @Test
        void openTurnsOfFiltersByAgent() {
            agg.accept(delivery(CONV, T0, "m1"));
            agg.accept(new ConversationTurnAggregator.Delivery("agent-2", USER, CONV, List.of("x1"), T0));

            assertEquals(1, agg.openTurnsOf(AGENT).size());
            assertEquals(1, agg.openTurnsOf("agent-2").size());
            assertEquals(0, agg.openTurnsOf("agent-3").size());
            assertEquals(0, agg.openTurnsOf(null).size());
        }

        @Test
        void clearDropsOpenTurnsWithoutCountingThem() {
            agg.accept(delivery(CONV, T0, "m1"));
            agg.clear();
            assertEquals(0, agg.openTurns().size());
            assertEquals(0, agg.stats().turnsSealed(), "丢掉不等于处理过");
        }
    }
}
