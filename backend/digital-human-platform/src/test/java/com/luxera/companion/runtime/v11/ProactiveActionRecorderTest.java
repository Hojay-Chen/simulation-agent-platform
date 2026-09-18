package com.luxera.companion.runtime.v11;

import com.luxera.companion.behavior.BehaviorAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §25.2 —— <b>主动行为的切流账本</b>。
 *
 * <p>这本账与认知那本有一个根本区别: 它<b>做不到逐条配对</b>(触发器在调度线程上,
 * 消费者在她的 actor 线程上, 中间隔着一个信箱), 所以它是聚合对照。
 * 本文件里 {@link Semantics#thereIsDeliberatelyNoProactivityRatio} 把那件"故意不做的事"
 * 钉住 —— 一个看起来精确、实际会让人做错决定的比率, 比没有比率更糟。
 */
class ProactiveActionRecorderTest {

    private ProactiveActionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ProactiveActionRecorder();
    }

    @Nested
    @DisplayName("什么算'她开口了'")
    class Semantics {

        @Test
        void onlySendingAMessageCounts() {
            assertTrue(ProactiveActionRecorder.speaks(BehaviorAction.SEND_PROACTIVE_MESSAGE));
        }

        @Test
        void internalActionsDoNotCount() {
            // CHECK_PHONE 改变她的内部状态与她对这个世界的印象, 但不产生一条
            // 用户看得见的消息。把它算成"主动", 会让切流判据里混进一批
            // 对方根本看不见的行为 —— 而那正是"她会变吵吗"这个问题的答案失真之处。
            assertFalse(ProactiveActionRecorder.speaks(BehaviorAction.CHECK_PHONE));
            assertFalse(ProactiveActionRecorder.speaks(BehaviorAction.CONTACT_OTHER_PERSON));
            assertFalse(ProactiveActionRecorder.speaks(BehaviorAction.DO_NOTHING));
            assertFalse(ProactiveActionRecorder.speaks(null));
        }

        @Test
        void thereIsDeliberatelyNoProactivityRatio() {
            recorder.record("LIFE_EVENT", BehaviorAction.SEND_PROACTIVE_MESSAGE, "想问问", 0.6);
            recorder.recordOld(BehaviorAction.SEND_PROACTIVE_MESSAGE);

            Map<String, Object> stats = recorder.stats();

            // 分子是"新链算出来的次数"(按拆开的信取样), 分母是"老链实际发出去的条数"
            // (按跑过的 agent 取样)。两个口径不同, 相除得到的比率会被读成
            // "她会多主动 X%", 而那个读法是错的。并列给出两个计数, 让读的人自己判断。
            assertEquals(1L, stats.get("wouldAct"));
            assertEquals(1L, stats.get("oldActed"));
            assertFalse(stats.containsKey("ratio"));
            assertFalse(stats.containsKey("speakRatio"));
        }
    }

    @Nested
    @DisplayName("计数")
    class Counting {

        @Test
        void aLetterThatLedToNothingIsStillCountedAsAnEvent() {
            // 分母是"拆开的信", 不是"她本来会说几次话"。把没说话的那些从分母里剔掉,
            // 比率就不再是"她多主动"而是"她说话时说了几次" —— 一个完全不同的问题。
            recorder.record("SCHEDULED_WAKEUP", BehaviorAction.DO_NOTHING, "发会呆", 0.3);

            assertEquals(1L, recorder.events());
            assertEquals(0L, recorder.wouldAct());
            assertEquals(1, ((Map<?, ?>) recorder.stats().get("byEventType")).size());
        }

        @Test
        void aNullActionIsCountedAsAnEventButNotAsAPlan() {
            // 消费者可能在开关关掉时带一个 null 动作进来("拆掉但没打算做什么")
            recorder.record("LIFE_EVENT", null, "proactive_off", 0);

            assertEquals(1L, recorder.events());
            assertTrue(((Map<?, ?>) recorder.stats().get("byPlannedAction")).isEmpty());
        }

        @Test
        void errorsAreCountedSeparatelySoABrokenShadowCannotLookLikeSilence() {
            recorder.recordError();
            recorder.recordError();

            assertEquals(2L, recorder.errors());
            assertEquals(0L, recorder.events(), "错误不该被算成一次'她本来不会说话'");
        }

        @Test
        void samplesAreCapped() {
            for (int i = 0; i < ProactiveActionRecorder.MAX_SAMPLES + 5; i++) {
                recorder.record("LIFE_EVENT", BehaviorAction.SEND_PROACTIVE_MESSAGE, "理由" + i, 0.5);
            }

            assertEquals(ProactiveActionRecorder.MAX_SAMPLES,
                    ((java.util.List<?>) recorder.stats().get("samples")).size(),
                    "读不完的证据等于没有证据");
            assertEquals(ProactiveActionRecorder.MAX_SAMPLES + 5L, recorder.wouldAct(),
                    "样本有上限, 计数没有");
        }

        @Test
        void theNewestSampleIsKept() {
            for (int i = 0; i < ProactiveActionRecorder.MAX_SAMPLES + 3; i++) {
                recorder.record("LIFE_EVENT", BehaviorAction.SEND_PROACTIVE_MESSAGE, "理由" + i, 0.5);
            }

            var samples = (java.util.List<?>) recorder.stats().get("samples");
            assertTrue(samples.get(samples.size() - 1).toString().contains("理由"
                    + (ProactiveActionRecorder.MAX_SAMPLES + 2)),
                    "丢掉的是最旧的, 留下的是最近的");
        }

        @Test
        void resetClearsEverything() {
            recorder.record("LIFE_EVENT", BehaviorAction.SEND_PROACTIVE_MESSAGE, "想问问", 0.6);
            recorder.recordOld(BehaviorAction.SEND_PROACTIVE_MESSAGE);
            recorder.recordError();

            recorder.reset();

            assertEquals(0L, recorder.events());
            assertEquals(0L, recorder.wouldAct());
            assertEquals(0L, recorder.errors());
            assertTrue(((Map<?, ?>) recorder.stats().get("byEventType")).isEmpty());
            assertTrue(((java.util.List<?>) recorder.stats().get("samples")).isEmpty());
        }
    }
}
