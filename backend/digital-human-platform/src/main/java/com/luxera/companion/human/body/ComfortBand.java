package com.luxera.companion.human.body;

import com.luxera.companion.registry.CoreEventCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §3.2.3 —— <b>一条生理通道的舒适带</b>: 什么算正常, 什么算越界, 以及多快算"掉得太快"。
 *
 * <h2>为什么它是一个值对象, 而不是散落的 {@code if (warmth < 0.45)}</h2>
 * 因为阈值不是一个数, 而<b>一组互相约束的数</b>。逐个看:
 * <table border="1">
 *   <tr><th>分量</th><th>回答的问题</th><th>少了它会发生什么</th></tr>
 *   <tr>
 *     <td>{@code low} / {@code high}</td>
 *     <td>越界了吗</td>
 *     <td>没有带, 就只有"越大越好"或"越小越好" —— 而体温是<b>两头都不好</b>的,
 *         中暑与失温在这套模型里必须是对称的两种越界</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mid}</td>
 *     <td>身体往哪里回</td>
 *     <td>没有回中点, 稳态模型就只能"停在当前值" —— 于是她一旦掉到 0.44 就永远停在 0.44,
 *         而真人会打哆嗦、会缩起来, 把体温拉回来</td>
 *   </tr>
 *   <tr>
 *     <td>{@code hysteresis}</td>
 *     <td>什么时候算"缓过来了"</td>
 *     <td>没有回差, 阈值附近的抖动会让"觉得冷 / 不冷了 / 觉得冷"每 tick 翻一次 ——
 *         这就是 {@code TickAware} 类注释里警告的那个刷屏问题</td>
 *   </tr>
 *   <tr>
 *     <td>{@code alarmingRatePerSecond}</td>
 *     <td>掉得多快算危险</td>
 *     <td><b>用户明确要求的第二条路径</b>: "变化产生的 diff …… 也能形成一个实时 event"。
 *         她还<b>没</b>越界, 但 30 秒里掉了 0.2 —— 这必须现在就惊动她,
 *         因为等越界就来不及加衣服了</td>
 *   </tr>
 * </table>
 *
 * <h2>{@code mid} 是"适中值", <b>不是</b>算术中点</h2>
 * 这一点值得单独说明, 因为它是本类最容易理解错的地方。
 * 若 {@code mid = (low + high) / 2}, 那么饥饿通道({@code low = 0, high = 0.65})的
 * 适中值会是 0.325 —— 于是"身体会把她往 0.325 的饥饿度回拉",
 * <b>也就是让她自己变饿</b>。这在生理上完全说不通。
 *
 * <p>真人是: 吃饱之后饥饿度停在低位(0.1 左右), 而不是停在"舒适带的正中间"。
 * 所以 {@code mid} 是<b>稳态的目标值</b>, 它由生理决定, 与舒适带的宽窄无关。
 * 对于双向通道(体温、湿度、压力)它通常接近中点, 对于单向通道(饿、渴、累、痛)
 * 它靠近"没有这个感觉"的那一端。
 *
 * <h2>为什么它是 {@code record} 而不是类</h2>
 * 因为它是<b>纯值</b>: 两个 {@code ComfortBand} 只要五个数一样就是同一条带,
 * 没有任何身份。这一点与 {@code PhysiologicalState} 相同而与 {@code Body} 相反 ——
 * {@link Body} 有身份(是"她"), 而带没有。
 */
public record ComfortBand(String channel,
                          double low,
                          double high,
                          double mid,
                          double hysteresis,
                          double alarmingRatePerSecond) {

    /** 越界程度归一化的下极 —— 所有生理通道都被约定归一化到 {@code [0, 1]}。 */
    private static final double FLOOR = 0.0;
    /** 越界程度归一化的上极。 */
    private static final double CEILING = 1.0;

    public ComfortBand {
        Objects.requireNonNull(channel, "舒适带必须知道自己守的是哪条通道");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("通道名不能为空白");
        }
        requireUnit(low, "舒适下沿");
        requireUnit(high, "舒适上沿");
        requireUnit(mid, "适中值");
        if (low > high) {
            throw new IllegalArgumentException(
                    "舒适下沿(" + low + ")不能高于上沿(" + high + ") —— 那是一条永远为空的舒适带, 表现是她永远不舒服");
        }
        if (mid < low || mid > high) {
            throw new IllegalArgumentException(
                    "适中值 " + mid + " 落在舒适带 [" + low + ", " + high + "] 之外 —— "
                            + "那意味着身体的回归方向指向带外, 她会稳定地停在一个不舒服的地方");
        }
        if (hysteresis < 0.0) {
            throw new IllegalArgumentException("回差不能为负, 收到 " + hysteresis);
        }
        if (alarmingRatePerSecond < 0.0) {
            throw new IllegalArgumentException("危险变化率不能为负 —— 它只是一个阈值, 方向由通道的语义决定");
        }
    }

    private static void requireUnit(double value, String what) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(what + "必须归一化到 [0, 1], 收到 " + value);
        }
    }

    // ─────────────────────────── 第一条路径: 越界了吗 ───────────────────────────

    /** 在舒适带内 —— <b>两端都算</b>(闭区间)。 */
    public boolean inside(double value) {
        return value >= low && value <= high;
    }

    /** 低于下沿 —— 保暖不足、精力不足、睡得太少都是这一侧。 */
    public boolean below(double value) {
        return value < low;
    }

    /** 高于上沿 —— 过热、太湿、太累、太饿、太痛都是这一侧。 */
    public boolean above(double value) {
        return value > high;
    }

    /**
     * 越界方向: {@code -1} 偏低, {@code +1} 偏高, {@code 0} 在带内。
     *
     * <p>返回一个 int 而不是两个 boolean, 是为了让检测器的状态只能处于三种之一 ——
     * 两个独立的 boolean 允许"既偏低又偏高"这个不可能的状态存在。
     */
    public int side(double value) {
        if (below(value)) {
            return -1;
        }
        return above(value) ? 1 : 0;
    }

    /**
     * <b>"低于适中值的程度"</b> —— 用户要求的那一条, 归一化到 {@code [0, 1]}。
     *
     * <h3>归一化的分母为什么是"到极值还有多远", 而不是"带宽"</h3>
     * 若按带宽归一化({@code (low - v) / (mid - low)}), 那么越界程度会在她真正危险之前
     * 就撞到 1.0 —— 于是"冷"和"快冻死了"在强度上无法区分。
     * 按到极值的余量归一化({@code (low - v) / (low - FLOOR)}), 强度会随危险程度<b>单调增长</b>:
     * <pre>{@code
     *   warmth 0.45 (刚好下沿)  → 0.00
     *   warmth 0.28             → (0.45-0.28)/0.45 = 0.38
     *   warmth 0.10             → 0.78
     *   warmth 0.00 (极值)      → 1.00
     * }</pre>
     * 两侧共用同一套几何: 偏高时用 {@code (v - high) / (CEILING - high)}。
     *
     * <p>带内返回 0.0 —— 而不是负数或"距 mid 的距离"。理由: 这个数唯一的用途是
     * <b>越界刺激的强度</b>(见 {@code ThresholdDetector}), 而"在舒适带内"这件事
     * 不产生任何刺激, 所以它的强度必须是 0。
     */
    public double deviation(double value) {
        if (below(value)) {
            return clamp01((low - value) / Math.max(low - FLOOR, EPS));
        }
        if (above(value)) {
            return clamp01((value - high) / Math.max(CEILING - high, EPS));
        }
        return 0.0;
    }

    /**
     * 越界之后要回到哪里才算"缓过来了" —— <b>回差</b>的落点。
     *
     * <p>它是 {@code ThresholdDetector} 唯一需要用来消抖的数: 一旦偏离,
     * 必须恢复到 {@code low + hysteresis} 之上(偏低侧)才算解除,
     * <b>而不是一越过 {@code low} 就立刻解除</b>。
     * 少了这一步, 她会在阈值附近反复地"刚觉得冷 / 又好了 / 又觉得冷"。
     */
    public double releaseValue(int side) {
        if (side < 0) {
            return Math.min(high, low + hysteresis);
        }
        if (side > 0) {
            return Math.max(low, high - hysteresis);
        }
        return mid;
    }

    // ─────────────────────────── 第二条路径: 掉得太快 ───────────────────────────

    /**
     * 她还没越界, 但<b>正在快速恶化</b> —— 用户要求的第二条触发路径。
     *
     * <h3>它与第一条路径的区别, 以及为什么两条都必须有</h3>
     * <pre>{@code
     *   路径一 (阈值越界): warmth 0.28 < low 0.45  → 离散, 已经发生的事
     *   路径二 (变化率)  : warmth 每秒掉 0.012 > 0.006 → 连续, 正在发生的事
     * }</pre>
     * 只做路径一, 会得到一个反应迟钝的系统: 外面降到零下, 她要用十分钟才"觉得冷",
     * 而那十分钟里她一直待在外面 —— <b>真人在还没觉得冷的时候就已经开始加衣服了</b>,
     * 因为"冷得很快"本身就是一种体感(皮肤的温度感受器对<b>变化率</b>敏感,
     * 这也是为什么手伸进凉水里那一瞬间最冷)。
     *
     * <p>只做路径二, 会得到一个永远不报警的系统: 稳态的冷(掉得慢但一直掉)永远不触发。
     *
     * <h3>方向</h3>
     * 用 {@code -side} 判断: 偏低侧只有"继续下降"才危险, 偏高一侧只有"继续上升"才危险。
     * 一个正在<b>回升</b>的偏低值不该触发任何东西 —— 她正在好转。
     *
     * @param value 当前值
     * @param rate  当前每秒变化量(由 {@link PhysiologicalState.Delta} 提供)
     */
    public boolean deteriorating(double value, double rate) {
        int side = side(value);
        if (side == 0) {
            return false;
        }
        double harmful = -side * rate;   // 偏低侧: rate 越负越危险; 偏高侧: rate 越正越危险
        return harmful > alarmingRatePerSecond;
    }

    /**
     * <b>还在带内, 但照这个速度很快就会出去</b> —— 路径二的预测面。
     *
     * <pre>{@code
     *   以当前速率, 距离最近的那条边界还有多少秒?
     *     秒数 <= lookaheadSeconds  →  现在就提醒她
     * }</pre>
     * 与 {@link #deteriorating(double, double)} 的分工:
     * <table border="1">
     *   <tr><th></th><th>{@link #convergingOnBoundary}</th><th>{@link #deteriorating}</th></tr>
     *   <tr><td>她此刻的位置</td><td>还在舒适带内</td><td>已经出界</td></tr>
     *   <tr><td>语义</td><td>"照这样下去她要冷了"</td><td>"她已经冷了, 而且越来越冷"</td></tr>
     *   <tr><td>真人的对应</td><td>起风那一刻的预感 —— 她开始找外套</td>
     *       <td>已经在发抖, 而且抖得越来越厉害</td></tr>
     * </table>
     *
     * <p>用<b>预测秒数</b>而不是"速率超阈值", 是因为速率阈值必须随 headroom 变化才合理:
     * 保暖值 0.65 时每秒掉 0.002 完全无所谓(还有一百多秒), 而 0.46 时每秒掉 0.002
     * 是"两分钟内就要冷"。同一个速率, 两种处境 —— 而 {@code lookaheadSeconds}
     * 恰好把这两者分开, 不需要为每条通道单独调参。
     *
     * @param value            当前值
     * @param rate             每秒变化量
     * @param lookaheadSeconds 预测视野(仿真秒), 必须为正
     */
    public boolean convergingOnBoundary(double value, double rate, double lookaheadSeconds) {
        if (lookaheadSeconds <= 0.0) {
            throw new IllegalArgumentException("预测视野必须为正, 收到 " + lookaheadSeconds);
        }
        if (side(value) != 0 || rate == 0.0) {
            return false;
        }
        double distance = rate < 0 ? value - low : high - value;
        if (distance <= 0.0) {
            return false;
        }
        return distance / Math.abs(rate) <= lookaheadSeconds;
    }

    // ─────────────────────────── 平台自带的十条带 ───────────────────────────

    /**
     * 保暖 —— 用户那条链守的就是这一条。
     *
     * <p>数值来源: §3.2.4 的例子给出了 {@code comfortThreshold = 0.45},
     * 本实现把它定为 {@code low}。上沿 0.85 是"热得难受"的起点。
     * {@code mid = 0.65} 是体温调节的目标 —— 比下沿高一点, 因为真人的稳态不在边缘上。
     */
    public static ComfortBand warmth() {
        return new ComfortBand(CoreEventCatalog.Channels.WARMTH,
                0.45, 0.85, 0.65, 0.05, 0.004);
    }

    /** 潮湿 —— 只有上沿有意义("湿透了"), 下沿是"干爽得过分"(不存在, 但带要连续)。 */
    public static ComfortBand wetness() {
        return new ComfortBand(CoreEventCatalog.Channels.WETNESS,
                0.0, 0.35, 0.10, 0.05, 0.006);
    }

    /** 精力。下沿 0.15 是"撑不住了" —— 低于它会产生 {@code body.energy-depleted.v1}。 */
    public static ComfortBand energy() {
        return new ComfortBand(CoreEventCatalog.Channels.ENERGY,
                0.15, 1.0, 0.70, 0.05, 0.003);
    }

    /** 疲劳(值越大越累)。 */
    public static ComfortBand fatigue() {
        return new ComfortBand(CoreEventCatalog.Channels.FATIGUE,
                0.0, 0.70, 0.30, 0.05, 0.004);
    }

    /** 饥饿。 */
    public static ComfortBand hunger() {
        return new ComfortBand(CoreEventCatalog.Channels.HUNGER,
                0.0, 0.65, 0.30, 0.05, 0.002);
    }

    /** 口渴 —— 比饥饿更快, 因为缺水比缺食更快致命。 */
    public static ComfortBand thirst() {
        return new ComfortBand(CoreEventCatalog.Channels.THIRST,
                0.0, 0.60, 0.25, 0.05, 0.004);
    }

    /** 睡眠压力。 */
    public static ComfortBand sleepPressure() {
        return new ComfortBand(CoreEventCatalog.Channels.SLEEP_PRESSURE,
                0.0, 0.70, 0.30, 0.05, 0.002);
    }

    /** 疼痛 —— 上沿很低(0.30), 因为"有点疼"已经是需要处理的事。 */
    public static ComfortBand pain() {
        return new ComfortBand(CoreEventCatalog.Channels.PAIN,
                0.0, 0.30, 0.05, 0.05, 0.03);
    }

    /** 压力。 */
    public static ComfortBand stress() {
        return new ComfortBand(CoreEventCatalog.Channels.STRESS,
                0.0, 0.60, 0.25, 0.05, 0.008);
    }

    /**
     * 综合舒适度。
     *
     * <p>它自己也有带 —— 因为 {@code environment.weather-changed.v1} 就投在这条通道上
     * (见 {@code CoreEventCatalog}), 而"天气让她不舒服"这件事必须能被量化。
     */
    public static ComfortBand comfort() {
        return new ComfortBand(CoreEventCatalog.Channels.COMFORT,
                0.40, 1.0, 0.75, 0.05, 0.004);
    }

    /** 平台自带的全部带, 按通道名索引 —— 只读。 */
    public static Map<String, ComfortBand> defaults() {
        Map<String, ComfortBand> map = new LinkedHashMap<>();
        for (ComfortBand band : new ComfortBand[]{warmth(), wetness(), energy(), fatigue(),
                hunger(), thirst(), sleepPressure(), pain(), stress(), comfort()}) {
            map.put(band.channel(), band);
        }
        return Map.copyOf(map);
    }

    /**
     * 一条通道的带。平台没给它定过带时, 返回一条<b>宽松带</b>。
     *
     * <h3>为什么未知通道返回"不报警", 而不是抛异常</h3>
     * 因为账本上的通道名是<b>开放</b>的: 一个第三方应用可以往 {@code body.} 前缀下
     * 投它自己的持续影响(比如"血糖"、"辐射累积"), 而平台不可能预先知道有多少条。
     * 抛异常会让一个合法的新通道直接把她的 tick 打断;<b>不报警</b>则给出一个
     * 诚实的结果: 平台不认识这条通道, 所以不会因为它产生刺激。
     *
     * <p>但那条影响<b>仍然会被应用到状态上</b>(见 {@code HomeostasisModel} 的
     * "未知通道也要推进"), 因为"不认识"不该等于"数据丢掉"。
     */
    public static ComfortBand forChannel(String channel) {
        ComfortBand known = defaults().get(channel);
        return known != null ? known : permissive(channel);
    }

    /** 一条不设防的带 —— 永远"在带内", 永远不报警。 */
    public static ComfortBand permissive(String channel) {
        return new ComfortBand(channel, FLOOR, CEILING, (FLOOR + CEILING) / 2.0, 0.0, Double.MAX_VALUE);
    }

    public String describe() {
        return String.format("%s ∈ [%.2f, %.2f] 适中 %.2f 回差 %.2f 危险率 %.4f/s",
                channel, low, high, mid, hysteresis, alarmingRatePerSecond);
    }

    private static final double EPS = 1e-9;

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
