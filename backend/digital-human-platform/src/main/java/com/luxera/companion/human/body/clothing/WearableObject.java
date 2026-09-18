package com.luxera.companion.human.body.clothing;

import java.util.Map;
import java.util.Set;

/**
 * V2.2 §3.2.5 —— <b>一件可以被穿在身上的东西</b>。
 *
 * <h2>它是对象, 不是 {@code enum ClothingType}</h2>
 * 用户的原话:
 * <blockquote>
 *   温度低应该让 human 的 body 持续降低保暖值, 除非其多穿衣服
 *   (因此衣服可能也要抽象成对象), 多穿衣服也是一个 event, 能够持续影响 body 的保暖值的
 * </blockquote>
 * "抽象成对象"这四个字是这一段的关键, 而它与 P4 是同一件事:
 * <table border="1">
 *   <tr><th></th><th>{@code enum ClothingType { DOWN_JACKET, TSHIRT, ... }}</th>
 *       <th>本接口 + {@code @DomainType} 实现</th></tr>
 *   <tr>
 *     <td>加一件"实验室白大褂"</td>
 *     <td>改平台源码 → 重新编译 → 重新部署</td>
 *     <td>第三方写一个类, 注册类型, 完</td>
 *   </tr>
 *   <tr>
 *     <td>一件具体的衣服</td>
 *     <td>枚举常量没有"这一件"的概念 —— <b>两件羽绒服是同一个常量</b>,
 *         于是"她穿的是哪一件"在系统里无法表达</td>
 *     <td>每一件是<b>一个对象</b>, 有自己的 id、自己的状态(谁穿着、穿多久了、有没有湿)</td>
 *   </tr>
 *   <tr>
 *     <td>非保暖属性</td>
 *     <td>加字段 = 改枚举 = 又一次全量重编译</td>
 *     <td>接口的 default 方法, 或者实现类自己的字段</td>
 *   </tr>
 * </table>
 *
 * <h2>它属于 World, 而 Body 只是<b>引用</b>它</h2>
 * 这一点很重要: 衣服放在衣柜里、可以买、可以丢、可以弄湿 —— 它是<b>世界里的一个对象</b>。
 * 穿衣服这件事是"衣服从'衣柜里'变成'身上'", 是<b>世界被改变了</b>, 而不是
 * "给身体加了个字段"。
 *
 * <p>也正因如此, 用户要求的那条链在架构上才成立:
 * <pre>
 *   Mind 决定穿衣服 → WearClothingCommand → Wardrobe(世界对象) → jacket.equip(她)
 *        → WorldEvent: body.clothing-changed.v1
 *        → EventFabric → ContinuousEffectLedger 记一条保暖影响
 *        → 下一个 tick 的 effectiveHeatLoss 立刻变小
 *        → warmth 开始<b>渐进</b>回升
 * </pre>
 *
 * <h2>与文档 §3.2.5 的两处差异(均为有意)</h2>
 * <ol>
 *   <li><b>不继承 {@code WorldObject}</b>。文档写的是
 *       {@code public interface WearableObject extends WorldObject}, 但这个仓库里
 *       {@code com.luxera.companion.world} 下<b>还没有</b> {@code WorldObject}, 而
 *       更重要的是: {@code human/} 不许 import {@code world/}(P3, §8.2.7 的 ArchUnit 会红)。
 *       两者是同一个约束的两面 —— 只要 {@code WearableObject} 住在 {@code human/} 里,
 *       它就不能继承一个 World 的类型, 否则 {@code human → world} 的依赖立刻成立。
 *       正确的解法在 World 侧: 让 {@code WorldObject} 提供一个"可穿戴"能力接口,
 *       由 World 侧的对象去 {@code implements WearableObject}。依赖方向反过来,
 *       两边都不破规矩。</li>
 *   <li><b>{@code equip(String humanId)} 而不是 {@code equip(Body body)}</b>。
 *       传 Body 会让一件衣服能读到她的体温、饥饿值、正在做什么 ——
 *       而衣服没有理由知道这些。它只需要知道<b>谁穿着我</b>, 而那是一个字符串。
 *       依赖方向从 {@code human.body.clothing → human.body} 变成了一条 id,
 *       于是"衣服"这个包可以被单独理解、单独测试。</li>
 * </ol>
 *
 * <h2>覆盖的身体部位: 字符串集合, 不是 {@code Set<BodyPart>}</h2>
 * 见 {@link Parts} —— 那一组常量是<b>平台自带的词汇</b>, 不是封闭集合。
 */
public interface WearableObject {

    /**
     * 身体的部位 —— <b>用于表达"这件衣服盖住了哪里"</b>。
     *
     * <h2>为什么不是 {@code enum BodyPart}</h2>
     * 三个理由, 一个比一个硬:
     * <ol>
     *   <li>P4: 枚举是编译期的扩展机制。一件"只盖住尾巴"的衣服(给机器宠物穿的),
     *       或一顶"盖住耳朵"的帽子, 不该需要改平台源码;</li>
     *   <li>部位是会<b>细分</b>的: 今天只有 {@code head}, 明天要区分"头顶"和"脸"
     *       (面罩 vs 帽子), 而这种细分在枚举里是一次破坏性改动;</li>
     *   <li>它与 {@link Parts#weightOf(String)} 绑定, 而权重是一个<b>可以随身体设定变化</b>
     *       的量(一个小孩的头部散热量占比比成人大得多)。写死在枚举里会让"身体设定"
     *       这个功能无从下手。</li>
     * </ol>
     *
     * <p>它们的值是<b>标准词汇</b>(与 {@code CoreEventCatalog.Channels} 同类): 拼错一个
     * 部位名的后果是"这件衣服盖住的地方算错了", 而那会静默地改变她冷不冷。
     * 所以 {@link #coveredParts()} 的实现应当校验取值。
     */
    final class Parts {
        private Parts() {
        }

        public static final String HEAD = "head";
        public static final String NECK = "neck";
        public static final String TORSO = "torso";
        public static final String ARMS = "arms";
        public static final String HANDS = "hands";
        public static final String LEGS = "legs";
        public static final String FEET = "feet";

        /** 全部部位。 */
        public static final Set<String> ALL = Set.of(HEAD, NECK, TORSO, ARMS, HANDS, LEGS, FEET);

        /**
         * 各部位的散热量占比 —— 合计 1.0。
         *
         * <p>数量级参照"不穿衣服时人体各部位的散热比例", 头部约 9%、躯干最大。
         * 这组数<b>不是</b>用来给每件衣服的保暖值打折的(那件事由串行阻抗做, 见
         * {@link ThermalInsulation}), 而是用来算 {@link ClothingSet#coverageGap()}:
         * <b>她身上还有多大比例处在无遮蔽状态</b> —— 那才是"寒风从哪里钻进来"。
         */
        public static final Map<String, Double> WEIGHTS = Map.of(
                TORSO, 0.45,
                LEGS, 0.20,
                ARMS, 0.12,
                HEAD, 0.09,
                HANDS, 0.05,
                NECK, 0.05,
                FEET, 0.04);

        /** 这个部位名是不是平台的标准词汇。 */
        public static boolean isStandard(String part) {
            return part != null && ALL.contains(part);
        }

        /** 这个部位的散热占比。未知部位返回 0(并应当被实现类在入口处挡下)。 */
        public static double weightOf(String part) {
            return WEIGHTS.getOrDefault(part, 0.0);
        }
    }

    // ─────────────────────────── 身份 ───────────────────────────

    /** 这一件衣服在世界里的 id —— 注意是"这一件", 不是"这一款"。 */
    String objectId();

    /** 人类可读的名字, 给界面与日志: "那件灰色羽绒服"。 */
    String displayName();

    // ─────────────────────────── 三个物理属性 ───────────────────────────

    /** 保暖能力 —— 羽绒服 0.85, 短袖 0.05。见 {@link ThermalInsulation}。 */
    ThermalInsulation insulation();

    /** 防水等级 {@code [0, 1]}。雨衣 0.9, 棉袄 0.1。 */
    double waterproofLevel();

    /** 透气性 {@code [0, 1]}。运动时影响闷热 —— 一件不透气的雨衣跑步会闷得难受。 */
    double breathability();

    // ─────────────────────────── 覆盖 ───────────────────────────

    /**
     * 它盖住了哪些部位。取值见 {@link Parts}。
     *
     * <p>返回<b>不可变</b>集合 —— 它是这件衣服的属性, 不是调用方可以改的东西。
     */
    Set<String> coveredParts();

    /** 这件衣服覆盖部位的权重之和 {@code [0, 1]} —— 诊断与日志用。 */
    default double coverageWeight() {
        return Math.min(1.0, coveredParts().stream().mapToDouble(Parts::weightOf).sum());
    }

    default boolean covers(String part) {
        return coveredParts().contains(part);
    }

    // ─────────────────────────── 穿着状态 ───────────────────────────

    /** 它现在是不是被穿着。 */
    boolean worn();

    /** 谁穿着它。没被穿时返回 {@code null}。 */
    String wornBy();

    /**
     * 穿上。
     *
     * <p>由 {@code ClothingSet} 调用 —— 而 {@code ClothingSet} 由 {@code Body} 持有。
     * 衣服自己记录"被谁穿着", 因为这是它的状态, 不是身体的状态:
     * 一件挂在衣柜里的夹克和一件穿在她身上的夹克是<b>两个世界状态</b>,
     * 而"发生了什么变化"这个问题的答案就在这两个状态之间。
     *
     * @throws IllegalStateException 已经被人穿着
     */
    void equip(String humanId);

    /**
     * 脱下。
     *
     * @throws IllegalStateException 本来就没被穿着 —— 静默成功会让"她脱了一件并不在身上的
     *                               衣服"变成一次不可见的错误, 而那正是账本里"她为什么
     *                               突然开始冷"最需要的线索
     */
    void remove(String humanId);

    // ─────────────────────────── 诊断 ───────────────────────────

    default String describe() {
        return String.format("%s[%s] 保暖%s 防水%.2f 透气%.2f 覆盖%s%s",
                displayName(), objectId(), insulation().describe(), waterproofLevel(),
                breathability(), coveredParts(), worn() ? " 穿在 " + wornBy() + " 身上" : " 未穿着");
    }
}
