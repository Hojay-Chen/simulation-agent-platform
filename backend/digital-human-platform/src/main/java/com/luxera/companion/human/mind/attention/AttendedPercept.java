package com.luxera.companion.human.mind.attention;

import com.luxera.companion.human.mind.percept.Percept;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.4 —— <b>一条真的进入了工作认知的感知</b>, 以及"为什么是它"。
 *
 * <h2>为什么它必须带上 {@code reason}</h2>
 * 这个平台要能回答的问题里, 最难、最常被问到的是<b>"她怎么没回"</b>。
 * 它的反面(她为什么回了)可以从行为本身看出来, 而"没有发生的事"没有任何痕迹 ——
 * 除非每一次"没进认知"都留下了理由。
 *
 * <p>{@link AttentionService#explainMiss} 负责那些<b>没被选中</b>的,
 * 这里负责被选中的: 于是"她今天只注意到三条刺激"这句话, 可以展开成三条理由。
 * 两条路径合起来, 才让"她没注意到"成为<b>可解释</b>而不是<b>不可知</b>的。
 *
 * <h2>理由必须指向处境, 而不是给刺激下评价</h2>
 * <pre>
 *   ✅ "她在睡觉(注意力占用 0.95), 这条刺激的得分 0.12 低于门槛 0.35"
 *   ❌ "这条消息不重要"
 * </pre>
 * 后一句是错的, 而且错得很危险: 那条消息可能非常重要。
 * 没被注意到的是<b>她此刻不在能接收的状态</b>, 不是那条消息的价值。
 * 这两件事在日志里一旦混起来, 行为分析会得出"她在乎的人发的消息她都不看"这种结论 ——
 * 而真相只是她睡了。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不修改感知</b>。Percept 原样带过来, 一个字都不动。</li>
 *   <li><b>不是"已读"</b>。进入工作认知离"她读懂了"还有好几步(§2.3 的意识阶梯)。</li>
 *   <li><b>不带正文</b>。它带的是 Percept, 而 Percept 里装不下正文。
 *       正文只会在一次成功的读取动作回来之后进入工作记忆(§9 验收标准 E)。</li>
 * </ul>
 */
public record AttendedPercept(
        Percept percept,
        double attentionScore,
        String reason,
        Instant attendedAt) {

    public AttendedPercept {
        Objects.requireNonNull(percept, "被注意到的感知不能为空");
        Objects.requireNonNull(reason, "必须说明为什么注意到了它 —— "
                + "理由为空的注意力记录会在归因时变成一句'反正就是注意到了', 等于没记");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "注意力的理由不能是空白串 —— 没有理由时请把打分过程写进去, 但不要留空");
        }
        Objects.requireNonNull(attendedAt, "注意力判定必须带仿真时刻 —— 不许读系统时钟");
    }

    public String describe() {
        return "注意到 " + percept.modality().label() + " 上的 " + percept.id()
                + "(得分 " + Math.round(attentionScore * 100) / 100.0 + ") —— " + reason;
    }

    @Override
    public String toString() {
        return describe();
    }
}
