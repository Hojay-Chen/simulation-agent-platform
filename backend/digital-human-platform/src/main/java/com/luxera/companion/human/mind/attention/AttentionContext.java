package com.luxera.companion.human.mind.attention;

import com.luxera.companion.human.mind.percept.SourceRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.4.4 —— <b>她此刻的处境</b>。注意: 是<b>她</b>的, 不是消息的。
 *
 * <h2>这个类存在的全部理由: 把"处境"收进一个对象, 于是它只能被乘一次</h2>
 * §3.4.4 的边界铁律原文:
 *
 * <blockquote>
 *   <b>{@code salience} 是消息的属性, 处境是她的属性。两边都打折就是双罚 ——
 *   一个深夜的勿扰消息被罚两次, 于是"她没看见"既无法解释也无法调参。
 *   这条在 V11 Phase 2 已经被踩过一次。</b>
 * </blockquote>
 *
 * <p>"双罚"长什么样? 具体到可以写出来:
 * <pre>
 *   // ❌ 双罚: 深夜这条消息在源头被罚了一次(时间戳进了 salience),
 *   //    在这里又因为"她在睡觉"被罚一次
 *   double s = percept.salience() * quietHoursFactor(now) * (1.0 - ctx.taskAttention());
 *
 *   // ❌ 另一种双罚: 同一个原因换两个名字, 各乘一次 —— 深夜不该被打扰这件事
 *   //    被"勿扰时段"与"设备静音"各表达了一次
 *   double s = percept.salience() * quietHoursFactor(now) * dndFactor(now);
 * </pre>
 * 两种写法都能编译, 都能跑, 数值看起来也"挺合理"。它们毁掉的是<b>可调参性</b>:
 * 当"她怎么没看见这条消息"需要调的时候, 你无法知道该调哪一个 —— 因为两个系数
 * 都在说"她在忙", 而它们对结果的影响是同一个。于是唯一的出路是把两个一起动,
 * 而每一次一起动都会让另一个场景(她在休息但没睡)偏掉。
 *
 * <h2>本设计的做法: 处境有且只有一个出口 —— {@link #situationFactor(SourceRef)}</h2>
 * {@code AttentionService} 只会从这个方法取一个数, 乘一次。它<b>不</b>去分别读
 * {@link #taskAttention()} 之类的字段。于是"处境被打了几折"在代码里是一处,
 * 在日志里是一个数, 在调参时是一个旋钮。
 *
 * <h3>与 §3.4.4 的一处措辞差异 —— {@code notificationFactor} 到底是什么</h3>
 * §3.4.4 把它写成"手机免打扰状态(dnd → 0.0)"。这里<b>不</b>取那个意思, 理由是它会造成双罚:
 * 手机被静音时, <b>那条刺激根本不会产生</b>(没有音量就没有声音, 没有声音就没有
 * {@code SensoryEvent}, 也就没有 Percept)。所以"手机静音"这件事已经体现在
 * "有没有感知"上了; 若在这里再乘一个 0, 就是同一个事实被计了两次。
 *
 * <p>这里取的是另一个意思, 也是更该由 Mind 持有的那个: <b>她此刻愿不愿意被手机打断</b>。
 * 一个正在专注的人会让这个值低, 一个在等人回话的人会让它高 —— 它是她的意愿,
 * 不是设备的设置。设备那一半由 World 负责(音量、震动), 且它作用在"刺激有多强"上。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不读正文</b>。这个对象上根本没有正文, 也没有账号 —— 见 {@link SourceRef} 的禁令。</li>
 *   <li><b>不做判断</b>。它只是处境的<b>载体</b>; 打分是 {@link AttentionService} 的事。</li>
 *   <li><b>不含"当前目标"的权值</b>。见 {@link #currentGoals()}。</li>
 * </ul>
 */
public record AttentionContext(
        Instant now,
        String situation,
        double taskAttention,
        double notificationFactor,
        Relevance relevance,
        List<String> currentGoals) {

    /**
     * 进入工作认知的默认门槛。低于它的感知会被记下、但不会进入认知。
     *
     * <p><b>注意它是常量, 不是本记录的一个字段</b> —— 这条取舍值得说清楚, 因为它
     * 一开始不是这样: 门槛曾经是本记录的一个组件, 而 {@code AttentionService}
     * 同时也持有自己的一个门槛。结果是<b>同一个旋钮有两个来源</b>:
     * 调了 context 上那个, 判定却按 service 上那个走, 而且不报错、不警告 ——
     * 表现为"我把门槛调到 0 了, 她怎么还是没注意到", 排查时只能靠读实现。
     *
     * <p>这与 §3.4.4 那条铁律防的是同一种病: <b>同一个原因被两个旋钮各表达一次,
     * 于是"她为什么没注意到"没有唯一答案。</b>所以门槛只留在
     * {@link AttentionService#withThreshold(double)} 上 —— 那是服务自己的策略
     * (它决定"多少次算够"), 而处境只回答"她现在好不好被叫动"。
     */
    public static final double DEFAULT_THRESHOLD = 0.35;

    /** 相关性无知识时的默认值 —— 表示"不额外打折", 而不是"很有关系"。 */
    public static final double NEUTRAL_RELEVANCE = 1.0;

    public AttentionContext {
        Objects.requireNonNull(now, "注意力判断必须带仿真时刻 —— 处境是某一刻的。"
                + "时刻一律由调用方传入: human/ 里不许读系统时钟");
        situation = situation == null ? "" : situation;
        Objects.requireNonNull(relevance, "相关性函数不能为 null —— 没有关系知识时请用 Relevance.undiscounted()");
        currentGoals = currentGoals == null ? List.of() : List.copyOf(currentGoals);
        taskAttention = clamp01(taskAttention);
        notificationFactor = clamp01(notificationFactor);
    }

    /**
     * 最省事的处境: 不忙、愿意被打断、不认识任何来源。给测试与"还没有生活状态"的早期装配用。
     *
     * <p>它<b>不是</b>一个合理的生产默认值 —— 它意味着"她永远有空且谁找她都一样"。
     * 名字里带 {@code neutral} 而不是 {@code default}, 就是为了让人在装配代码里看到它时
     * 多想一下。
     */
    public static AttentionContext neutral(Instant now) {
        return new AttentionContext(now, "", 0.0, 1.0, Relevance.undiscounted(), List.of());
    }

    public static AttentionContext of(Instant now, String situation,
                                      double taskAttention, double notificationFactor,
                                      Relevance relevance, List<String> currentGoals) {
        return new AttentionContext(now, situation, taskAttention, notificationFactor,
                relevance, currentGoals);
    }

    /**
     * <b>处境折扣的唯一出口。</b>
     *
     * <p>它是一个乘法: 她有多空 × 她多愿意被打断 × 来源与她的关系。三个因子都是
     * {@code (0, 1]}, 所以结果也在 {@code (0, 1]} —— 处境<b>只可能让感知更难进入认知,
     * 不可能让它更容易</b>。这一条也是刻意的: 一个能把低显著度刺激"抬"进认知的处境因子,
     * 会让"她没注意到"这件事无法归因(到底是刺激太弱, 还是处境把它抬上来又被别的东西压下去?)。
     *
     * <p>来源只被用在相关性上, 而相关性是"她对这个来源的知识"
     * (她的通讯录里这个人是什么关系), 不是对这条刺激的<b>再</b>折扣。
     */
    public double situationFactor(SourceRef source) {
        double free = 1.0 - taskAttention;
        double willing = notificationFactor;
        double closeness = clamp01(relevance.of(source));
        return clamp01(free * willing * closeness);
    }

    /** 处境的一句话描述 —— 它会被原样抄进 {@link AttendedPercept#reason()}。 */
    public String situationLabel() {
        return situation.isEmpty() ? "此刻" : situation;
    }

    static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    /**
     * <b>来源与她的关系</b> —— 她的知识, 不是世界的属性。
     *
     * <p>它被做成函数而不是一个标量, 因为"相关度"必须逐来源地算:
     * 同一批感知里, 来自她主人的那一条与她完全没见过的那个账号的那一条,
     * 处境折扣不该相同。做成标量就等于宣布"所有来源一视同仁",
     * 而那会让 {@code RelationshipGraph} 在注意力这一环上彻底失效 ——
     * 于是她会在深夜为一个陌生人破例, 却对主人视而不见。
     *
     * <p>返回值取 {@code [0, 1]}: 0 = 与她毫无关系(几乎不可能被注意到),
     * 1 = 最亲近的人(处境不打这一项的折)。
     */
    @FunctionalInterface
    public interface Relevance {

        double of(SourceRef source);

        /**
         * 不额外打折 —— 用于"她还没有通讯录"或"这一项不参与判断"。
         *
         * <p>注意它返回 {@link #NEUTRAL_RELEVANCE}(即 1.0), 这代表
         * "<b>没有理由因为来源而降低她的注意</b>", 而不是"这个人很重要"。
         * 这两种含义在数值上相同、在意图上相反, 所以这里用名字而不是注释来区分。
         */
        static Relevance undiscounted() {
            return source -> NEUTRAL_RELEVANCE;
        }

        /** 恒定值 —— 用于"用一次实验把相关性整体调到 0.6 看看"这种对照实验。 */
        static Relevance constant(double value) {
            double clamped = clamp01(value);
            return source -> clamped;
        }
    }
}
