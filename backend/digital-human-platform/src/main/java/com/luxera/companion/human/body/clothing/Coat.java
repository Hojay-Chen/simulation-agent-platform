package com.luxera.companion.human.body.clothing;

import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;

import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.2.5 衣物表 —— <b>外套/风衣</b>: 保暖 0.45 / 防水 0.55 / 透气 0.35, 覆盖躯干与手臂。
 *
 * <h2>它在链条里的位置是"不够的那一步"</h2>
 * 用户那条链的完整形态是:
 * <pre>{@code
 *   12:00  只穿短袖        保暖 0.05        无遮蔽 0.55
 *   12:10  觉得冷 → 加外套   保暖 0.475       无遮蔽 0.43   ← 还是冷, 但冷得慢了
 *   12:30  还是冷 → 换羽绒服 保暖 0.8575      无遮蔽 0.43   ← 这回够了
 * }</pre>
 * 外套存在的意义是把"加衣服"从一次二元跳变(冷 / 不冷)变成一个<b>有梯度的过程</b>。
 * 只有一个"厚衣服"档位时, 系统的行为必然表现为"她要么冻着, 要么突然好了" ——
 * 而真人找衣服是一个试错过程: 加一件, 还冷, 再换一件厚的。
 *
 * <h2>防水 0.55 高于羽绒服, 是刻意的</h2>
 * 因为它的外层布料是致密的(风衣的初衷就是挡风), 而羽绒服不是。
 * 于是出现一个<b>非单调</b>的排序 —— 外套更防水但更不保暖:
 * <pre>{@code
 *   小雨 + 5℃   →  外套更好(羽绒服会湿, 湿了就完了)
 *   干冷  -5℃   →  羽绒服更好
 * }</pre>
 * 系统里必须存在这样的取舍, 否则"她该穿哪件"就退化成一个可以写死的答案,
 * 而 Mind 侧的选择也就失去了意义。
 */
@DomainType(value = "clothing.coat", version = 1,
        description = "外套/风衣: 中等保暖、较好的防水与透气, 覆盖躯干与手臂")
public final class Coat extends AbstractWearable {

    public static final double THERMAL_INSULATION = 0.45;
    public static final double WATERPROOF_LEVEL = 0.55;
    public static final double BREATHABILITY = 0.35;

    public static final Set<String> COVERS = Set.of(WearableObject.Parts.TORSO, WearableObject.Parts.ARMS);

    public Coat(String objectId, String displayName) {
        super(objectId, displayName, ThermalInsulation.of(THERMAL_INSULATION),
                WATERPROOF_LEVEL, BREATHABILITY, COVERS);
    }

    public static Coat of(String objectId) {
        return new Coat(objectId, "外套");
    }

    /**
     * <b>本类登记它自己</b> —— 装配层调用。
     *
     * <h2>为什么这一次没有把入口收在一个"握有全集"的类上</h2>
     * 那条规则的前提是<b>存在</b>这样一个类: 一个本来就必须知道"有哪几种"的地方
     * (活动那边是 {@code ActivityFactory}, 它的查表键就是类型名)。
     * 衣服这边<b>没有</b>, 而且不是"暂时没写" —— {@code ClothingSet} 持有的是
     * <b>这一件</b>(世界里的一个对象, 有自己的 id 与状态), 不是"有哪几款衣服";
     * 本包也刻意不提供"平台 new 一件衣服"的工厂(见 {@link DownJacket} 的
     * "它怎么被造出来": 一件具体的衣服由世界侧构造, 身体只是引用)。
     * 于是硬造一个"衣物目录"只会多出一个谁也不查的登记点。
     * <p>各自登记因此是这里<b>唯一不引入新耦合</b>的做法: 类型名与它的三个物理属性
     * 仍然在同一个文件里, 而装配层多一行 {@code Coat.registerTypes(registry)}。
     * 哪天平台真的需要一份"衣物表"(前端要列款式、商店要上架), 那一天该做的是
     * 写那个目录类, 并把这三个 {@code registerTypes} 一起收进去 ——
     * 而不是现在先造一个空壳。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条(恒为 1)。可重复调用: 同一个类登记两次在注册表那边
     *         是一次空操作, 所以装配层重复装配(或测试各自装配)不会炸
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(Coat.class);
        return 1;
    }
}
