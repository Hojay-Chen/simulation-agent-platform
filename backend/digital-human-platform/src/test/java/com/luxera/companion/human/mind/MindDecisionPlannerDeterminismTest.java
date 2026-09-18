package com.luxera.companion.human.mind;

import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.cognition.ReasoningContext;
import com.luxera.companion.human.mind.cognition.ReasoningResult;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.ProposedIntention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.5 —— <b>决定做什么的是确定性引擎</b>。
 *
 * <h2>确定性在这里是什么意思</h2>
 * 不是"结果永远正确", 而是<b>同一个处境永远得到同一个结论</b>。这条性质之所以是硬要求,
 * 是因为它的反例会被误读成性格:
 *
 * <blockquote>
 * 一个随机的行为会被误读成性格。
 * </blockquote>
 *
 * <p>平局是随机性最容易溜进来的地方 —— 平局的胜者默认取决于 {@code List} 的迭代顺序,
 * 而那取决于候选是怎么被塞进来的。所以这里专门有一条断言:
 * <b>把候选的顺序倒过来, 结论不变</b>。
 *
 * <h2>它做不到什么(本测试也钉住了)</h2>
 * 它只选"此刻最该做的", 不选"最合适的" —— 后者需要品味, 而品味不是能写进
 * 确定性引擎的东西。本测试因此不假装能验证"选得对", 只验证"选得稳"。
 */
class MindDecisionPlannerDeterminismTest {

    private static final Instant T = Instant.parse("2026-03-02T20:00:00Z");

    private final MindDecisionPlanner planner = new MindDecisionPlanner();

    private static ReasoningContext context(List<Intention> candidates) {
        return ReasoningContext.quiet(T, IntentionContext.minimal(T)).withCandidates(candidates);
    }

    @Test
    @DisplayName("同一个处境跑两次, 得到同一个结论")
    void 同一个处境跑两次得到同一个结论() {
        List<Intention> candidates = List.of(
                ProposedIntention.of("experiment", "把实验做完", IntentionPriority.IMPORTANT),
                ProposedIntention.of("reply", "回一条消息", IntentionPriority.ROUTINE));

        ReasoningResult first = planner.reason(context(candidates));
        ReasoningResult second = planner.reason(context(candidates));

        assertTrue(first.deterministic(), "这个引擎必须自报确定性 —— 调用方要靠它决定能不能回放");
        assertEquals(first.intentions().get(0).id(), second.intentions().get(0).id());
        assertEquals(first.understanding(), second.understanding());
        assertEquals("experiment", first.intentions().get(0).id().value(), "优先级高的胜出");
    }

    @Test
    @DisplayName("平局被打断 —— 候选顺序倒过来, 结论不变")
    void 平局被打断() {
        Intention first = ProposedIntention.of("a", "回一条消息", IntentionPriority.ROUTINE);
        Intention second = ProposedIntention.of("b", "把作业写完", IntentionPriority.ROUTINE);

        ReasoningResult forward = planner.reason(context(List.of(first, second)));
        ReasoningResult backward = planner.reason(context(List.of(second, first)));

        assertEquals(forward.intentions().get(0).id().value(),
                backward.intentions().get(0).id().value(),
                "同一个 agent 在同一个处境下有时选 A 有时选 B —— 而一个随机的行为会被误读成性格");

        // 全序里平局的裁决键是描述, 这里就按那条规则算出应当胜出的那一个
        String expected = List.of(first, second).stream()
                .min(Comparator.comparing(Intention::description))
                .orElseThrow()
                .id().value();
        assertEquals(expected, forward.intentions().get(0).id().value());
    }

    @Test
    @DisplayName("做不了的念头不会被选中, 而且会说明为什么")
    void 做不了的念头不会被选中() {
        ProposedIntention needsLab = ProposedIntention.needing("lab", "去实验室做实验",
                IntentionPriority.CRITICAL, Set.of("lab.access"));
        ProposedIntention easy = ProposedIntention.of("water", "喝口水", IntentionPriority.TRIVIAL);

        ReasoningResult result = planner.reason(context(List.of(needsLab, easy)));

        assertEquals("water", result.intentions().get(0).id().value(),
                "一个此刻做不了的念头被选中了 —— 那会让她每一轮都在想做不成的事");
        assertTrue(result.observations().stream().anyMatch(o -> o.contains("实验室")),
                "她想过但做不成的那件事必须留在观察里, 否则'她没想到'与'她想了做不成'分不开: "
                        + result.observations());
    }

    @Test
    @DisplayName("一个候选都没有时, 返回空候选 —— 不造一个假的决定出来")
    void 一个候选都没有时返回空候选() {
        ReasoningResult result = planner.reason(ReasoningContext.quiet(T, IntentionContext.minimal(T)));

        assertTrue(result.intentions().isEmpty());
        assertFalse(result.understanding().isBlank(), "什么都没发生时也要有一句话能解释, 否则日志是空的");
    }

    @Test
    @DisplayName("它只产候选, 不产动作 —— 动作是决定那一层的事")
    void 它只产候选() {
        assertFalse(ReasoningResult.class.isEnum());
        for (java.lang.reflect.Method method : ReasoningResult.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("action") || name.contains("command"),
                    "认知结果上出现了动作/命令: " + method.getName()
                            + " —— §3.4.5 的分工表里, '决定做什么'的下一步才轮到动作");
            assertFalse(name.contains("feasib"),
                    "认知结果上出现了可行性字段: 可行性在意图自己身上(Intention#evaluate), "
                            + "在这里再放一份会让同一个问题有两个可能互相矛盾的答案");
        }
    }
}
