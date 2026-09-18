package com.luxera.companion.human.mind;

import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.cognition.ReasoningContext;
import com.luxera.companion.human.mind.cognition.ReasoningResult;
import com.luxera.companion.human.mind.decision.ActionIntent;
import com.luxera.companion.human.mind.decision.Decision;
import com.luxera.companion.human.mind.decision.DecisionId;
import com.luxera.companion.human.mind.decision.DecisionReason;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.decision.RuleDecision;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.ProposedIntention;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.relationship.PersonaSpec;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.5 —— <b>LLM 只在"已经决定要说"之后才被调用</b>。
 *
 * <h2>被钉住的那句话</h2>
 * <blockquote>
 * 以前是先问模型要不要回, 再解释它的答案; 现在是<b>先有"她决定做什么"这个值,
 * 模型只在已经决定要说之后才被调用</b>。
 * </blockquote>
 *
 * <h2>这条纪律怎么才能被测试</h2>
 * 不能靠读代码确认"这里没调模型" —— 那种约定会在某次重构里静默消失。
 * 能测的是<b>调用次数</b>: 用一个会数数的假引擎, 然后断言
 *
 * <ul>
 *   <li>{@link #决定做完之前一次都没叫过模型()} —— {@code decide} 跑完之后计数是 0;</li>
 *   <li>{@link #决定不需要说话时模型一次都不会被叫()} —— 计数还是 0;</li>
 *   <li>{@link #决定要说时模型才被叫_而且拿到的正是那个决定()} —— 计数是 1,
 *       而且它拿到的是<b>同一个对象</b>。这一条比计数更硬: 它证明了顺序,
 *       而不只是证明了次数。</li>
 * </ul>
 */
class LanguageEngineOrderTest {

    private static final Instant T = Instant.parse("2026-03-02T20:00:00Z");

    /** 一个会数数的语言引擎 —— 它存在的唯一目的就是让"有没有被叫"变成一个可断言的事实。 */
    static final class CountingLanguage implements LanguageEngine {

        private final List<Decision> rendered = new ArrayList<>();

        @Override
        public String render(Decision decision, String situation) {
            rendered.add(decision);
            return "她说: " + decision.reason().narrative() + "(" + situation + ")";
        }

        @Override
        public String engineId() {
            return "test.counting";
        }

        int calls() {
            return rendered.size();
        }

        Decision lastGiven() {
            return rendered.get(rendered.size() - 1);
        }
    }

    private final CountingLanguage counting = new CountingLanguage();

    private Mind mind() {
        EventFabric fabric = new DefaultEventFabric("test-human", new EventHandlerRegistry());
        RelationshipGraph relationships = RelationshipGraph.bootstrap(
                PersonaSpec.neutral("阿澈"),
                com.luxera.companion.human.mind.relationship.ChatAccountId.of("account-owner"), T);
        return new Mind("test-human", fabric, new PlanBoard(), relationships,
                new MindDecisionPlanner(), counting, PerceptLexicon.generic());
    }

    /** 一个"要做点什么"的念头 —— 它拆得出动作, 所以这次决定是要说话的。 */
    private static ReasoningResult oneThingToDo() {
        Intention intention = ProposedIntention.of("reply", "回一条消息", IntentionPriority.ROUTINE)
                .withActions(List.of(PlanIntent.ActionIntent.of("capability.say",
                        Map.of("text", "好"), "回一句话")));
        return new MindDecisionPlanner().reason(
                ReasoningContext.quiet(T, IntentionContext.minimal(T)).withCandidates(List.of(intention)));
    }

    @Test
    @DisplayName("决定做完之前, 一次都没叫过模型")
    void 决定做完之前一次都没叫过模型() {
        Mind mind = mind();

        Decision decision = mind.decide(oneThingToDo(), IntentionContext.minimal(T));

        assertEquals(0, counting.calls(),
                "decide 里调用了语言引擎 —— 那就是'先问模型要不要回, 再解释它的答案'的老路。"
                        + "这条路的问题不是慢, 而是'她为什么回了'的答案变成了一次不再可复现的模型调用");
        assertTrue(decision.hasActions(), "这条决定是要说话的, 不然这条断言就没有意义");
    }

    @Test
    @DisplayName("决定不需要说话时, 模型一次都不会被叫")
    void 决定不需要说话时模型一次都不会被叫() {
        Mind mind = mind();
        Decision decision = RuleDecision.nothing(DecisionId.of("d-silent"),
                DecisionReason.nothingToDo("此刻没什么要做的"));

        Optional<String> text = mind.speak(decision, "她正在写作业");

        assertTrue(text.isEmpty(), "一个不打算说话的决定竟然产出了措辞");
        assertEquals(0, counting.calls(),
                "'这次不回复'的场景下白跑一次模型往返 —— 这既是浪费, 也让"
                        + "'她今天说了几句话'这个统计里混进了一批空回复");
    }

    @Test
    @DisplayName("决定要说时模型才被叫, 而且拿到的正是那个决定")
    void 决定要说时模型才被叫_而且拿到的正是那个决定() {
        Mind mind = mind();
        Decision decision = RuleDecision.of(DecisionId.of("d-speak"),
                DecisionReason.chose("她决定回一句"),
                List.of(ActionIntent.of("capability.say", Map.of("text", "好"), "回一句话")),
                List.of());

        Optional<String> text = mind.speak(decision, "她刚写完作业");

        assertTrue(text.isPresent());
        assertEquals(1, counting.calls());
        assertSame(decision, counting.lastGiven(),
                "模型拿到的不是那个决定对象 —— 那意味着措辞可能来自别处, "
                        + "而'她的决定'与'她说的话'就不再是同一个东西了");
        assertTrue(text.get().contains("她刚写完作业"), "处境要传进去, 否则措辞里不会有此刻: " + text.get());
    }

    @Test
    @DisplayName("整条链路: 吸收 → 认知 → 决定 → 措辞, 模型始终在最后一步")
    void 整条链路() {
        Mind mind = mind();

        Decision decision = mind.decide(oneThingToDo(), IntentionContext.minimal(T));
        assertEquals(0, counting.calls(), "顺序纪律在整条链路上也必须成立");

        if (decision.needsWording()) {
            mind.speak(decision, "此刻");
            assertEquals(1, counting.calls());
        } else {
            assertEquals(0, counting.calls());
        }
    }
}
