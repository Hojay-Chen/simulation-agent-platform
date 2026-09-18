package com.luxera.companion.runtime.v11;

import com.luxera.companion.cognition.CognitiveDecision;
import com.luxera.companion.cognition.DecisionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §25.2 —— <b>切流判据的账本</b>。
 *
 * <p>本文件里最重要的是 {@link Silence} 那一组: {@code wouldSilence} 就是
 * "切流后 53 个 agent 会安静下来的比例"。这个数字算错的方向有两种, 而它们
 * 都指向同一个坏结果 —— 你对着一本错账判断"可以切了"。
 */
class CognitionDecisionRecorderTest {

    private CognitionDecisionRecorder recorder;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        recorder = new CognitionDecisionRecorder();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats() {
        return recorder.stats();
    }

    @SuppressWarnings("unchecked")
    private long n(String key) {
        return ((Number) stats().get(key)).longValue();
    }

    // ─────────────────── 三种对照 ───────────────────

    @Nested
    @DisplayName("对照: 比的是'会不会写一条消息出去', 不是决策名字")
    class Silence {

        @Test
        void bothSidesReplyingIsAgreement() {
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            assertEquals(1, n("agree"));
            assertEquals(0, n("wouldSilence"));
        }

        @Test
        void planningSilenceWhereTheOldChainRepliedIsTheNumberToWatch() {
            recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "REPLY");
            assertEquals(1, n("wouldSilence"));
            assertEquals(0, n("agree"));
            assertEquals(1.0, (double) stats().get("silenceRate"), 0.001);
        }

        @Test
        void planningAReplyWhereTheOldChainStayedQuietIsTheOtherDirection() {
            // 这一边通常很小 —— 老链不回的原因大多是"没写出来"或"与事实冲突",
            // 而那些在新链里仍然会发生。它大起来说明 planner 太爱说话了
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), false, "IGNORE");
            assertEquals(1, n("wouldSpeak"));
        }

        @Test
        void observeIsNotTreatedAsSilenceWhenTheOldChainAlsoStayedQuiet() {
            // 名字不同但行为相同 → 一致。把它算成差异会让差异率虚高,
            // 于是真正该盯的 wouldSilence 被淹没在一堆噪声里
            recorder.record(CognitiveDecision.of(DecisionType.OBSERVE, "not_noticed"), false, "IGNORE");
            assertEquals(1, n("agree"));
            assertEquals(0, n("wouldSilence"));
        }

        @Test
        void readMessagesCountsAsSilenceIfTheOldChainReplied() {
            recorder.record(CognitiveDecision.of(DecisionType.READ_MESSAGES, "woken_up"), true, "REPLY");
            assertEquals(1, n("wouldSilence"));
        }

        @Test
        void classificationIsSymmetricAndPure() {
            assertEquals(CognitionDecisionRecorder.Agreement.AGREE,
                    CognitionDecisionRecorder.classify(DecisionType.REPLY, true));
            assertEquals(CognitionDecisionRecorder.Agreement.AGREE,
                    CognitionDecisionRecorder.classify(DecisionType.DO_NOTHING, false));
            assertEquals(CognitionDecisionRecorder.Agreement.WOULD_SILENCE,
                    CognitionDecisionRecorder.classify(DecisionType.DO_NOTHING, true));
            assertEquals(CognitionDecisionRecorder.Agreement.WOULD_SPEAK,
                    CognitionDecisionRecorder.classify(DecisionType.REPLY, false));
        }
    }

    // ─────────────────── 比率与分布 ───────────────────

    @Nested
    @DisplayName("比率与分布")
    class Rates {

        @Test
        void divergenceRateIsOverDecisionsNotMessages() {
            // 分母是"多少次认知", 不是"多少条消息"。一个回合可能含三条消息 ——
            // 拿消息总数当分母会得到一个偏小的数, 而判据问的是"多少次认知会改变结果"
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");

            assertEquals(4, n("decisions"));
            assertEquals(0.25, (double) stats().get("divergenceRate"), 0.001);
            assertEquals(0.25, (double) stats().get("silenceRate"), 0.001);
        }

        @Test
        void anEmptyRecorderReportsZeroNotNaN() {
            // NaN 会一路传到 JSON 里变成 null, 而读到 null 的人会以为"没数据"还是"没在看"分不清 ——
            // 两者本来就要靠 note 区分
            assertEquals(0.0, (double) stats().get("divergenceRate"), 0.0001);
            assertEquals(0l, n("decisions"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void reasonsAreCountedSeparatelySoQuietCanBeExplained() {
            // "她为什么安静"必须能一眼看出是"忙"还是"没看到" —— 处置完全不同
            recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.DO_NOTHING, "not_noticed"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "REPLY");

            Map<String, Long> byReason = (Map<String, Long>) stats().get("byReason");
            assertEquals(2L, byReason.get("busy_busy").longValue());
            assertEquals(1L, byReason.get("not_noticed").longValue());
        }

        @Test
        @SuppressWarnings("unchecked")
        void typesAreCountedSeparately() {
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            recorder.record(CognitiveDecision.of(DecisionType.WAIT, "sleeping"), false, "IGNORE");

            Map<String, Long> byType = (Map<String, Long>) stats().get("byType");
            assertEquals(2L, byType.get("REPLY").longValue());
            assertEquals(1L, byType.get("WAIT").longValue());
        }

        @Test
        void errorsAreCountedApartFromDivergence() {
            // 一个坏掉的 shadow 会表现为"差异率 0%, 可以切流了" —— 除非这两件事分开计数
            recorder.recordError();
            recorder.recordError();
            assertEquals(2, n("errors"));
            assertEquals(0, n("decisions"));
            assertEquals(0.0, (double) stats().get("divergenceRate"), 0.0001);
        }
    }

    // ─────────────────── 样本 ───────────────────

    @Nested
    @DisplayName("差异样本: 只留能读完的那些")
    class Samples {

        @Test
        @SuppressWarnings("unchecked")
        void onlyDivergencesAreSampled() {
            recorder.record(CognitiveDecision.of(DecisionType.REPLY, "engaged"), true, "REPLY");
            for (int i = 0; i < 3; i++) {
                recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "REPLY");
            }
            var samples = (java.util.List<?>) stats().get("samples");
            assertEquals(3, samples.size(), "一致的那些没有排查价值, 混进来只会把样本挤满");
        }

        @Test
        @SuppressWarnings("unchecked")
        void theOldestSamplesFallOffSoTheListStaysReadable() {
            for (int i = 0; i < CognitionDecisionRecorder.MAX_SAMPLES + 15; i++) {
                recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy"), true, "REPLY");
            }
            var samples = (java.util.List<?>) stats().get("samples");
            assertEquals(CognitionDecisionRecorder.MAX_SAMPLES, samples.size(),
                    "读不完的证据等于没有证据 —— 一个存了十万条的列表在'明天要不要切流'上提供的信息一样多");
        }

        @Test
        void aSampleCarriesTheOldChainOutcomeSoTheDivergenceIsExplainable() {
            recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy_busy"), true, "DEFERRED");
            @SuppressWarnings("unchecked")
            var samples = (java.util.List<CognitionDecisionRecorder.Divergence>) stats().get("samples");
            var d = samples.get(0);
            assertEquals(DecisionType.DEFER, d.planned());
            assertEquals("busy_busy", d.reason());
            assertTrue(d.oldChainReplied());
            assertEquals("DEFERRED", d.detail(), "必须留下老链当时的实际走向, 否则这条样本无法解释");
            assertNotNull(d.at());
        }
    }

    @Test
    void resetClearsEverythingSoANewConfigStartsFromZero() {
        // 改配置之后必须能清零: 把旧配置的样本混进新一批, 你会对着一个被污染的
        // 平均数判断"可以切了"
        recorder.record(CognitiveDecision.of(DecisionType.DEFER, "busy"), true, "REPLY");
        recorder.recordError();
        recorder.reset();

        assertEquals(0l, n("decisions"));
        assertEquals(0l, n("errors"));
        assertEquals(0, recorder.wouldSilence());
    }
}
