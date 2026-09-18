package com.luxera.companion.human.mind;

import com.luxera.companion.human.mind.decision.ActionIntent;
import com.luxera.companion.human.mind.decision.Decision;
import com.luxera.companion.human.mind.decision.DecisionId;
import com.luxera.companion.human.mind.decision.DecisionReason;
import com.luxera.companion.human.mind.decision.RuleDecision;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.7 —— <b>Decision 是结果对象, 不是枚举</b>。
 *
 * <h2>被钉住的那句话, 以及它防的三件事</h2>
 * 旧实现把"她决定做什么"表达成一档枚举({@code DO_NOTHING} / {@code REPLY} / {@code DEFER})。
 * 本测试要保证的三件事, 正是那种写法做不到的:
 *
 * <ol>
 *   <li><b>"她决定不做"是一个决定, 不是一个空。</b>
 *       {@link #决定不做也是一个决定()} —— 没有这条, "她没回"与"她没看见"在数据上一样;</li>
 *   <li><b>"继续做当前的事"必须能表达。</b>{@link DecisionReason#CODE_KEPT_CURRENT}
 *       与 {@code PlanMutation.KeepActive} 一起才构成这个表达 —— 文档专门说了为什么;</li>
 *   <li><b>决定带着"要做什么"的清单走。</b>一个常量塞不进"回复谁、回什么、
 *       要不要顺便改计划", 于是那些东西会散在别处。</li>
 * </ol>
 */
class DecisionShapeTest {

    @Test
    @DisplayName("决定是接口, 不是枚举")
    void 决定是接口() {
        assertTrue(Decision.class.isInterface(),
                "Decision 必须是接口 —— 它是结果对象的形状, 不是一档取值");
        assertFalse(Decision.class.isEnum(), "决定不是枚举");
    }

    @Test
    @DisplayName("决定不做也是一个决定 —— 它有自己的理由, 不是空对象")
    void 决定不做也是一个决定() {
        Decision decision = RuleDecision.nothing(
                DecisionId.of("d-1"), DecisionReason.nothingToDo("此刻没什么要做的"));

        assertFalse(decision.hasActions(), "不做就是不做, 不该有动作");
        assertFalse(decision.changesPlan(), "她什么都不做, 计划当然不动");
        assertFalse(decision.needsWording(), "决定不说话时, 语言引擎根本不该被调用");
        assertEquals(DecisionReason.CODE_NOTHING_TO_DO, decision.reason().code());
        assertFalse(decision.reason().narrative().isBlank(),
                "理由必须有一句人话 —— 否则'她为什么没回'只能靠读代码回答");
    }

    @Test
    @DisplayName("继续做当前的事也是一个决定 —— 而且它必须与\"什么都没发生\"能分开")
    void 继续当前的事也是一个决定() {
        Decision decision = RuleDecision.keeping(DecisionId.of("d-1b"),
                DecisionReason.keptCurrent("她收到了, 看过了, 继续写作业"),
                new PlanMutation.KeepActive(PlanItemId.of("homework"), "这件事不足以打断她"));

        assertFalse(decision.hasActions(), "继续意味着没有新动作");
        assertTrue(decision.changesPlan(),
                "但计划上必须有一次明确的'保持'。没有 KeepActive 时, 重排器只能靠"
                        + "'什么都不做'来表达'继续', 而'什么都不做'同时也意味着'我没处理这个事件'");
        assertFalse(decision.needsWording());
        assertTrue(decision.planMutations().get(0).describe().contains("homework")
                        || decision.planMutations().get(0).describe().contains("不足以打断"),
                "计划改动必须能被读出来: " + decision.planMutations().get(0).describe());
    }

    @Test
    @DisplayName("决定带着动作清单走: 谁、做什么、为什么")
    void 决定带着动作清单() {
        ActionIntent action = ActionIntent.of("capability.say", Map.of("text", "好的"),
                "给她回一句话");
        Decision decision = RuleDecision.of(DecisionId.of("d-2"),
                DecisionReason.chose("她决定回一句"), List.of(action), List.of());

        assertTrue(decision.hasActions());
        assertTrue(decision.needsWording(), "有动作的决定默认要说点什么");
        assertEquals(1, decision.actions().size());
        assertEquals("capability.say", decision.actions().get(0).capabilityKey());
        assertTrue(decision.actions().get(0).arguments().containsKey("text"));
    }

    @Test
    @DisplayName("动作意图不是动作命令 —— 它没有 id、没有时刻、没有幂等键")
    void 动作意图不是动作命令() {
        ActionIntent action = ActionIntent.of("capability.say", "回一句话");

        assertFalse(action.getClass().getSimpleName().contains("Command"),
                "两个类型合并之后, '她决定要做'与'这件事已经发出去了'会变成同一个瞬间, "
                        + "而中间的失败与重试就没有地方待了");
        for (java.lang.reflect.Method method : ActionIntent.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("idempot"),
                    "动作意图上出现了幂等键 —— 那是'发出'这件事的属性, 不是'决定'的属性");
            assertFalse(name.contains("issuedat") || name.contains("commandid"),
                    "动作意图上出现了发送时刻/命令 id —— 见 ActionIntent 的类注释");
        }
    }

    @Test
    @DisplayName("可选动作做不成, 不影响这次决定是否成立")
    void 可选动作() {
        ActionIntent optional = ActionIntent.optionalOf("capability.notify", "顺手把屏幕点亮",
                "她只是想看一眼时间");

        assertTrue(optional.optional());
        assertFalse(ActionIntent.of("capability.say", "回一句话").optional(),
                "默认必须是不可选: 让每个动作默认可选, 会让'她今天有 40% 的决定失败'"
                        + "这个统计里混进一堆'没把屏幕点亮'");
    }

    @Test
    @DisplayName("计划侧的动作与执行侧的动作之间只有一个转换点")
    void 转换点只有一个() {
        PlanIntent.ActionIntent plan = PlanIntent.ActionIntent.of("capability.say",
                Map.of("text", "好"), "回一句话");

        ActionIntent converted = ActionIntent.fromPlanAction(plan, "她决定回一句");

        assertEquals(plan.capabilityKey(), converted.capabilityKey());
        assertEquals(plan.arguments(), converted.arguments());
        assertEquals("她决定回一句", converted.reason(), "转换时补上的正是计划侧不需要的那一句'为什么'");
        assertEquals(plan.capabilityKey(), converted.toPlanAction().capabilityKey());
    }

    @Test
    @DisplayName("没有理由的决定造不出来")
    void 没有理由的决定造不出来() {
        assertThrows(NullPointerException.class,
                () -> new RuleDecision(DecisionId.of("d-3"), null, List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new RuleDecision(null, DecisionReason.nothingToDo("没什么要做的"),
                        List.of(), List.of()));
    }
}
