package com.luxera.companion.human.body.clothing;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.2.5 —— <b>一件衣服的公共部分</b>: 身份、穿着状态、覆盖部位的校验。
 *
 * <h2>为什么需要它, 而不是让每件衣服各写一遍</h2>
 * 因为它承担的不是"省几行代码", 而是<b>两处正确性</b>:
 * <ol>
 *   <li><b>穿着状态只有一份实现。</b> "一件衣服同时穿在两个人身上"这件事若可能发生,
 *       那它一定是因为有两个地方各自维护了 {@code wornBy}。收在一个字段里,
 *       这个状态就只有一种写法, 也就只有一种错法 —— 而那种错法会在构造时被挡下;</li>
 *   <li><b>覆盖部位在构造时校验。</b> {@link WearableObject.Parts} 是一个<b>开放</b>词表
 *       (见该类的说明), 这意味着"拼错部位名"是可能的。而拼错的后果不是报错,
 *       是 {@code Parts.weightOf} 静默返回 0 —— 表现是"她穿着羽绒服但系统认为
 *       她无遮蔽 1.0", 于是她会在零下十度里不停觉得冷。这类故障必须在装配期挡下,
 *       不能等到行为分析时才发现。</li>
 * </ol>
 *
 * <h2>{@code equals} 按 {@code objectId}, 不按字段</h2>
 * 这不是风格问题。两件同款羽绒服在字段上完全相同, 但它们是<b>两件东西</b> ——
 * 一件在她身上, 一件在衣柜里。若 {@code equals} 按字段比较, 那么
 * {@code ClothingSet} 的 {@code Map} 会把它们合并成一条, 于是
 * "她把另一件也穿上了"这件事在系统里彻底不可见。
 *
 * <h2>它<b>不</b>是 {@code sealed}</h2>
 * 与 {@code RawSignal} 不同。原始信号是<b>平台的物理量纲</b> —— 五种模态是封闭的,
 * 多一种就意味着多一个感官。而衣服是<b>无限的领域对象</b>: 明天有人要加"护膝"、
 * "防辐射围裙"、"机械外骨骼", 这些都不该需要改平台源码。所以这里<b>故意</b>留开口子。
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public abstract class AbstractWearable implements WearableObject {

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String objectId;

    @ToString.Include
    private final String displayName;

    private final ThermalInsulation insulation;
    private final double waterproofLevel;
    private final double breathability;
    private final Set<String> coveredParts;

    /** 谁穿着它 —— {@code null} = 挂在衣柜里。 */
    private String wornBy;

    /**
     * @param objectId        这一件在世界里的 id
     * @param displayName     人类可读的名字
     * @param insulation      保暖能力
     * @param waterproofLevel 防水等级 {@code [0, 1]}
     * @param breathability   透气性 {@code [0, 1]}
     * @param coveredParts    盖住的部位, 取值见 {@link WearableObject.Parts}
     */
    protected AbstractWearable(String objectId,
                               String displayName,
                               ThermalInsulation insulation,
                               double waterproofLevel,
                               double breathability,
                               Set<String> coveredParts) {
        this.objectId = Objects.requireNonNull(objectId, "衣服必须有 id —— 否则账本与日志里无法区分两件同款");
        if (objectId.isBlank()) {
            throw new IllegalArgumentException("衣服 id 不能为空白 —— 空白 id 会让两件不同的衣服在日志里看起来是同一件");
        }
        this.displayName = Objects.requireNonNull(displayName, "衣服必须有人类可读的名字");
        this.insulation = Objects.requireNonNull(insulation, "保暖能力不能为空 —— 空值会被当成 0, 表现是'她穿了但没变暖'");
        this.waterproofLevel = requireUnit(waterproofLevel, "防水等级");
        this.breathability = requireUnit(breathability, "透气性");

        Objects.requireNonNull(coveredParts, "覆盖部位不能为空集合以外的空值 —— 一件不盖任何部位的衣服不是衣服");
        if (coveredParts.isEmpty()) {
            throw new IllegalArgumentException(
                    "衣服 \"" + displayName + "\" 没有声明任何覆盖部位 —— "
                            + "它在失温计算里的贡献会是 0, 而这是一条永远查不出来的'她穿了却没用'");
        }
        for (String part : coveredParts) {
            if (!WearableObject.Parts.isStandard(part)) {
                throw new IllegalArgumentException(
                        "衣服 \"" + displayName + "\" 声明了未知部位 \"" + part + "\", 平台标准部位是 "
                                + WearableObject.Parts.ALL
                                + " —— 未知部位会让 coverageGap 静默算错, 见 AbstractWearable 的类注释");
            }
        }
        this.coveredParts = Set.copyOf(coveredParts);
    }

    private static double requireUnit(double value, String what) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(what + "必须归一化到 [0, 1], 收到 " + value);
        }
        return value;
    }

    // ─────────────────────────── 身份与属性 ───────────────────────────

    @Override
    public final String objectId() {
        return objectId;
    }

    @Override
    public final String displayName() {
        return displayName;
    }

    @Override
    public final ThermalInsulation insulation() {
        return insulation;
    }

    @Override
    public final double waterproofLevel() {
        return waterproofLevel;
    }

    @Override
    public final double breathability() {
        return breathability;
    }

    @Override
    public final Set<String> coveredParts() {
        return coveredParts;
    }

    // ─────────────────────────── 穿着状态 ───────────────────────────

    @Override
    public final boolean worn() {
        return wornBy != null;
    }

    @Override
    public final String wornBy() {
        return wornBy;
    }

    /**
     * {@inheritDoc}
     *
     * <h3>为什么"已经被别人穿着"要抛异常, 而不是默默抢过来</h3>
     * 因为那两种情况在世界里完全不同: "她把外套递给了朋友" 与 "系统里外套同时出现在
     * 两个人身上" 是两件事, 而后者会让两边的失温计算都错, 且没有任何日志能指出它发生过。
     * 把这种世界状态的不一致在<b>发生的那一刻</b>暴露出来, 代价是一次异常;
     * 容忍它, 代价是一个查不出来的行为异常。
     */
    @Override
    public final void equip(String humanId) {
        Objects.requireNonNull(humanId, "谁穿的衣服不能为空");
        if (wornBy != null && !wornBy.equals(humanId)) {
            throw new IllegalStateException(
                    "衣服 \"" + displayName + "\" 已经穿在 " + wornBy + " 身上, 不能再给 " + humanId
                            + " —— 一件衣服不能同时穿在两个人身上");
        }
        this.wornBy = humanId;
    }

    @Override
    public final void remove(String humanId) {
        Objects.requireNonNull(humanId, "谁脱的衣服不能为空");
        if (wornBy == null) {
            throw new IllegalStateException(
                    "衣服 \"" + displayName + "\" 本来就没被穿着, 却被要求由 " + humanId + " 脱下 —— "
                            + "静默成功会让一次装配错误(她脱了一件不在身上的衣服)不可见");
        }
        this.wornBy = null;
    }
}
