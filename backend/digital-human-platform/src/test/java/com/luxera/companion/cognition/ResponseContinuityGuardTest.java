package com.luxera.companion.cognition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §13.2 —— <b>别把同一句话再说一遍</b>。
 *
 * <p>这个类最容易出的错不是"漏判", 是<b>误判</b>: 判错一次, 她该说话的时候被堵住嘴,
 * 而那种故障不报错, 只表现为"她最近话变少了"。所以下面有整整一组
 * {@link NotAFalsePositive} —— 它们和"能抓住重复"同样重要。
 */
class ResponseContinuityGuardTest {

    private ResponseContinuityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ResponseContinuityGuard();
    }

    private List<String> hers(String... lines) {
        return new ArrayList<>(List.of(lines));
    }

    // ─────────────────── 该抓住的 ───────────────────

    @Nested
    @DisplayName("该抓住的重复")
    class Catches {

        @Test
        void anIdenticalReplyIsADuplicate() {
            var v = guard.check("别难过, 抱抱你",
                    hers("别难过, 抱抱你"));
            assertEquals(ResponseContinuityGuard.Verdict.Kind.DUPLICATE, v.kind());
            assertTrue(v.isProblem());
            assertEquals("别难过, 抱抱你", v.evidence(), "必须指出撞上了哪一句, 否则排查时无从下手");
        }

        @Test
        void punctuationAndSpacingDoNotHideADuplicate() {
            // 逐字比较抓不住它 —— 而人念出来这两句是一样的
            var v = guard.check("别难过 抱抱你。", hers("别难过，抱抱你"));
            assertEquals(ResponseContinuityGuard.Verdict.Kind.DUPLICATE, v.kind());
        }

        @Test
        void askingTheSameQuestionAgainIsCaught() {
            var v = guard.check("你今天吃饭了吗？", hers("你今天吃饭了没？"));
            assertEquals(ResponseContinuityGuard.Verdict.Kind.REPEATED_QUESTION, v.kind(),
                    "同一个问题换了语气词 —— 逐字比较抓不到, 而人会觉得'你怎么又问一遍'");
        }

        @Test
        void theSameOpeningInterjectionTwiceInARowIsCaught() {
            var v = guard.check("哈哈那你也太惨了", hers("哈哈我今天也是这样"));
            assertEquals(ResponseContinuityGuard.Verdict.Kind.REPEATED_OPENING, v.kind());
            assertFalse(v.isProblem(), "连着两次'哈哈'大概率无所谓 —— 它是提示, 不是拦截理由");
        }
    }

    // ─────────────────── 绝不能误伤的 ───────────────────

    @Nested
    @DisplayName("不能误伤的: 人本来就该这样说话")
    class NotAFalsePositive {

        @Test
        void noHistoryMeansNoProblem() {
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK, guard.check("在的", null).kind());
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK, guard.check("在的", List.of()).kind());
        }

        @Test
        void aLongOpeningIsNotARepeatedInterjection() {
            // 坏样子是连着两条都只有"哈哈""嗯嗯"那种长度; 两条正常长度的句子都以"诶"开头
            // 完全是人的说话方式, 拦它就是堵住她的嘴
            var v = guard.check("诶你说的那个我想起来了, 是上周三那件事吧",
                    hers("诶今天天气不错"));
            assertNotEquals(ResponseContinuityGuard.Verdict.Kind.REPEATED_OPENING, v.kind());
        }

        @Test
        void theSameInterjectionThreeMessagesApartIsJustHerWayOfTalking() {
            // 契约是升序(旧 → 新), 所以"上一条"是最后一个元素 —— "哈哈我也是"在这个列表的
            // <b>开头</b>, 也就是三条之前。写成列表末尾就变成了"紧接着的上一条",
            // 而那样这条用例测的恰好是反面。
            var v = guard.check("哈哈好", hers("哈哈我也是", "我先去忙了", "晚点聊"));
            assertNotEquals(ResponseContinuityGuard.Verdict.Kind.REPEATED_OPENING, v.kind(),
                    "只看紧接着的上一条 —— 隔三条出现一次是说话习惯, 不是卡住了");
        }

        @Test
        void aShortQuestionIsNotCompared() {
            // 「在吗」这种太短, 撞上的概率高得没有意义 —— 真人也会连着问两次"在吗"
            var v = guard.check("在吗", hers("在吗"));
            assertNotEquals(ResponseContinuityGuard.Verdict.Kind.REPEATED_QUESTION, v.kind(),
                    "两个字的问句不该进问题去重 —— 但逐字重复仍然算");
        }

        @Test
        void aStatementAnsweringHerOwnQuestionIsFine() {
            // 她问完自己又补了一句陈述 —— 这是正常的, 反过来才是问题
            var v = guard.check("我今天有点累", hers("你今天怎么样？"));
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK, v.kind());
        }

        @Test
        void onlyTheLastFewMessagesAreLookedAt() {
            // 很久以前说过一模一样的话不算重复: 那可能是对方又提起了同一件事
            List<String> history = new ArrayList<>();
            history.add("我今天有点累");
            for (int i = 0; i < 10; i++) {
                history.add("嗯嗯 " + i);
            }
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK,
                    guard.check("我今天有点累", history).kind());
        }

        @Test
        void anEmptyCandidateIsNotARepetitionProblem() {
            // 空回复是别的闸门(输出验证)管的 —— 在这里拦会让故障归属错位
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK, guard.check("   ", hers("在的")).kind());
            assertEquals(ResponseContinuityGuard.Verdict.Kind.OK, guard.check(null, hers("在的")).kind());
        }
    }

    // ─────────────────── 提示词 ───────────────────

    @Nested
    @DisplayName("重新生成用的提示")
    class Hints {

        @Test
        void okHasNoHint() {
            assertNull(guard.rewriteHint(null));
            assertNull(guard.rewriteHint(guard.check("在的", List.of())));
        }

        @Test
        void everyProblemKindHasANonEmptyHint() {
            // 提示为空等于"重生成一次但什么都没告诉它" —— 那只会得到同一句话
            for (ResponseContinuityGuard.Verdict v : List.of(
                    guard.check("别难过, 抱抱你", hers("别难过, 抱抱你")),
                    guard.check("你今天吃饭了吗？", hers("你今天吃饭了没？")),
                    guard.check("哈哈那你也太惨了", hers("哈哈我今天也是这样")))) {
                String hint = guard.rewriteHint(v);
                assertNotNull(hint, v.kind() + " 没有提示词");
                assertFalse(hint.isBlank());
            }
        }

        @Test
        void theHintDoesNotLeakTheWholeQuote() {
            String longLine = "这是一句非常长的话".repeat(10);
            String hint = guard.rewriteHint(guard.check(longLine, hers(longLine)));
            assertTrue(hint.length() < 120, "提示词会把旧句抄进去, 太长会挤占表达提示的预算: " + hint.length());
        }
    }

    // ─────────────────── 纯函数 ───────────────────

    @Nested
    @DisplayName("归一化与问句主干(纯函数, 单独钉住)")
    class PureFunctions {

        @Test
        void normalizationStripsPunctuationAndSpace() {
            assertEquals("别难过抱抱你", ResponseContinuityGuard.normalize("别难过，抱抱你。"));
            assertEquals("", ResponseContinuityGuard.normalize(null));
            assertEquals("", ResponseContinuityGuard.normalize("  ，。 "));
        }

        @Test
        void questionStemStripsOnlyTheTail() {
            assertEquals("你今天吃饭", ResponseContinuityGuard.questionStem("你今天吃饭了吗"));
            assertEquals("你今天吃饭", ResponseContinuityGuard.questionStem("你今天吃饭了没"));
            // 中间的字绝不能被削掉 —— 否则"你想我了吗"与"你想他了吗"会变成同一句,
            // 于是她问"你想我了吗"之后就被禁止问"你想他了吗"
            assertEquals("你想我", ResponseContinuityGuard.questionStem("你想我了吗"));
            assertEquals("你想他", ResponseContinuityGuard.questionStem("你想他了吗"));
        }

        @Test
        void questionStemLeavesAStatementAlone() {
            assertEquals("我今天有点累", ResponseContinuityGuard.questionStem("我今天有点累"));
        }
    }
}
