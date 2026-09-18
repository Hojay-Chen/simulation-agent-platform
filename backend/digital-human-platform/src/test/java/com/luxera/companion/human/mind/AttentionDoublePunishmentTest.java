package com.luxera.companion.human.mind;

import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.attention.AttentionContext;
import com.luxera.companion.human.mind.attention.AttentionService;
import com.luxera.companion.human.mind.percept.Modality;
import com.luxera.companion.human.mind.percept.Percept;
import com.luxera.companion.human.mind.percept.Perception;
import com.luxera.companion.human.mind.percept.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.4 —— <b>边界铁律: 两边都打折就是双罚</b>。
 *
 * <blockquote>
 * {@code salience} 是<b>消息的属性</b>, 处境是<b>她的属性</b>。
 * 两边都打折就是双罚 —— 一个深夜的勿扰消息被罚两次, 于是"她没看见"
 * 既无法解释也无法调参。这条在 V11 Phase 2 已经被踩过一次。
 * </blockquote>
 *
 * <h2>这些断言要钉住的到底是什么</h2>
 * 不是"分数算得对不对"(那个数值可以调), 而是<b>分数里处境出现了几次</b>。
 * 双罚的实现能跑、能编译、数值看起来也很合理 —— 它毁掉的只有一件事:
 * 调参时不知道该调哪个旋钮。所以这里的断言刻意写成"恰好乘了一次"的形式:
 *
 * <ul>
 *   <li>{@link #分数恰好是显著度乘一次处境折扣()} —— 严格相等, 不是近似;</li>
 *   <li>{@link #处境不是被乘了两次()} —— 直接断言分数<b>不等于</b>双罚的结果。
 *       这一条是专门写给未来的: 若有人把 {@code situationFactor} 顺手乘两遍,
 *       上面那条会红, 而这条会说清楚红的原因;</li>
 *   <li>{@link #处境因子永远不可能把感知抬上去()} —— 处境只能让感知更难进来。</li>
 * </ul>
 *
 * <h2>时刻约定</h2>
 * 全部用固定的仿真时刻(深夜 23:40), <b>不读系统时钟</b> —— 与 {@code human/} 的纪律一致。
 */
class AttentionDoublePunishmentTest {

    private static final Instant T_NIGHT = Instant.parse("2026-03-02T23:40:00Z");

    /** 一条来自她自己设备的听觉刺激: 显著度 0.5(二进制精确), 便于严格相等。 */
    private static Percept ding() {
        return Perception.percept(Modality.AUDITORY, "设备响了一声", 0.5, 0.5,
                SourceRef.ownDevice("device-1", "她自己的设备"), T_NIGHT);
    }

    private static AttentionContext context(double taskAttention, double willing) {
        return AttentionContext.of(T_NIGHT, "她在睡觉", taskAttention, willing,
                AttentionContext.Relevance.undiscounted(), List.of());
    }

    /**
     * 一个门槛为 0 的服务 —— 任何分数都会被放行。
     *
     * <p><b>门槛只在服务上, 不在处境上。</b>这条取舍见 {@code AttentionContext} 里
     * 关于 {@code DEFAULT_THRESHOLD} 的说明: 它曾经两边各有一份, 于是
     * "我把门槛调到 0 了, 她怎么还是没注意到"成了一个只能靠读实现来回答的问题。
     */
    private static AttentionService openDoor() {
        return new AttentionService(null, 0.0);
    }

    @Test
    @DisplayName("分数恰好是 显著度 × 一次处境折扣 —— 严格相等")
    void 分数恰好是显著度乘一次处境折扣() {
        Percept percept = ding();
        AttentionContext context = context(0.5, 0.5);
        double factor = context.situationFactor(percept.source());

        Optional<AttendedPercept> attended = openDoor().attend(percept, context);

        assertTrue(attended.isPresent(), "门槛是 0, 它必须被放行");
        assertEquals(percept.salience() * factor, attended.get().attentionScore(), 0.0,
                "注意力分数必须恰好等于 显著度 × 处境折扣。多乘一次少乘一次都会让这条红");
    }

    @Test
    @DisplayName("处境不是被乘了两次")
    void 处境不是被乘了两次() {
        Percept percept = ding();
        AttentionContext context = context(0.5, 0.5);
        double factor = context.situationFactor(percept.source());

        double score = openDoor().attend(percept, context).orElseThrow().attentionScore();
        double doublePunished = percept.salience() * factor * factor;

        assertNotEquals(doublePunished, score, 0.0,
                "分数正好等于 显著度 × 处境折扣² —— 这就是双罚。"
                        + "它的现象是: 深夜的勿扰消息被罚两次, 于是'她怎么没看见'"
                        + "既无法解释(两个系数都在说同一件事)也无法调参");
    }

    @Test
    @DisplayName("处境变一半, 分数也正好变一半 —— 它是一个乘法, 不是一串判断")
    void 处境是线性的一把旋钮() {
        Percept percept = ding();
        AttentionService service = openDoor();

        // 空闲度 0.5 × 愿意被打断 1.0 × 相关度 1.0 = 0.5
        double first = service.attend(percept, context(0.5, 1.0)).orElseThrow().attentionScore();
        // 空闲度 0.25 × 1.0 × 1.0 = 0.25
        double second = service.attend(percept, context(0.75, 1.0)).orElseThrow().attentionScore();

        assertEquals(2.0, first / second, 0.0,
                "处境折扣减半之后分数没有正好减半 —— 说明分数里还有别的处境项在参与, "
                        + "而每一项都会让'该调哪个旋钮'变得无法回答");
    }

    @Test
    @DisplayName("她没注意到时, 理由先讲处境, 绝不评价那条消息")
    void 没注意到时的理由指向处境() {
        Percept percept = ding();
        // 处境不打折(她闲着、也愿意被打断), 但门槛被抬到分数之上 —— 这样落进的正是
        // "刺激本身不够显眼"这一支, 而不是"她太忙"那一支
        AttentionContext context = context(0.0, 1.0);
        AttentionService service = new AttentionService(null, 0.9);

        assertTrue(service.attend(percept, context).isEmpty(), "这条本来就该没被注意到");

        String reason = service.explainMiss(percept, context);

        assertTrue(reason.startsWith("她"), "理由必须先把处境说完: " + reason);
        assertTrue(reason.contains("睡觉"), "理由里必须出现处境: " + reason);
        assertTrue(reason.contains("显著度"), "理由里必须带上数值, 否则调参只能靠猜: " + reason);
        assertFalse(reason.contains("不重要"),
                "理由里出现了对消息的评价。它是错的(那条消息可能非常重要), "
                        + "而且会把归因引向错误的变量 —— 该调的是她此刻在做什么: " + reason);
    }

    @Test
    @DisplayName("处境因子永远不可能把感知抬上去")
    void 处境因子永远不可能把感知抬上去() {
        Percept percept = ding();
        double[][] cases = {{0.0, 1.0}, {0.5, 0.5}, {0.9, 0.2}, {1.0, 0.0}, {0.3, 1.0}};
        for (double[] one : cases) {
            double factor = context(one[0], one[1]).situationFactor(percept.source());
            assertTrue(factor >= 0.0 && factor <= 1.0,
                    "处境折扣跑到 (0, 1] 之外了: " + factor
                            + " —— 一个能抬高刺激的处境因子会让'她没注意到'无法归因");
        }
    }

    @Test
    @DisplayName("门槛只有一个来源 —— 它是服务的策略, 不是处境的字段")
    void 门槛只有一个来源() {
        assertEquals(AttentionContext.DEFAULT_THRESHOLD, new AttentionService().threshold(), 0.0,
                "服务的默认门槛与那个常量对不上 —— 于是'默认门槛是多少'有两个答案");
        assertEquals(0.9, new AttentionService(null, 0.9).threshold(), 0.0);

        for (java.lang.reflect.RecordComponent component
                : AttentionContext.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("threshold"),
                    "处境上又出现了门槛字段 " + component.getName()
                            + " —— 同一个旋钮两个来源: 调了一个, 另一个还在生效, 而且不报错。"
                            + "这与 §3.4.4 那条铁律防的是同一种病");
        }
    }

    @Test
    @DisplayName("处境折扣只有一个出口 —— 公开出来, 因为它是可观测面")
    void 处境折扣只有一个出口() {
        Percept percept = ding();
        AttentionContext context = context(0.25, 0.5);
        AttentionService service = new AttentionService();

        assertEquals(context.situationFactor(percept.source()),
                service.situationFactor(context, percept), 0.0,
                "AttentionService 里的折扣与 AttentionContext 里的算法不一致 —— "
                        + "两个算法迟早会在某一个场景下分开, 而那时谁对谁错无法判定");
    }
}
