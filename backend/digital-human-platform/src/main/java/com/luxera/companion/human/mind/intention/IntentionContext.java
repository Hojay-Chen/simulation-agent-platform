package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanningContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.4.7 —— <b>一个意图判断"现在做不做得了"时能看到的全部事实</b>。
 *
 * <h2>与文档的分歧: 它替代了 {@code WorldSnapshot}</h2>
 * §3.4.7 的接口示例写的是:
 *
 * <pre>{@code
 * Feasibility evaluate(WorldSnapshot world, HumanSnapshot human);
 * }</pre>
 *
 * <p>而 {@code WorldSnapshot} 是<b>世界侧的类型</b>, 它在 {@code world/} 包里。
 * 参数里出现它, 等于让 {@code human.mind.intention} 依赖 {@code world} ——
 * 验收标准 F(§9)的 ArchUnit 断言会当场变红, 而那条断言是 P3 的物理保障。
 *
 * <p>所以这里改成<b>她自己那一侧的一份投影</b>。这不是把问题藏起来, 而是把它放对位置:
 * "世界上现在有什么"由别人去查, 查完<b>翻译成她的词汇</b>再交给她。这份投影由
 * {@link #from(PlanningContext)} 构造 —— 计划侧已经有一份一模一样形状的
 * {@code PlanningContext}(它同样是"做判断时的全部事实"), 于是这里不需要第二个来源。
 *
 * <p>这条替代还有一个副作用是好的: {@code WorldSnapshot} 会诱惑实现者去
 * <b>现查世界</b>("让她看看外面下不下雨"), 而那份查询的时机由调用方决定,
 * 不由她决定 —— 现在类型上就没有"查"这个动作, 只有一个已经查好的值。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不做反向转换</b>。没有 {@code toPlanningContext(...)} —— 那个方向需要
 *       一个 {@code HumanSnapshot}, 而它来自身体, 不属于这一层。缺了它,
 *       跨层调用就只能单向地从计划侧流过来, 这正是想要的方向;</li>
 *   <li><b>不预置任何"现在几点该干什么"</b>。它只给事实与能力, 不给倾向 ——
 *       倾向是 {@link Intention} 自己的事。</li>
 * </ul>
 *
 * @param now                   仿真时刻
 * @param availableCapabilities 她此刻<b>真的能用</b>的能力 key 集合。注意"能用"不是"存在":
 *                              手机没电时发消息的能力存在但不可用, 而"现在能不能回她一句"
 *                              取决于后者
 * @param humanSummary          她此刻身体状态的一句话 —— 进 LLM context, 也用于
 *                              "我撑不撑得住"这类判断。刻意是<b>一句话而不是结构体</b>:
 *                              需要精确通道值的判断在身体侧就该做完, 把通道表搬到这里
 *                              会让每个意图都开始自己解释生理数值
 * @param locationLabel         她现在在哪儿的一句话描述。这是 {@code WorldSnapshot} 里
 *                              最有用的那一小块 —— 意图的可行性常常就是"离得远不远"
 * @param knownPlaces           她知道的、能去的地方(key → 一句话)。开放取值, 因为
 *                              她会不断知道新地方
 * @param attributes            零散事实的开放袋子。用它而不是不断加字段, 理由同
 *                              {@link PlanningContext#attributes()}: 加字段会逼每个
 *                              实现类跟着改
 */
public record IntentionContext(
        Instant now,
        Set<String> availableCapabilities,
        String humanSummary,
        String locationLabel,
        Map<String, String> knownPlaces,
        Map<String, Object> attributes) {

    public IntentionContext {
        Objects.requireNonNull(now, "做可行性判断必须带仿真时刻 —— 不许读系统时钟");
        availableCapabilities = availableCapabilities == null ? Set.of() : Set.copyOf(availableCapabilities);
        humanSummary = humanSummary == null ? "状态未知" : humanSummary;
        locationLabel = locationLabel == null ? "不知道自己在哪儿" : locationLabel;
        knownPlaces = knownPlaces == null ? Map.of() : Map.copyOf(knownPlaces);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * 从计划侧的 context 造一份 —— <b>两个层之间唯一的桥</b>。
     *
     * <p>丢掉的东西是显式的: 约束列表与地点表在 {@link PlanningContext} 里是给
     * 重排器用的, 她自己的意图判断用不到它们(用得到的话, 那件事该由重排器判断,
     * 而不是由她判断)。丢掉而不是原样带过来, 是为了让"她看到了什么"这个清单
     * 不随计划侧加字段而悄悄变长。
     */
    public static IntentionContext from(PlanningContext context) {
        Objects.requireNonNull(context, "计划上下文不能为空 —— 见本类关于两份 context 的说明");
        return new IntentionContext(
                context.now(),
                context.availableCapabilities(),
                context.human() == null ? null : context.human().summary(),
                context.attributes().getOrDefault("locationLabel", "").toString(),
                context.locations(),
                context.attributes());
    }

    /** 一个"什么都还不知道"的起点 —— 测试与启动瞬间用。 */
    public static IntentionContext minimal(Instant now) {
        return new IntentionContext(now, Set.of(), "状态未知", "不知道自己在哪儿", Map.of(), Map.of());
    }

    public IntentionContext at(Instant other) {
        return new IntentionContext(other, availableCapabilities, humanSummary, locationLabel,
                knownPlaces, attributes);
    }

    public IntentionContext withCapabilities(Set<String> capabilities) {
        return new IntentionContext(now, capabilities, humanSummary, locationLabel, knownPlaces, attributes);
    }

    public IntentionContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new IntentionContext(now, availableCapabilities, humanSummary, locationLabel,
                knownPlaces, merged);
    }

    /** 她有没有某个能力可用。 */
    public boolean can(String capabilityKey) {
        return capabilityKey != null && !capabilityKey.isBlank()
                && availableCapabilities.contains(capabilityKey);
    }

    /** 她有没有这一族能力可用(按前缀)—— 见 {@link PlanningContext#canAnyUnder}。 */
    public boolean canAnyUnder(String namespacePrefix) {
        if (namespacePrefix == null || namespacePrefix.isBlank()) {
            return false;
        }
        String prefix = namespacePrefix.endsWith(".") ? namespacePrefix : namespacePrefix + ".";
        return availableCapabilities.stream().anyMatch(k -> k.startsWith(prefix));
    }

    public Optional<Object> attribute(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(attributes.get(key));
    }

    public Optional<String> place(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(knownPlaces.get(key));
    }

    public String describe() {
        return "此刻 " + now + ", 在" + locationLabel + "(" + humanSummary + "), 可用能力 "
                + availableCapabilities.size() + " 项";
    }

    @Override
    public String toString() {
        return describe();
    }
}
