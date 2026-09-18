package com.luxera.companion.human.mind;

import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.attention.AttentionContext;
import com.luxera.companion.human.mind.cognition.IntrusiveThought;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.cognition.ReasoningContext;
import com.luxera.companion.human.mind.decision.Decision;
import com.luxera.companion.human.mind.decision.DecisionMade;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.ProposedIntention;
import com.luxera.companion.human.mind.memory.WorkingMemory;
import com.luxera.companion.human.mind.percept.Modality;
import com.luxera.companion.human.mind.percept.Percept;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.percept.Perception;
import com.luxera.companion.human.mind.percept.SourceRef;
import com.luxera.companion.human.mind.relationship.BindReason;
import com.luxera.companion.human.mind.relationship.ChatAccountId;
import com.luxera.companion.human.mind.relationship.PersonaSpec;
import com.luxera.companion.human.mind.relationship.RelationshipBound;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.2 + §9 验收标准 E —— <b>Mind 不直接访问 World; 正文只在读到之后才存在</b>。
 *
 * <h2>两条被钉住的规矩</h2>
 * <ol>
 *   <li><b>§3.4.2</b>: {@code mind.phone()} / {@code mind.chatApplication()} /
 *       {@code mind.environment()} 三种写法一次都不许出现。这里用反射断言
 *       {@code Mind} 上<b>既没有这些方法, 也没有任何 world/ 类型的字段</b> ——
 *       因为"缺少工具"比"缺少纪律"可靠: 一个想顺手看一眼天气的人,
 *       若手里根本没有那个对象, 他就不会去写那行代码;</li>
 *   <li><b>§9 E</b>: 她<b>不该在读到之前就知道内容</b>。这条在架构上落成一句话:
 *       正文进入工作记忆只有一个成因 —— 一次<b>成功了的</b>动作的结果。
 *       通知响了不算, 动作发出去了不算。</li>
 * </ol>
 *
 * <h2>为什么这两条要写在同一个测试里</h2>
 * 因为它们是同一件事的两面: 若 Mind 能手握世界对象直接读消息, 标准 E 就无从谈起了 ——
 * 没有"读"这个动作, 也就没有"读到之后"这个时刻。分开写会让它们看起来像两条无关的规矩。
 */
class MindNoWorldAccessTest {

    private static final Instant T = Instant.parse("2026-03-02T20:00:00Z");

    /** 一句只在"读到之后"才该出现在她脑子里的正文。 */
    private static final String SECRET = "明天下午三点在实验室碰面";

    private static final ChatAccountId OWNER_ACCOUNT = ChatAccountId.of("account-owner");

    private static Mind mind(EventFabric fabric, LanguageEngine language) {
        RelationshipGraph relationships = RelationshipGraph.bootstrap(
                PersonaSpec.owner("阿澈", "恋人", 0.9, 0.8), OWNER_ACCOUNT, T);
        return new Mind("test-human", fabric, new PlanBoard(), relationships,
                new MindDecisionPlanner(), language, PerceptLexicon.generic());
    }

    private static EventFabric fabric() {
        return new DefaultEventFabric("test-human", new EventHandlerRegistry());
    }

    private static Percept notification() {
        return Perception.percept(Modality.AUDITORY, "设备响了一声", 0.9, 0.5,
                SourceRef.ownDevice("device-1", "她自己的设备"), T);
    }

    private static boolean mentions(Mind mind, String needle) {
        return mind.workingMemory().entries().stream()
                .anyMatch(entry -> entry.content().contains(needle));
    }

    // ─────────────────────── 一、§3.4.2 不直接访问 World ───────────────────────

    @Test
    @DisplayName("Mind 上没有 phone / chatApplication / environment 这三种方法")
    void 没有直接访问世界的方法() {
        Set<String> names = new TreeSet<>();
        for (Method method : Mind.class.getMethods()) {
            names.add(method.getName().toLowerCase());
        }
        for (String forbidden : List.of("phone", "chatapplication", "environment", "device",
                "application", "world", "directory")) {
            for (String name : names) {
                assertFalse(name.contains(forbidden),
                        "Mind 上出现了 " + name + "() —— §3.4.2 明确列出了这种写法是禁止的。"
                                + "她与世界之间唯一的通路是: "
                                + "Mind → Decision → ActionCommand → ActionFabric → Phone → Application");
            }
        }
    }

    @Test
    @DisplayName("Mind 的字段里没有任何 world/ 类型")
    void 字段里没有世界对象() {
        for (Field field : Mind.class.getDeclaredFields()) {
            Package owner = field.getType().getPackage();
            String packageName = owner == null ? "" : owner.getName();
            assertFalse(packageName.startsWith("com.luxera.companion.world"),
                    "Mind 持有了世界对象 " + field.getType().getName() + "(" + field.getName() + ") —— "
                            + "§3.4.2 的第一条禁令。她认识的类型只应当来自 boundary/ 与 human/");
        }
    }

    @Test
    @DisplayName("她认识的是人, 不是账号 —— 账号只是一个字符串")
    void 她认识的是人而不是账号() {
        Mind mind = mind(fabric(), LanguageEngine.silent());

        assertEquals(RelationshipGraph.OWNER, mind.relationships().resolve(OWNER_ACCOUNT)
                .orElseThrow().id());
        assertTrue(mind.relationships().bindings().containsKey(OWNER_ACCOUNT));
        assertTrue(mind.relationships().accountsOf(RelationshipGraph.OWNER)
                .contains(OWNER_ACCOUNT));
    }

    // ─────────────────────── 二、§9 E 正文只在读到之后 ───────────────────────

    @Test
    @DisplayName("通知响了不等于她读到了 —— 正文不会进工作记忆")
    void 通知响了不等于她读到了() {
        Mind mind = mind(fabric(), LanguageEngine.silent());
        AttentionContext context = AttentionContext.neutral(T);

        List<AttendedPercept> attended = mind.absorb(List.of(notification()), context);

        assertFalse(attended.isEmpty(), "这条刺激足够显眼, 她应当注意到");
        assertTrue(mentions(mind, "响了一声"), "她注意到了什么必须留在工作记忆里");
        assertFalse(mentions(mind, SECRET),
                "她还没读, 正文就已经在工作记忆里了 —— §9 验收标准 E 被绕过了。"
                        + "这条约束的整个价值在于: 她<b>不该在读到之前就知道内容</b>");
        assertFalse(mind.snapshot(T).describe().contains(SECRET), "摘要里也不许有正文");
    }

    @Test
    @DisplayName("一次成功的动作结果回来之后, 正文才成为她的一部分")
    void 成功读到了之后正文才进来() {
        Mind mind = mind(fabric(), LanguageEngine.silent());
        mind.absorb(List.of(notification()), AttentionContext.neutral(T));
        assertFalse(mentions(mind, SECRET));

        Optional<WorkingMemory.Entry> admitted =
                mind.admitActionOutcome(
                        ActionResult.succeeded("capability.read", T, Map.of("正文", SECRET)), T);

        assertTrue(admitted.isPresent());
        assertTrue(mentions(mind, SECRET), "读到了却没有记住 —— 那么她下一轮会再问一遍");
    }

    @Test
    @DisplayName("失败的动作不会把正文带进来")
    void 失败的动作不会把正文带进来() {
        Mind mind = mind(fabric(), LanguageEngine.silent());

        Optional<WorkingMemory.Entry> admitted =
                mind.admitActionOutcome(
                        ActionResult.rejected("capability.read", T, "她没有读它的理由"), T);

        assertTrue(admitted.isEmpty(), "没成功的动作竟然产出了工作记忆条目");
        assertFalse(mentions(mind, SECRET));
    }

    // ─────────────────────── 三、她自己做过的事要留痕 ───────────────────────

    @Test
    @DisplayName("她做过的每一次决定都会记一笔 —— 包括决定不做的那次")
    void 决定会被记下来() {
        EventFabric fabric = fabric();
        Mind mind = mind(fabric, LanguageEngine.silent());

        Intention intention = ProposedIntention.of("reply", "回一条消息", IntentionPriority.ROUTINE);
        Decision decision = mind.decide(
                new MindDecisionPlanner().reason(ReasoningContext
                        .quiet(T, IntentionContext.minimal(T))
                        .withCandidates(List.of(intention))),
                IntentionContext.minimal(T));

        List<WorldEvent> events = fabric.recentEvents(20);
        assertTrue(events.stream().anyMatch(e -> e instanceof DecisionMade),
                "决定没有记账 —— 于是'她没回那条消息'与'她没看见那条消息'在数据上长得一样: "
                        + events);
        assertTrue(events.stream().filter(e -> e instanceof DecisionMade)
                        .map(e -> (DecisionMade) e)
                        .anyMatch(e -> e.decisionId().equals(decision.id().value())),
                "记下来的那条事件不是这次决定 —— 事件与决定对不上, 溯源就断了");
    }

    @Test
    @DisplayName("她认识一个人会记一笔, 见过一面不会")
    void 认识会记一笔() {
        EventFabric fabric = fabric();
        Mind mind = mind(fabric, LanguageEngine.silent());
        ChatAccountId stranger = ChatAccountId.of("account-stranger");

        mind.meetAccount(stranger, T);
        assertFalse(fabric.recentEvents(20).stream().anyMatch(e -> e instanceof RelationshipBound),
                "只是见过一面就记了'她认识了某人' —— 那么'她的通讯录是她自己长出来的'"
                        + "这件事就无法用事件流来验证了");

        mind.promoteAccount(stranger, BindReason.inferred("他连着几天晚上都来问同一件事"), T);

        List<WorldEvent> events = fabric.recentEvents(20);
        RelationshipBound bound = events.stream()
                .filter(e -> e instanceof RelationshipBound)
                .map(e -> (RelationshipBound) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("建立绑定之后事件流里什么都没有: " + events));
        assertEquals(stranger, bound.accountId());
        assertTrue(bound.selfMade(), "这条绑定是她自己建立的, 事件必须能说明这一点");
        assertTrue(mind.relationships().knows(stranger));
    }

    @Test
    @DisplayName("装配时预置的那条绑定不算\"自己长出来的\"")
    void 装配时的绑定不算自己长的() {
        EventFabric fabric = fabric();
        Mind mind = mind(fabric, LanguageEngine.silent());

        Optional<RelationshipBound> bootstrap = mind.bindAccount(OWNER_ACCOUNT,
                RelationshipGraph.OWNER, BindReason.bootstrap(), T);

        assertTrue(bootstrap.isPresent());
        assertFalse(bootstrap.get().selfMade(), "装配时给的绑定被算成了自己长出来的");
        assertEquals(0, mind.relationships().selfMadeBindingCount());
    }

    // ─────────────────────── 四、她内部产生的刺激 ───────────────────────

    @Test
    @DisplayName("一个念头自己冒出来时, 没有外部来源对象")
    void 念头没有外部来源() {
        EventFabric fabric = fabric();
        Mind mind = mind(fabric, LanguageEngine.silent());

        mind.raiseIntrusiveThought("那件事我还没做完", 0.6, T);

        IntrusiveThought thought = fabric.recentEvents(10).stream()
                .filter(e -> e instanceof IntrusiveThought)
                .map(e -> (IntrusiveThought) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("念头没有进事件流"));
        assertNull(thought.sourceObjectId(),
                "念头被安上了一个外部来源 —— 与'她自己做的决定'同一条理由: "
                        + "它发生在她心里, 不在世界里");
    }
}
