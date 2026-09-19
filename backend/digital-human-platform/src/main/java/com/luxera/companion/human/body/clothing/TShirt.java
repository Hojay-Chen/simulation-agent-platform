package com.luxera.companion.human.body.clothing;

import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;

import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.2.5 衣物表 —— <b>短袖</b>: 保暖 0.05 / 防水 0.0 / 透气 0.95, 只覆盖躯干。
 *
 * <h2>它是这张表里最有信息量的一行</h2>
 * 因为它的保暖值几乎为 0, 而它的透气性接近 1。这意味着它在系统里扮演的角色不是
 * "保暖的一点点", 而是<b>两件事的对照组</b>:
 * <table border="1">
 *   <tr><th>它证明的</th><th>怎么证明</th></tr>
 *   <tr>
 *     <td>层叠模型<b>不是</b>简单相加</td>
 *     <td>穿三件短袖是 {@code 1-(1-0.05)^3 = 0.1425}, 不是 0.15; 而且这个数
 *         <b>仍然远小于</b>羽绒服的 0.85 —— 而"三件T恤顶不上一件羽绒服"恰好是真的</td>
 *   </tr>
 *   <tr>
 *     <td>"穿了"与"暖了"是两件事</td>
 *     <td>它让 {@code ClothingSet.insulation()} 不为 0, 但保暖值几乎没变 ——
 *         于是"她加衣服了怎么还冷"有一个诚实的答案, 而不是一个需要靠猜的数值异常</td>
 *   </tr>
 * </table>
 *
 * <h2>防水 0.0 是字面意思</h2>
 * 棉质短袖淋湿后保暖值归到 {@link ThermalInsulation#SOAKED_RETENTION}(10%)。
 * 这正是"夏天淋雨反而容易感冒"的机制: 不是因为雨冷, 是因为湿衣服的导热系数
 * 是空气的 25 倍, 身体的热量被持续带走。
 */
@DomainType(value = "clothing.t-shirt", version = 1,
        description = "短袖: 几乎不保暖、几乎完全透气, 只覆盖躯干")
public final class TShirt extends AbstractWearable {

    public static final double THERMAL_INSULATION = 0.05;
    public static final double WATERPROOF_LEVEL = 0.0;
    public static final double BREATHABILITY = 0.95;

    /** 只盖躯干 —— 0.45, 也就是无遮蔽至少 0.55。 */
    public static final Set<String> COVERS = Set.of(WearableObject.Parts.TORSO);

    public TShirt(String objectId, String displayName) {
        super(objectId, displayName, ThermalInsulation.of(THERMAL_INSULATION),
                WATERPROOF_LEVEL, BREATHABILITY, COVERS);
    }

    public static TShirt of(String objectId) {
        return new TShirt(objectId, "短袖");
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
        registry.register(TShirt.class);
        return 1;
    }
}
