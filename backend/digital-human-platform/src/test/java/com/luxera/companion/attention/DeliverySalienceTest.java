package com.luxera.companion.attention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §2.2.2 —— 显著性模型的逐条断言。
 *
 * <p>这个类的测试值得写得比它本身长, 因为<b>它是"她注意到没有"这个判断的全部依据</b>。
 * 一次回归的表现不是崩溃, 而是 53 个 agent 对消息的敏感度整体偏移 —— 不会报错,
 * 只在几天后表现为"她们好像不太理人了"。
 */
class DeliverySalienceTest {

    /** 一条普通消息、陌生人、没有紧急度。所有增量都为 0, 只剩基线。 */
    private static DeliverySalience.Signals plain() {
        return new DeliverySalience.Signals(
                1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 0.0);
    }

    @Nested
    @DisplayName("基线")
    class Baseline {

        @Test
        void aSinglePlainMessageSitsAtTheBaseline() {
            // 0.3 —— 与老链 `0.3 + warmth*0.3 + ...` 的起点同值。
            // 切流时阈值不该同时跳档, 所以这个数字是接口的一部分, 不是实现细节。
            assertEquals(0.3, DeliverySalience.of(plain()), 1e-9);
        }

        @Test
        void nullSignalsFallBackToTheBaselineInsteadOfThrowing() {
            // 送达主链上任何异常都会变成"她不理人"且不报错, 所以这里宁可退化
            assertEquals(0.3, DeliverySalience.of(null), 1e-9);
        }
    }

    @Nested
    @DisplayName("连发: 唯一一个完全不依赖正文的信号")
    class Burst {

        @Test
        void moreMessagesInOneDeliveryMeansMoreSalient() {
            double one = DeliverySalience.of(burst(1));
            double three = DeliverySalience.of(burst(3));
            double five = DeliverySalience.of(burst(5));
            assertTrue(one < three, "3 条应当比 1 条显眼");
            assertTrue(three < five, "5 条应当比 3 条显眼");
        }

        @Test
        void burstBonusIsCappedSoATorrentDoesNotDominate() {
            // 连发是"在催"的近似信号, 但不能让 50 条刷屏把显著性顶满 ——
            // 顶满之后关系、紧急度这些信号就全被淹没了
            assertEquals(DeliverySalience.of(burst(5)), DeliverySalience.of(burst(50)), 1e-9);
        }

        @Test
        void aZeroOrNegativeBurstDoesNotSubtractFromSalience() {
            // 防御: burstSize 是调用方传的, 传 0 时不该出现负数加成
            assertEquals(DeliverySalience.of(burst(1)), DeliverySalience.of(burst(0)), 1e-9);
            assertEquals(DeliverySalience.of(burst(1)), DeliverySalience.of(burst(-3)), 1e-9);
        }
    }

    @Nested
    @DisplayName("关系")
    class Relationship {

        @Test
        void aCloserRelationshipMakesTheSameMessageMoreSalient() {
            var distant = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 0.0);
            var close = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 1.0, 1.0, 0.0);
            assertTrue(DeliverySalience.of(close) > DeliverySalience.of(distant));
        }

        @Test
        void aNewRelationshipGetsABonusBecauseRealPeoplePayAttentionAtFirst() {
            // 与老链 `messageCount < 8` 的保底注意同源
            var fresh = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 2, 0.0, 0.0, 0.0);
            var settled = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 500, 0.0, 0.0, 0.0);
            assertTrue(DeliverySalience.of(fresh) > DeliverySalience.of(settled));
        }
    }

    @Nested
    @DisplayName("两个今天恒为默认值的入参必须是活代码")
    class PendingContractFields {

        @Test
        void urgencyHintActuallyChangesTheResult() {
            // 今天 DeliverySignals 恒填 0。这个断言证明它一旦被接上就会生效,
            // 而不是一个被忽略的参数 —— 否则"等契约补字段"就成了一句空话。
            var calm = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 0.0);
            var urgent = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 1.0);
            assertTrue(DeliverySalience.of(urgent) > DeliverySalience.of(calm));
        }

        @Test
        void aRecentReplyFromHerRaisesSalience() {
            var longAgo = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 0.0);
            var justNow = new DeliverySalience.Signals(1, 1, 100, 0.0, 0.0, 0.0);
            assertTrue(DeliverySalience.of(justNow) > DeliverySalience.of(longAgo));
        }

        @Test
        void theBonusDecaysWithTimeRatherThanCuttingOffAtAThreshold() {
            double justNow = DeliverySalience.of(new DeliverySalience.Signals(1, 1, 100, 0, 0, 0));
            double withinHalfHour = DeliverySalience.of(new DeliverySalience.Signals(1, 20, 100, 0, 0, 0));
            double longAgo = DeliverySalience.of(new DeliverySalience.Signals(1, 600, 100, 0, 0, 0));
            assertTrue(justNow > withinHalfHour, "刚回过话应当比半小时前更强");
            assertTrue(withinHalfHour > longAgo, "半小时前应当比很久以前更强");
        }
    }

    @Nested
    @DisplayName("值域与健壮性")
    class Bounds {

        @Test
        void theResultIsAlwaysAProbabilityLikeZeroToOne() {
            // 所有加成同时拉满 —— 不能超过 1, 否则 AttentionService 里的
            // noticeProbability 会算出一个大于 1 的"概率", 阈值比较就失去意义了
            var everything = new DeliverySalience.Signals(99, 0, 0, 5.0, 5.0, 5.0);
            double v = DeliverySalience.of(everything);
            assertTrue(v >= 0 && v <= 1, "显著性越界: " + v);
        }

        @Test
        void negativeRelationshipValuesDoNotPushSalienceBelowZero() {
            var hostile = new DeliverySalience.Signals(1, 600, 100, -3.0, -3.0, -1.0);
            double v = DeliverySalience.of(hostile);
            assertTrue(v >= 0 && v <= 1, "显著性越界: " + v);
        }

        @Test
        void aNaNRelationshipValueDoesNotPoisonTheWholeScore() {
            // relationships 表里的数值理论上不该是 NaN, 但一旦是,
            // 让它变成 0 比让它传播成"显著性 NaN → 永远不注意到"要好得多
            var poisoned = new DeliverySalience.Signals(
                    1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, Double.NaN, Double.NaN, Double.NaN);
            double v = DeliverySalience.of(poisoned);
            assertFalse(Double.isNaN(v), "NaN 不应传播出去");
            assertTrue(v >= 0 && v <= 1);
        }
    }

    private static DeliverySalience.Signals burst(int n) {
        return new DeliverySalience.Signals(
                n, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.0, 0.0, 0.0);
    }
}
