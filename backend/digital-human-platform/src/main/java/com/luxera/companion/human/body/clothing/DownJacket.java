package com.luxera.companion.human.body.clothing;

import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;

import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.2.5 衣物表第一行 —— <b>羽绒服</b>: 保暖 0.85 / 防水 0.30 / 透气 0.20, 覆盖躯干与手臂。
 *
 * <h2>为什么它值得是一个类, 而不是一行配置</h2>
 * 因为它带着<b>一组互相牵制的事实</b>, 而它们在行为上必须同时成立:
 * <pre>{@code
 *   保暖 0.85  →  穿上之后 effectiveHeatLoss 立刻变小 → warmth 回升
 *   透气 0.20  →  穿着它跑步会闷 → 出汗 → wetness 上升 → 保暖值被 wetted() 打折
 * }</pre>
 * 也就是说: <b>羽绒服在"静止待在冷处"和"跑起来"这两种情境下是好坏相反的</b>。
 * 一个把属性写成配置行的实现很容易让这两种情境里的某一个算错, 因为没有任何东西
 * 提醒作者"这两个数会互相作用"。一个类可以写下这段话, 而配置行不能。
 *
 * <h2>为什么防水只有 0.30</h2>
 * 这是一个刻意的、与直觉相反的取值。羽绒服的布料本身是防泼水的, 但它的<b>接缝、
 * 拉链、领口、下摆</b>都不防水 —— 而失温真正发生的地方正是这些开口。
 * 参照 {@link ThermalInsulation#wetted(double, double)}: 在 {@code wetness = 1.0} 时,
 * 羽绒服的保暖值会从 0.85 掉到约 0.43 —— <b>一件湿透的羽绒服不如一件干的抓绒</b>。
 * 这正是"冬天淋了雨比单纯冷更危险"在数值上的表达, 也是用户那条链里
 * "保暖值持续下降"可能出现的第二种原因(第一种是外面降温)。
 *
 * <h2>它怎么被造出来</h2>
 * {@code @DomainType} 只声明它在数据里的名字, 它<b>不由平台 new 出来</b> ——
 * 一件具体的羽绒服是世界里的一件东西, 由世界侧(衣柜、购物、初始化脚本)构造,
 * 然后通过 {@code body.clothing-changed.v1} 这条事件告诉身体"现在有这件东西盖在你身上"。
 * 身体永远不"创建"衣服, 它只是引用。
 */
@DomainType(value = "clothing.down-jacket", version = 1,
        description = "羽绒服: 高保暖、低透气, 湿透后保暖能力大幅下降")
public final class DownJacket extends AbstractWearable {

    /** 平台建议的稀有度/档次无关, 这里只固定它的物理属性 —— 见类注释。 */
    public static final double THERMAL_INSULATION = 0.85;
    public static final double WATERPROOF_LEVEL = 0.30;
    public static final double BREATHABILITY = 0.20;

    /** 它盖住哪 —— 躯干 0.45 + 手臂 0.12 = 0.57, 剩下的是裤子和头颈脚。 */
    public static final Set<String> COVERS = Set.of(WearableObject.Parts.TORSO, WearableObject.Parts.ARMS);

    /**
     * @param objectId    这一件的 id, 形如 {@code jacket-gray-01} —— 注意是"这一件", 不是"这一款"
     * @param displayName 界面与日志里的名字, 形如 "那件灰色羽绒服"
     */
    public DownJacket(String objectId, String displayName) {
        super(objectId, displayName, ThermalInsulation.of(THERMAL_INSULATION),
                WATERPROOF_LEVEL, BREATHABILITY, COVERS);
    }

    /** 便捷构造 —— 供初始化脚本与测试用。生产路径上应当由世界侧给出真实的 id 与名字。 */
    public static DownJacket of(String objectId) {
        return new DownJacket(objectId, "羽绒服");
    }

    /**
     * <b>本类登记它自己</b> —— 装配层调用。
     *
     * <p>为什么衣物这一族破例各自登记(而不是收在一个"握有全集"的类上), 见
     * {@link Coat#registerTypes} —— 简短版本: 本包没有、也不该有一个"有哪几款衣服"
     * 的目录类, {@link ClothingSet} 持有的是<b>这一件</b>而不是"有哪几款"。
     * 编造一个没人查的目录只会让"类型叫什么"与"它长什么样"分到两个文件里。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条(恒为 1)。可重复调用: 同一个类登记两次是一次空操作
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(DownJacket.class);
        return 1;
    }
}
