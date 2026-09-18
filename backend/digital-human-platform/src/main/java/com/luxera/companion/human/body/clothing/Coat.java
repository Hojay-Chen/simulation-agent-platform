package com.luxera.companion.human.body.clothing;

import com.luxera.companion.registry.DomainType;

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
}
