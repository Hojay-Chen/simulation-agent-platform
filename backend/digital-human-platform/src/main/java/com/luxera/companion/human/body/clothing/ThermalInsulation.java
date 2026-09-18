package com.luxera.companion.human.body.clothing;

import java.util.Objects;

/**
 * V2.2 §3.2.5 —— <b>保暖能力</b>的建模: 一个 {@code [0, 1]} 的值, 以及它怎么组合。
 *
 * <h2>为什么它值得是一个类型, 而不是一个 double</h2>
 * 因为"保暖能力"要参与三种运算, 而每一种都有<b>只有它才知道</b>的物理常识:
 * <table border="1">
 *   <tr><th>运算</th><th>常识</th><th>如果只是一个 double 会发生什么</th></tr>
 *   <tr>
 *     <td>{@link #layered(ThermalInsulation)} 层叠</td>
 *     <td>穿两件薄的比穿一件厚的暖, 但<b>不是</b>两倍暖 —— 层间对流与压迫会吃掉收益</td>
 *     <td>直接相加, 于是三件短袖 = 0.15 的保暖值, 比羽绒服的一半还多。这个错误不会报错,
 *         它只会让"她冬天穿三件T恤就出门了"变成一个稳定复现的怪行为</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #wetted(double, double)} 淋湿</td>
 *     <td>湿了的衣服几乎不保暖(水的导热系数是空气的 25 倍), 但<b>防水</b>的衣服湿不透</td>
 *     <td>忘记这一步, 就得到"她淋着雨站在 3 度的风里, 保暖值不降反升"—— 因为她穿着羽绒服</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #asLedgerCoefficient()} 入账</td>
 *     <td>账本上的 {@code magnitude} 是<b>无量纲系数</b>, 与"每秒变化多少"不是一回事</td>
 *     <td>两处各自假设量纲, 而它们的假设不同 —— 表现是"穿上羽绒服后保暖值一秒涨满"</td>
 *   </tr>
 * </table>
 *
 * <h2>层叠为什么用"串行阻抗"公式而不是求和</h2>
 * <pre>{@code
 *   求和再截顶:   0.85 + 0.45 + 0.05 = 1.35 → min(1.35, 1.0) = 1.00
 *   串行阻抗:     1 - (1-0.85)(1-0.45)(1-0.05) = 0.9216
 * }</pre>
 * 求和再截顶的问题是<b>它会把完全不同的搭配压成同一个数</b>: "羽绒服+外套+短袖"
 * 与"一件 1.0 的神衣"得到同样的保暖值, 而现实里前者明显更差(袖口、领口、下摆都是漏风的缝)。
 * 串行阻抗的公式来自"热阻串联", 它天然满足三条不证自明的性质:
 * <ul>
 *   <li>加一件衣服<b>永远</b>会变暖(单调递增);</li>
 *   <li>收益<b>递减</b>(第二件的边际效果小于第一件);</li>
 *   <li>永远<b>到不了</b> 1.0(0.9999 需要无穷多件)。</li>
 * </ul>
 * 这三条恰好就是"穿衣服"这件事的真实形状。
 *
 * <h2>与文档的差异(有意为之)</h2>
 * §3.2.4 的 {@code ClothingSet.thermalInsulation()} 写的是"Σ(各件保暖值 × 覆盖部位权重),
 * 上限 1.0"。本实现改成了串行阻抗, 理由见上 —— 而<b>覆盖部位权重并没有被丢掉</b>,
 * 它换了位置: 它现在是 {@link ClothingSet#coverageGap()}("寒风从哪里钻进来"),
 * 由稳态模型用来<b>放大失温</b>, 而不是用来打折每件衣服自己的保暖值。这个换位更接近
 * 物理: 露着脖子不会让羽绒服本身变薄, 它只是让风从那里进来。
 */
public record ThermalInsulation(double value) implements Comparable<ThermalInsulation> {

    /** 什么都没穿。 */
    public static final ThermalInsulation NONE = new ThermalInsulation(0.0);
    /** 理论上的完美保暖 —— 只用于比较, 现实里达不到。 */
    public static final ThermalInsulation MAX = new ThermalInsulation(1.0);

    /**
     * 湿透之后的残存保暖比例。
     *
     * <p>取 {@code 0.1}: 湿透的羽绒服几乎完全不保暖(这符合真人的经验 —— 羽绒湿了会结块)。
     * 留 10% 而不是 0, 因为"完全不保暖"会让数值在某个点上出现一个硬拐点,
     * 而硬拐点在调参时表现得像 bug。
     */
    public static final double SOAKED_RETENTION = 0.1;

    public ThermalInsulation {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "保暖值必须归一化到 [0, 1], 收到 " + value + " —— "
                            + "超出这个范围的值在层叠公式里会产生负的'热阻', 表现是穿衣服反而变冷");
        }
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("保暖值不能是 NaN");
        }
    }

    public static ThermalInsulation of(double value) {
        return new ThermalInsulation(value);
    }

    // ─────────────────────────── 三种运算 ───────────────────────────

    /**
     * 层叠 —— 串行阻抗公式, 见类注释。
     *
     * <p>交换律天然成立({@code a.layered(b) == b.layered(a)}), 这不是巧合而是要求:
     * 穿衣服的顺序不该影响她冷不冷。
     */
    public ThermalInsulation layered(ThermalInsulation other) {
        Objects.requireNonNull(other, "层叠的另一件保暖值不能为空 —— 空值会被当成 0, 于是'穿了一件'变成'没穿'");
        return new ThermalInsulation(1.0 - (1.0 - value) * (1.0 - other.value));
    }

    /**
     * 淋湿之后的有效保暖。
     *
     * <pre>{@code
     *   有效保暖 = value × (1 - wetness × (1 - waterproof) × (1 - SOAKED_RETENTION))
     * }</pre>
     * 三项的直觉: 湿得越透损失越大; 越防水损失越小; 损失的下限是 {@link #SOAKED_RETENTION}。
     *
     * <p>雨衣({@code waterproof = 0.9})在 {@code wetness = 1.0} 时只损失 9% —— 这正是
     * "雨衣不保暖但挡雨"在数值上的表达。羽绒服({@code waterproof = 0.3})在同样条件下
     * 损失 63%, 于是"淋湿的羽绒服不如一件干的抓绒"这件事自然地发生了。
     *
     * @param wetness       潮湿程度 {@code [0, 1]}
     * @param waterproofLevel 防水等级 {@code [0, 1]}
     */
    public ThermalInsulation wetted(double wetness, double waterproofLevel) {
        if (wetness < 0.0 || wetness > 1.0) {
            throw new IllegalArgumentException("潮湿程度必须归一化到 [0, 1], 收到 " + wetness);
        }
        if (waterproofLevel < 0.0 || waterproofLevel > 1.0) {
            throw new IllegalArgumentException("防水等级必须归一化到 [0, 1], 收到 " + waterproofLevel);
        }
        double loss = wetness * (1.0 - waterproofLevel) * (1.0 - SOAKED_RETENTION);
        return new ThermalInsulation(value * (1.0 - loss));
    }

    /**
     * 换算成账本上的 {@code magnitude}。
     *
     * <h3>为什么需要这一步</h3>
     * 账本做的运算是 {@code warmth += Σmagnitude × Δt}(见 {@code ContinuousEffectLedger}
     * 的结算模型), 也就是说 {@code magnitude} 的<b>量纲是"每秒变化多少"</b>。
     * 而 0.85 这个数显然不是"每秒把保暖值推高 0.85" —— 那是荒谬的。
     *
     * <p>所以账本上的数被约定为<b>无量纲系数</b>, 而"系数 → 每秒变化量"的换算率
     * 属于 {@code HomeostasisModel}(它知道身体对这身衣服的反应)。
     * 本方法只是把这条约定写下来: <b>这里放的是系数, 不是速率</b>。
     * 一个把两者混在一起的实现, 表现是"穿上羽绒服后保暖值在一个 tick 内涨满" ——
     * 而那正好是用户明确否定的: "影响是渐进的, 而不是'穿上就暖和了'"。
     */
    public double asLedgerCoefficient() {
        return value;
    }

    /** 是不是几乎不保暖 —— 用于日志里把"短袖"这种噪声过滤掉。 */
    public boolean negligible() {
        return value < 0.02;
    }

    public String describe() {
        return String.format("%.2f", value);
    }

    @Override
    public int compareTo(ThermalInsulation other) {
        return Double.compare(value, other.value);
    }
}
