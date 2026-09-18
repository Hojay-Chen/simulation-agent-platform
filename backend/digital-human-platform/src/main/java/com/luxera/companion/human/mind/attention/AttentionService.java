package com.luxera.companion.human.mind.attention;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.mind.percept.Modality;
import com.luxera.companion.human.mind.percept.Percept;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.4.4 —— <b>决定"当前哪些感知值得进入工作认知"</b>。
 *
 * <p>它是"她没注意到"这个状态的<b>唯一合法来源</b>。任何别的地方(队列的丢弃、
 * 感知的未生成、记忆的遗忘)造成的"没反应", 都不是"没注意到" ——
 * 混起来之后, 一句"她没注意到"就再也无法归因了。
 *
 * <h1>边界铁律(§3.4.4, 逐字照做)</h1>
 *
 * <blockquote>
 *   <b>{@code salience} 是消息的属性, 处境是她的属性。两边都打折就是双罚 ——
 *   一个深夜的勿扰消息被罚两次, 于是"她没看见"既无法解释也无法调参。
 *   这条在 V11 Phase 2 已经被踩过一次。</b>
 * </blockquote>
 *
 * <p>这句话在本类里被兑现成一条<b>只有一处乘法</b>的实现:
 *
 * <pre>{@code
 *   score = percept.salience() × ctx.situationFactor(percept.source())
 * }</pre>
 *
 * <p>三个性质, 每一个都有对应的测试钉着:
 * <ol>
 *   <li><b>显著度原样传递</b> —— 本类不重新算 salience, 也不读它的 content 去判断
 *       "这条是不是重要"。它只做一次乘法。</li>
 *   <li><b>处境只乘一次</b> —— 处境的所有因子都收在
 *       {@link AttentionContext#situationFactor} 里面, 本类只调它一次。
 *       <b>打分路径上没有第二处处境乘法</b>: 想加第二个处境系数, 必须先把它塞进
 *       {@code situationFactor}, 而在那里它会与别的因子相乘 —— 那是一处看得见的改动。
 *       (本类确实还读了 {@code taskAttention} 之类, 但那<b>只发生在
 *       {@link #explainMiss} 里</b> —— 那里产出的是一句人话, 不是分数。
 *       把"读处境字段"与"用处境分数"分成两件事, 是因为理由需要细节,
 *       而分数只需要一个数。)</li>
 *   <li><b>不能把显著度算进处境</b> —— 本类拿得到 Percept 的全部字段,
 *       所以这一条<b>不是</b>结构上不可能的, 它是一条纪律。因此它需要一条会红的测试:
 *       同一个处境下, 得分必须与 salience 严格成正比(线性缩放),
 *       而"把 salience 再折算一遍"会破坏这个比例。</li>
 * </ol>
 *
 * <h2>为什么"两处都打折"看起来没什么, 实际上是致命的</h2>
 * 因为折扣是<b>乘性</b>的。深夜(源头罚 0.5)叠加睡着(这里罚 0.05),
 * 一条本来显著度 0.8 的消息得到 0.02 —— 它已经低于任何门槛, 于是"她没看见"。
 * 单看这个结果是对的, 问题出在<b>调参的时候</b>:
 * <ul>
 *   <li>"她怎么老看不见消息?" → 调任务注意力? 但白天的消息也被影响;</li>
 *   <li>调源头的夜间系数? 但那会同时改变队列的排序, 而队列是<b>所有</b>刺激共用的;</li>
 *   <li>于是只能两个一起调, 而每次一起调都会让"她在休息但没睡"那一档偏掉。</li>
 * </ul>
 * 一个数值上正确、但无法单独调参的模型, 在需要拟合行为的时候等于没有模型。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不产生感知</b>。Percept 是 {@link com.luxera.companion.human.mind.percept.Perception}
 *       的产物。所以"她没注意到"永远不是"感知没造出来"。</li>
 *   <li><b>不读正文</b>。它读的是 {@code salience} / {@code urgency} / {@code source} /
 *       {@code modality} —— 打分因素全部来自<b>她已经知道的事实</b>。
 *       注意 {@link Percept#content()} 在本类里<b>一次都没被读</b>:
 *       一旦打分读了 content, "绝不读消息正文"就只剩一句口头承诺,
 *       因为正文迟早会以某种形式出现在 content 里。</li>
 *   <li><b>不决定做什么</b>。注意到 ≠ 行动。那是
 *       {@link com.luxera.companion.human.mind.cognition.MindDecisionPlanner} 的事。</li>
 *   <li><b>不读时钟</b>。时刻由 {@link AttentionContext#now()} 给。</li>
 * </ul>
 *
 * <h2>它为什么<b>不是</b>一个接口</h2>
 * 因为它是一个<b>规则</b>, 而且这条规则被上面那三个性质定义死了 ——
 * 换一个实现就等于换掉规则本身, 而那应当改这个类、连同它的测试一起改。
 * 对比之下, {@code ReasoningEngine} 是接口: 那里"怎么做判断"是真有分歧的
 * (确定性规则 / LLM / 混合), 而这里没有。
 */
@Slf4j
public final class AttentionService {

    private final EventFabric fabric;
    private final double threshold;

    /** 只做判定, 不产生事件。给离线测试与纯函数式使用。 */
    public AttentionService() {
        this(null, AttentionContext.DEFAULT_THRESHOLD);
    }

    public AttentionService(EventFabric fabric) {
        this(fabric, AttentionContext.DEFAULT_THRESHOLD);
    }

    /**
     * @param fabric    注意力转移的落点; 可以为 {@code null}(见 {@code PlanValidator} 的同款取舍:
     *                  没有总线时整件事仍然成立, 只是行为分析看不到它)
     * @param threshold 进入工作认知的门槛
     */
    public AttentionService(EventFabric fabric, double threshold) {
        this.fabric = fabric;
        this.threshold = AttentionContext.clamp01(threshold);
    }

    /**
     * 换一个门槛。<b>测试与对照实验专用</b> —— 它让"如果她门槛低一点会怎样"
     * 有一个可执行的答案, 而不必去改一个常量然后忘了改回来。
     */
    public AttentionService withThreshold(double newThreshold) {
        return new AttentionService(fabric, AttentionContext.clamp01(newThreshold));
    }

    public double threshold() {
        return threshold;
    }

    public boolean recordsToFabric() {
        return fabric != null;
    }

    // ─────────────────────────── 判定 ───────────────────────────

    /**
     * 从一批感知里挑出进入工作认知的那些, 按得分从高到低。
     *
     * <p>顺序是有意义的: 认知环节拿到的是一个<b>有先后的</b>列表,
     * 于是"她在同一刻注意到两件事时先说哪一件"不需要再抽签。
     */
    public List<AttendedPercept> attendAll(List<Percept> percepts, AttentionContext context) {
        Objects.requireNonNull(context, "注意力判断必须带处境 —— 没有处境的打分等于'她永远有空'");
        if (percepts == null || percepts.isEmpty()) {
            return List.of();
        }
        List<AttendedPercept> attended = new ArrayList<>();
        for (Percept percept : percepts) {
            evaluate(percept, context).ifPresent(attended::add);
        }
        attended.sort(Comparator.comparingDouble(AttendedPercept::attentionScore).reversed());
        if (!attended.isEmpty()) {
            publishShift(attended, context);
        }
        return List.copyOf(attended);
    }

    /**
     * 一批感知里<b>最值得注意的那一条</b>。
     *
     * <p>与 §3.4.4 的签名差异: 文档写的是返回一个 {@code AttendedPercept},
     * 这里返回 {@code Optional} —— 因为"一条都没达标"是一个完全正常的结果(她睡着了),
     * 而用它去构造一个"什么都没注意到"的 AttendedPercept 会让下游分不清
     * "她注意到了一个空的东西"和"她什么都没注意到"。
     */
    public Optional<AttendedPercept> attend(List<Percept> percepts, AttentionContext context) {
        List<AttendedPercept> all = attendAll(percepts, context);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** 单条判定。 */
    public Optional<AttendedPercept> attend(Percept percept, AttentionContext context) {
        Objects.requireNonNull(context, "注意力判断必须带处境");
        return evaluate(percept, context);
    }

    /**
     * 判定一条感知。达标则给出 {@link AttendedPercept}, 不达标给出空。
     *
     * <p>不达标时<b>不是什么都不发生</b> —— 调用 {@link #explainMiss} 可以拿到理由。
     * 两件事分开, 是因为"她没注意到"的绝大多数场合不需要解释(否则日志会被灌满),
     * 而需要解释的那几次(行为分析、调试、复现某个决定)会明确地问。
     */
    private Optional<AttendedPercept> evaluate(Percept percept, AttentionContext context) {
        if (percept == null) {
            return Optional.empty();
        }
        if (percept.modality() == Modality.UNKNOWN) {
            // 通道都认不出来 —— 见 Modality 的类注释: 这条刺激仍然被记下, 但永远进不了认知
            log.debug("[Attention] 通道认不出来的刺激被略过: {}", percept.describe());
            return Optional.empty();
        }
        double factor = context.situationFactor(percept.source());
        double score = percept.salience() * factor;
        if (score < threshold) {
            log.debug("[Attention] 未达标 {} —— {}", score, explainMiss(percept, context));
            return Optional.empty();
        }
        String reason = "得分 " + round(score) + " = 显著度 " + round(percept.salience())
                + " × 处境折扣 " + round(factor)
                + "（" + context.situationLabel() + "）"
                + "，达到门槛 " + round(threshold);
        return Optional.of(new AttendedPercept(percept, score, reason, context.now()));
    }

    /**
     * <b>处境折扣的唯一出口。</b>见 {@link AttentionContext#situationFactor}。
     *
     * <p>公开出来是刻意的: 它是"她被罚了多少"的可观测面。一个不愿意公开折扣的模型,
     * 调参时只能靠猜。
     */
    public double situationFactor(AttentionContext context, Percept percept) {
        Objects.requireNonNull(context, "处境不能为空");
        Objects.requireNonNull(percept, "感知不能为空");
        return context.situationFactor(percept.source());
    }

    /**
     * <b>为什么她没注意到这条</b> —— "她没反应"这句诊断的完整答案。
     *
     * <h3>理由必须指向处境, 不能变成对刺激的评价</h3>
     * 这是本方法唯一容易写错的地方, 所以把它写成硬性的:
     * 输出里<b>先</b>说处境(处境是唯一能被改变的那一半 —— 换个时间她就看见了),
     * <b>再</b>说数值。绝不出现"这条不重要"这种句子, 因为:
     * <ul>
     *   <li>那是错的 —— 它可能非常重要;</li>
     *   <li>它把归因引向了错误的变量: 读到这句话的人会去调消息的权值,
     *       而真正该调的是她此刻在做什么。</li>
     * </ul>
     *
     * <h3>"当前目标"为什么不参与打分, 只参与这句话</h3>
     * §3.4.4 把 {@code currentGoals} 列为打分因素之一。本实现让它只出现在理由里,
     * 理由是: 让目标参与打分就必须把目标与刺激对上, 而对上要靠<b>读 content</b> ——
     * 那正是上一条禁令("绝不读消息正文")要多绕一步才能绕开的地方。
     * 一句"她正忙着写作业, 所以没抬头"对行为的解释力, 比多一个系数值钱得多。
     */
    public String explainMiss(Percept percept, AttentionContext context) {
        Objects.requireNonNull(percept, "感知不能为空");
        Objects.requireNonNull(context, "处境不能为空");
        StringBuilder sb = new StringBuilder();
        sb.append("她").append(context.situationLabel()).append(",");
        if (context.taskAttention() > 0.0) {
            sb.append("当前的事占了她 ").append(pct(context.taskAttention())).append(" 的注意力,");
        }
        if (context.notificationFactor() < 1.0) {
            sb.append("她此刻愿意被打断的程度是 ").append(pct(context.notificationFactor())).append(",");
        }
        sb.append("这条").append(percept.modality().label()).append("上的刺激显著度是 ")
                .append(round(percept.salience())).append(",");
        double factor = context.situationFactor(percept.source());
        double score = percept.salience() * factor;
        sb.append("打折后 ").append(round(score)).append(", 没到门槛 ").append(round(threshold));
        if (!context.currentGoals().isEmpty()) {
            sb.append(" —— 她眼下在忙的是: ").append(String.join("、", context.currentGoals()));
        }
        return sb.toString();
    }

    /**
     * 把"她开始注意什么"记进账本。
     *
     * <p>一次判定里只发一条: 注意力是一个<b>状态</b>, 不是一串事件 ——
     * 十条感知同时进入认知不代表她转移了十次注意力。发的是最后那条
     * (也就是注意力真正被拉到的那件事)。
     */
    private void publishShift(List<AttendedPercept> attended, AttentionContext context) {
        if (fabric == null) {
            return;
        }
        AttendedPercept top = attended.get(attended.size() - 1);
        try {
            fabric.publish(AttentionShifted.of(context.now(), "", top.percept().content(),
                    context.taskAttention(), "注意到 " + attended.size() + " 条感知, 其中 "
                            + top.describe()));
        } catch (RuntimeException e) {
            log.error("[Attention] 发布注意力转移事件时出错 —— 判定本身已经完成, 不受影响", e);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String pct(double value) {
        return Math.round(value * 100) + "%";
    }

    public String describe() {
        return "AttentionService[门槛 " + round(threshold) + ", "
                + (fabric == null ? "不产生事件" : "事件可观测") + "]";
    }

    @Override
    public String toString() {
        return describe();
    }
}
