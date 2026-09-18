package com.luxera.companion.runtime.v11;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §25 —— shadow 记录器。<b>重点全在"它不会长大"。</b>
 *
 * <p>V10 的 {@code ShadowDecisionRecorder} 用 {@code companionId + "-" + System.nanoTime()}
 * 做键且从不淘汰, 于是它是一条只涨不跌的内存曲线, 而 {@code stats()} 还把
 * {@code buffer.size()} 当指标报出来。这个类的用例就是为了让同一件事不会再发生:
 * <b>喂它十万条, 它占用的大小必须纹丝不动。</b>
 */
class V11DeliveryShadowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 21, 0);

    /**
     * 本类会刻意投喂几千条, 而 {@link V11DeliveryShadow} 每条都记一行 DEBUG
     * (生产上是 INFO 之上的正常行为, 且有 {@code isDebugEnabled()} 守卫)。
     * 测试里把它压掉: 否则每次跑都会生成 MB 级的 system-out,
     * 把真正的失败信息埋掉 —— 曾经就是 18MB。
     */
    @BeforeAll
    static void silenceTheRecorder() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(V11DeliveryShadow.class))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    private static void feed(V11DeliveryShadow s, String agent, int n, boolean noticed) {
        for (int i = 0; i < n; i++) {
            s.record(agent, 3, 0.6, 0.5, noticed, true, -1, NOW);
        }
    }

    /**
     * 条数取 5000: 证明上限只要"远大于 64"就够了。
     * 曾经这里写的是 100000, 结果在开了 DEBUG 的测试环境里生成了 18MB 的
     * system-out —— 上限是被 CAPACITY 证明的, 不是被喂进去的量证明的。
     */
    @Test
    @DisplayName("持续投喂下样本环不长大")
    void theSampleRingIsBoundedNoMatterHowMuchTrafficArrives() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        feed(s, "agent-a", 5_000, true);

        // 计数器照实累加(它们是定长的), 但样本只有最后 64 条
        assertEquals(5_000L, s.stats().get("total"));
        assertEquals(64, s.recentSamples().size(),
                "样本必须有上限, 否则这就是 V10 那个泄漏的翻版");
    }

    @Test
    @DisplayName("agent 数量增长时, 每个 agent 的汇总也只留一份")
    void perAgentSummaryDoesNotDuplicate() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        feed(s, "agent-a", 500, true);
        feed(s, "agent-b", 500, false);

        Map<String, Object> perAgent = s.perAgentStats();
        assertEquals(2, perAgent.size(), "同一个 agent 只该有一份汇总");
        assertEquals(1000L, s.stats().get("total"));
    }

    @Test
    @DisplayName("分歧率: 老链处理了而新门认为她不会注意到")
    void disagreementRateCountsTheDeliveriesTheNewGateWouldSkip() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        feed(s, "agent-a", 3, true);    // 一致
        feed(s, "agent-a", 1, false);   // 分歧: 新门会跳过

        assertEquals(4L, s.stats().get("total"));
        assertEquals(3L, s.stats().get("agreeNoticed"));
        assertEquals(1L, s.stats().get("v11WouldSkip"));
        assertEquals(0.25, (double) s.stats().get("skipRate"), 1e-9);
    }

    @Test
    @DisplayName("一条流量都没有时不能除以零")
    void anEmptyRecorderReportsZeroRatherThanNaN() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        assertEquals(0L, s.stats().get("total"));
        assertEquals(0.0, (double) s.stats().get("skipRate"), 1e-9);
        assertTrue(s.recentSamples().isEmpty());
    }

    @Test
    @DisplayName("readCount 为负(没尝试读)时不算作'读不到'")
    void notAttemptingAReadIsNotTheSameAsReadingNothing() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        // -1 = shadow 模式没读
        s.record("agent-a", 1, 0.6, 0.9, true, true, -1, NOW);
        assertEquals(0L, s.stats().get("v11NoText"));

        // 0 = 真的读了, 一条都没读到 —— 这才是"她看见了却不回"的信号
        s.record("agent-a", 1, 0.6, 0.9, true, true, 0, NOW);
        assertEquals(1L, s.stats().get("v11NoText"));
    }

    @Test
    @DisplayName("每个 agent 的均值能看出谁最反常")
    void perAgentAveragesExposeAnOutlier() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        s.record("quiet", 1, 0.3, 0.1, false, true, -1, NOW);
        s.record("loud", 8, 0.9, 0.95, true, true, -1, NOW);

        @SuppressWarnings("unchecked")
        Map<String, Object> quiet = (Map<String, Object>) s.perAgentStats().get("quiet");
        @SuppressWarnings("unchecked")
        Map<String, Object> loud = (Map<String, Object>) s.perAgentStats().get("loud");

        assertEquals(1L, quiet.get("n"));
        assertEquals(8.0, (double) loud.get("avgBurst"), 1e-9);
        assertTrue((double) loud.get("avgNoticeProb") > (double) quiet.get("avgNoticeProb"));
    }

    @Test
    @DisplayName("最近样本带得动诊断信息")
    void recentSamplesCarryEnoughToExplainADecision() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        feed(s, "agent-a", 1, false);
        String sample = s.recentSamples().get(0);
        assertTrue(sample.contains("agent-a"), sample);
        assertTrue(sample.contains("noticed=false"), sample);
    }

    /**
     * 这一条防的不是功能, 是 V10 那个具体的失败:
     * 记录器的查询方法写好了却<b>没有任何调用者</b> —— 没端点、没测试,
     * 于是 shadow 一直是纯成本。结构化读出口必须真的能读出东西来。
     */
    @Test
    @DisplayName("结构化样本读得出来, 且只读指定 agent 的")
    void recentForIsTheReadableExitThatV10NeverHad() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        feed(s, "agent-a", 3, true);
        feed(s, "agent-b", 5, false);

        var forA = s.recentFor("agent-a", 20);
        assertEquals(3, forA.size(), "只该看到 agent-a 的样本");
        for (Map<String, Object> m : forA) {
            assertNotNull(m.get("burstSize"));
            assertNotNull(m.get("salience"));
            assertNotNull(m.get("noticeProbability"));
            assertEquals(Boolean.TRUE, m.get("v11Noticed"));
        }

        var forB = s.recentFor("agent-b", 20);
        assertEquals(5, forB.size());
        assertEquals(Boolean.FALSE, forB.get(0).get("v11Noticed"), "最近的样本排在最前");
    }

    @Test
    @DisplayName("无样本或 limit 为 0 时返回空表, 不返回 null")
    void recentForIsEmptyRatherThanNull() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        assertTrue(s.recentFor("nobody", 20).isEmpty());
        feed(s, "agent-a", 1, true);
        assertTrue(s.recentFor("agent-a", 0).isEmpty());
    }

    /**
     * 诊断端点直接把这些键序列化出去, 所以键名是接口, 不是实现细节。
     * 有人"顺手改个名"时这条会红。
     */
    @Test
    @DisplayName("stats() 的键名是诊断端点的对外契约")
    void theStatKeysAreAContract() {
        V11DeliveryShadow s = new V11DeliveryShadow();
        assertTrue(s.stats().keySet().containsAll(
                java.util.List.of("total", "agreeNoticed", "v11WouldSkip", "v11NoText", "skipRate")),
                "键名变了就会让 /v11 诊断端点的读者悄悄读到 null: " + s.stats().keySet());
    }
}
