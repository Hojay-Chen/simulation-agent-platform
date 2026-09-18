package com.luxera.companion.human.body.clothing;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.2.5 —— <b>她此刻穿在身上的那一身</b>, 以及它产生的持续影响。
 *
 * <h2>它是一份"引用清单", 不是一堆字段</h2>
 * {@code ClothingSet} 不复制衣服的属性, 它只<b>持有那些衣服对象</b>。这条区别决定了
 * 三件事都自动成立:
 * <ul>
 *   <li>一件衣服被穿在别人身上时, 这一身里就<b>不会</b>有它 —— 不需要任何同步代码;</li>
 *   <li>衣服被弄湿了(它的状态变了), 它在这一身里的贡献自动跟着变 ——
 *       因为 {@link #insulation(double)} 每次都读它的当前值;</li>
 *   <li>一件衣服的保暖值被第三方插件改了, 这里不需要知道 —— 它下次结算时自然就是新值。</li>
 * </ul>
 * 反过来, 一个把 {@code thermalInsulation} 抄成字段的实现, 会得到"她换了件更厚的,
 * 但系统里还是旧值"这种只在特定顺序下出现的 bug。
 *
 * <h2>穿脱为什么产生的是"一条总和"而不是"每件一条"</h2>
 * 账本用 {@code cancellationKey} 做替换(见 {@code StateEffectEvent.cancellationKey()})。
 * 如果每件衣服各投一条 {@code +0.85} / {@code +0.05}, 它们会<b>叠加</b>成 {@code +0.90} ——
 * 也就是说, 保暖能力退化成"求和", 而 {@link ThermalInsulation} 花整篇注释论证的
 * 递减收益就白做了。
 *
 * <p>所以本类只投<b>一条</b>: {@code cancellationKey = "body.thermal-insulation"},
 * {@code magnitude = 这一身的总保暖系数}。换一件更厚的 → 新的一条把旧的一条挤掉
 * (替换而非叠加); 全脱光 → 投一条 {@code magnitude = 0} 的同 key 影响。
 * <b>"她脱掉了"和"她从没穿过"是两件不同的事</b> —— 前者在账本历史里留着,
 * 后者没有, 而这个区别正是"她 12:30 之后为什么开始觉得冷"的答案。
 *
 * <h2>它<b>不</b>负责什么</h2>
 * <ul>
 *   <li>不决定"该不该穿衣服" —— 那是 Mind 的事。本类只在<b>世界报告衣服穿上了</b>之后
 *       更新自己并产生事件;</li>
 *   <li>不做保暖值的计算 —— 那是 {@code HomeostasisModel} 的事。本类只提供
 *       "这一身的保暖系数是多少"这个输入;</li>
 *   <li>不知道 {@code warmth} 的存在。它知道的是 {@code Channels.WARMTH} 这个通道名,
 *       而那是账本的词汇, 不是她的体温。</li>
 * </ul>
 */
public final class ClothingSet {

    /**
     * 这一身衣服在账本上的身份 —— 用户与文档都明确要求的那一个 key。
     *
     * <p>它的语义是"<b>此刻的总体保暖能力</b>"。用 {@code cancellationKey} 表达它的收益是:
     * <b>不需要任何"撤销上一条"的代码</b> —— 账本自己会把上一条挤掉。少一段这样的代码,
     * 就少一处"忘了撤销"的 bug。
     */
    public static final String INSULATION_CANCELLATION_KEY = "body.thermal-insulation";

    private final String humanId;

    /** objectId → 衣服。用 LinkedHashMap 保持穿上顺序 —— 日志与重放要稳定。 */
    private final Map<String, WearableObject> worn = new LinkedHashMap<>();

    public ClothingSet(String humanId) {
        if (humanId == null || humanId.isBlank()) {
            throw new IllegalArgumentException(
                    "一身衣服必须属于某个人 —— 否则两件衣服会互相覆盖, 而她可能穿着别人的外套");
        }
        this.humanId = humanId;
    }

    public String humanId() {
        return humanId;
    }

    // ─────────────────────────── 穿脱 ───────────────────────────

    /**
     * 穿上一件。
     *
     * <p>已经穿着的衣服会直接返回 {@code false} —— 幂等, 不抛异常。理由: 这条路径的触发
     * 源头是世界事件({@code body.clothing-changed.v1} 的世界侧对应物), 而事件可能重复投递
     * (见 {@code EventFabric.publish} 关于去重的说明: 去重是投递方的责任)。
     * 一次重复投递让 agent 崩掉, 显然不如让它什么也不做。
     *
     * @return 这一次是不是真的穿上了
     */
    public boolean wear(WearableObject item) {
        Objects.requireNonNull(item, "要穿的衣服不能为空");
        if (worn.containsKey(item.objectId())) {
            return false;
        }
        item.equip(humanId);
        worn.put(item.objectId(), item);
        return true;
    }

    /**
     * 脱下。
     *
     * @return 被脱下的那件; 本来就没穿则返回空
     */
    public Optional<WearableObject> takeOff(String objectId) {
        Objects.requireNonNull(objectId, "要脱的衣服 id 不能为空");
        WearableObject item = worn.remove(objectId);
        if (item == null) {
            return Optional.empty();
        }
        item.remove(humanId);
        return Optional.of(item);
    }

    /** 全部脱掉 —— 洗澡、睡觉、换睡衣时用。返回脱下的那些。 */
    public List<WearableObject> takeOffAll() {
        List<WearableObject> removed = new ArrayList<>(worn.values());
        worn.clear();
        removed.forEach(item -> item.remove(humanId));
        return List.copyOf(removed);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    /** 现在穿着的全部衣服, 按穿上顺序。 */
    public List<WearableObject> worn() {
        return List.copyOf(worn.values());
    }

    public Optional<WearableObject> byId(String objectId) {
        return Optional.ofNullable(worn.get(objectId));
    }

    public boolean isEmpty() {
        return worn.isEmpty();
    }

    public int size() {
        return worn.size();
    }

    /** 盖住某个部位的衣服 —— 用于解释"风从哪进来"以及"她需要脱哪一件"。 */
    public List<WearableObject> covering(String part) {
        return worn.values().stream().filter(i -> i.covers(part)).toList();
    }

    /** 这一身盖住了哪些部位。 */
    public Set<String> coveredParts() {
        return worn.values().stream()
                .flatMap(i -> i.coveredParts().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ─────────────────────────── 三个综合系数 ───────────────────────────

    /** 干燥状态下的综合保暖。 */
    public ThermalInsulation insulation() {
        return insulation(0.0);
    }

    /**
     * 给定潮湿程度下的综合保暖。
     *
     * <p>每一件先按<b>它自己的</b>防水等级折算, 再层叠 —— 顺序不能反。
     * 反过来的话(先算总保暖, 再按总防水折算)会得到"一件雨衣能保护里面的羽绒服不湿"
     * 这种结论, 而现实是雨水从领口和下摆进去。
     */
    public ThermalInsulation insulation(double wetness) {
        ThermalInsulation total = ThermalInsulation.NONE;
        for (WearableObject item : worn.values()) {
            total = total.layered(item.insulation().wetted(wetness, item.waterproofLevel()));
        }
        return total;
    }

    /**
     * 无遮蔽的比例 {@code [0, 1]} —— <b>"寒风从哪里钻进来"</b>。
     *
     * <p>它由 {@code HomeostasisModel} 用来<b>放大失温</b>: 露着脖子不会让羽绒服本身变薄
     * (那是 {@link ThermalInsulation} 的事), 它只是让风从那里进来。这两个效果是<b>相乘</b>的,
     * 不是相加的 —— 所以它们必须是两个独立的量。
     *
     * <p>注意它取的是"覆盖部位权重之和", 而不是"件数": 一条盖住躯干 45% 的毯子
     * 与一件盖住躯干+手臂 57% 的羽绒服, 后面的窟窿不一样大。
     */
    public double coverageGap() {
        double covered = Math.min(1.0, coveredParts().stream()
                .mapToDouble(WearableObject.Parts::weightOf).sum());
        return Math.max(0.0, 1.0 - covered);
    }

    /**
     * 综合防水等级 —— 按覆盖面积加权平均。
     *
     * <p>为什么是加权平均而不是取最大: "她穿着雨衣" 不等于 "她全身防水 0.9" ——
     * 雨衣只盖住躯干和手臂, 裤腿照样湿。取最大会让"雨衣 + 短裤"在数值上等价于
     * "全身上下雨衣", 而那不是真人淋雨的经验。
     */
    public double waterproofLevel() {
        return weightedAverage(WearableObject::waterproofLevel);
    }

    /** 综合透气性 —— 同样按覆盖面积加权平均。跑步时穿不透气的雨衣会闷热。 */
    public double breathability() {
        return weightedAverage(WearableObject::breathability);
    }

    private double weightedAverage(java.util.function.ToDoubleFunction<WearableObject> field) {
        double weightSum = 0.0;
        double valueSum = 0.0;
        for (WearableObject item : worn.values()) {
            double w = Math.max(1e-6, item.coverageWeight());
            weightSum += w;
            valueSum += field.applyAsDouble(item) * w;
        }
        return weightSum <= 0.0 ? 0.0 : valueSum / weightSum;
    }

    // ─────────────────────────── 产生持续影响事件 ───────────────────────────

    /**
     * 这一身衣服此刻在账本上的那条影响。
     *
     * <p>调用时机是<b>穿着变了</b>的那一刻(穿上/脱下/换掉), 由 {@code Body} 投进
     * {@code EventFabric}。它<b>不是</b>每 tick 都产生的 —— 账本会一直持有它,
     * 直到被下一条同 key 的影响替换。这是用户那句"多穿衣服也是一个 event,
     * <b>能够持续影响</b> body 的保暖值"在代码上的落点:
     * <b>event 只发生一次, 影响持续存在</b>。
     *
     * @param added   这一次穿上的
     * @param removed 这一次脱下的
     * @param at      发生时刻(仿真时刻, 不是墙上时钟)
     */
    public ClothingChanged changedEvent(List<WearableObject> added, List<WearableObject> removed,
                                        Instant at) {
        Objects.requireNonNull(at, "穿衣事件必须带仿真时刻 —— 账本不读墙上时钟");
        ThermalInsulation total = insulation();
        return new ClothingChanged(
                added == null ? List.of() : added.stream().map(WearableObject::objectId).toList(),
                removed == null ? List.of() : removed.stream().map(WearableObject::objectId).toList(),
                total.asLedgerCoefficient(),
                coverageGap(),
                waterproofLevel(),
                breathability(),
                humanId,
                at);
    }

    public String describe() {
        if (worn.isEmpty()) {
            return "什么都没穿 (保暖 0.00, 无遮蔽 " + String.format("%.2f", coverageGap()) + ")";
        }
        return String.format("%d 件: %s | 保暖 %s | 无遮蔽 %.2f | 防水 %.2f | 透气 %.2f",
                worn.size(),
                worn.values().stream().map(WearableObject::displayName).toList(),
                insulation().describe(), coverageGap(), waterproofLevel(), breathability());
    }

    // ═══════════════════════════ 那一条持续影响 ═══════════════════════════

    /**
     * V2.2 §5.4.3 —— {@code body.clothing-changed.v1}: <b>穿着变了, 而保暖能力从此不同</b>。
     *
     * <h2>为什么它是 {@link StateEffectEvent} 而不是 {@code SensoryEvent}</h2>
     * 因为"她穿着羽绒服"这件事<b>不要求她做任何事</b>。它不惊动她, 它只是让
     * 下一个 tick 的热量散失变小 —— 也就是说, 它改变的是<b>账本</b>, 不是队列。
     * 把穿衣服做成实时刺激会得到一个荒谬的结果: 她每次穿衣服都会"注意到自己穿了衣服"。
     *
     * <h2>{@code magnitude} 是<b>总量</b>, 不是增量</h2>
     * 这一点是本类最容易做错的地方。{@code magnitude} 取"这一身的总保暖系数",
     * 配合 {@code cancellationKey} 完成替换:
     * <pre>{@code
     *   12:15 穿羽绒服   key=body.thermal-insulation  +0.85   ← 账上只有这一条
     *   12:40 再套一件外套 key=body.thermal-insulation  +0.92   ← 上一条被挤掉
     *   13:00 全脱掉     key=body.thermal-insulation  +0.00   ← 又是一条新的, 记录"她脱了"
     * }</pre>
     * 如果投的是"这一件衣服的增量", 三条账目会叠加成 {@code +1.77} ——
     * 而那会让 {@link ThermalInsulation} 的递减收益模型完全失效。
     *
     * <h2>载荷里的另外三个数不是装饰</h2>
     * {@code coverageGap} / {@code waterproofLevel} / {@code breathability} 会随事件一起
     * 落库 —— 因为"她 12:40 为什么还是冷"这个问题的答案往往不是保暖系数不够,
     * 而是<b>无遮蔽 0.43</b>(她没穿裤子)。少了这三个数, 行为分析只能看到一个不够大的数,
     * 而看不到它为什么不够大。
     */
    public record ClothingChanged(List<String> worn,
                                  List<String> removed,
                                  double totalThermalInsulation,
                                  double coverageGap,
                                  double waterproofLevel,
                                  double breathability,
                                  String humanId,
                                  Instant occurredAt) implements StateEffectEvent {

        /** 事件类型 —— 与 {@code CoreEventCatalog} 里登记的那一条必须完全一致。 */
        public static final EventTypeId TYPE =
                EventTypeId.of("body", "clothing-changed");

        static {
            // 把"名字拼错"从"她穿了衣服但没变暖"这个静默故障, 升级成类初始化期的硬失败。
            // 这类检查放在这里而不是文档里, 是因为文档不会在 CI 上运行。
            if (!CoreEventCatalog.isCore(TYPE)) {
                throw new IllegalStateException(
                        "事件类型 " + TYPE + " 不在 CoreEventCatalog 里 —— "
                                + "拼错的事件类型不会被任何 handler 认领, 表现是'她穿了衣服但保暖值没变'");
            }
        }

        /** 平台自带通道名 —— {@code body.warmth}。 */
        public static final String CHANNEL = CoreEventCatalog.Channels.WARMTH;

        public ClothingChanged {
            worn = worn == null ? List.of() : List.copyOf(worn);
            removed = removed == null ? List.of() : List.copyOf(removed);
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            Objects.requireNonNull(humanId, "事件必须知道这是谁穿的");
            if (totalThermalInsulation < 0.0 || totalThermalInsulation > 1.0) {
                throw new IllegalArgumentException(
                        "总保暖系数必须归一化到 [0, 1], 收到 " + totalThermalInsulation);
            }
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public double magnitude() {
            return totalThermalInsulation;
        }

        @Override
        public String effectChannel() {
            return CHANNEL;
        }

        @Override
        public String cancellationKey() {
            return INSULATION_CANCELLATION_KEY;
        }

        /**
         * 没有单一的来源对象 —— 这一身可能有三件。
         *
         * <p>{@code WorldEvent.sourceObjectId()} 允许为空, 而这里是它该为空的情形:
         * 强行挑一件当代表会让"她为什么变暖了"的因果链指向一件不相干的衣服。
         */
        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public String describe() {
            return "穿着变化 " + humanId + ": +" + worn + " -" + removed
                    + String.format(" → 保暖系数 %.2f (无遮蔽 %.2f)", totalThermalInsulation, coverageGap);
        }
    }

    // ═══════════════════════════ 平台自带的默认衣服 ═══════════════════════════

    /** 便捷: 一件干燥的羽绒服穿在身上的效果 —— 测试与文档示例用。 */
    public static ThermalInsulation referenceDownJacket() {
        return ThermalInsulation.of(0.85);
    }
}
