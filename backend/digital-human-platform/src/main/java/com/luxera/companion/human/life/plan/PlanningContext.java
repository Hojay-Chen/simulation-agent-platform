package com.luxera.companion.human.life.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.5.2 / §3.5.6 —— <b>做计划判断时能看到的全部事实</b>。
 *
 * <h2>为什么它必须是一个显式的参数对象</h2>
 * {@link PlanIntent#evaluate}、{@link PlanConstraint#evaluate}、{@link PlanReplanner}
 * 都需要"现在的情况"。最省事的做法是各自去拿（注入一堆服务、读全局状态），
 * 但那会带来一个在这个系统里特别致命的后果: <b>重排的结果不可复现</b>。
 *
 * <p>行为分析要回答"她 12:15 为什么决定先穿衣服"。如果当时的判断依赖于
 * "读的时候恰好是什么状态", 那么事后回放会得到不同的结论 —— 而一个回放不出
 * 当初结论的行为分析系统, 对研究毫无价值。
 *
 * <p>把全部输入收进一个不可变的 context, 于是"给她这个 context, 她会怎么排"
 * 变成了一个<b>纯函数式的问句</b>, 可以反复问、可以离线问、可以用在测试里问。
 *
 * <h2>它<b>不</b>包含什么</h2>
 * 刻意不含"当前计划该怎么继续"这类暗示。用户否定"暂停/恢复"的语义时, 核心
 * 理由就是那个思路里预设了"被中断的事必然会继续"。context 只给事实, 不给倾向。
 *
 * <p>也不含"她上一次重排是什么时候"这类自我观测 —— 那属于重排器的内部状态
 * （防抖用）, 不属于世界的事实。
 *
 * @param now                   仿真时刻
 * @param human                 她此刻的身体状态（窄接口, 见 {@link HumanSnapshot}）
 * @param constraints           当前生效的全部约束
 * @param availableCapabilities 她此刻<b>真的能用</b>的能力 key 集合。
 *                              注意是"能用"不是"存在" —— 手机没电时
 *                              {@code chat.send-message} 存在但不可用,
 *                              而"现在能不能给她回个电话"取决于后者
 * @param locations             她此刻能去的地方（key → 一句话描述）。
 *                              用于 {@code AttendPlaceIntent} 的可行性判断
 * @param attributes            其它零散事实的自由字典。
 *                              <b>为什么留这个口子</b>: 第三方的 {@link PlanIntent}
 *                              实现会需要平台不知道的事实（"实验室今天开不开门"）。
 *                              让它们自己往这里放, 好过让它们各自去找全局状态 ——
 *                              后者会破坏"重排可复现"
 */
public record PlanningContext(
        Instant now,
        HumanSnapshot human,
        List<PlanConstraint> constraints,
        Set<String> availableCapabilities,
        Map<String, String> locations,
        Map<String, Object> attributes) {

    public PlanningContext {
        Objects.requireNonNull(now, "做计划判断必须带仿真时刻 —— 计划是关于时间的");
        Objects.requireNonNull(human, "必须能看到她的身体状态 —— "
                + "'她还撑得住吗'是计划可行性的一部分");
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        availableCapabilities = availableCapabilities == null ? Set.of() : Set.copyOf(availableCapabilities);
        locations = locations == null ? Map.of() : Map.copyOf(locations);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 最小可用的 context —— 测试与"什么都还不知道"的启动瞬间用。 */
    public static PlanningContext minimal(Instant now, HumanSnapshot human) {
        return new PlanningContext(now, human, List.of(), Set.of(), Map.of(), Map.of());
    }

    /** 换一个时刻, 其余不变。用于"同一套条件下, 换个时间点再问一次"。 */
    public PlanningContext at(Instant other) {
        return new PlanningContext(other, human, constraints, availableCapabilities, locations, attributes);
    }

    /** 换一个能力可用集 —— 用于"如果手机有电, 这件事做得了吗"这类反问。 */
    public PlanningContext withCapabilities(Set<String> capabilities) {
        return new PlanningContext(now, human, constraints, capabilities, locations, attributes);
    }

    /** 她有没有某个能力可用。 */
    public boolean can(String capabilityKey) {
        if (capabilityKey == null || capabilityKey.isBlank()) {
            return false;
        }
        return availableCapabilities.contains(capabilityKey);
    }

    /**
     * 她有没有这一族能力可用（按前缀）。
     *
     * <p>用途: 一个意图说"我需要聊天能力", 而具体是 {@code chat.send-message}
     * 还是 {@code chat.send-voice} 取决于她想怎么发。<b>按前缀判断让意图不必
     * 绑死到某一个具体能力上</b>, 从而在聊天软件换了实现时不用改意图。
     */
    public boolean canAnyUnder(String namespacePrefix) {
        if (namespacePrefix == null || namespacePrefix.isBlank()) {
            return false;
        }
        String prefix = namespacePrefix.endsWith(".") ? namespacePrefix : namespacePrefix + ".";
        return availableCapabilities.stream().anyMatch(k -> k.startsWith(prefix));
    }

    /** 读一个零散事实。 */
    public Optional<Object> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public Optional<String> location(String key) {
        return Optional.ofNullable(locations.get(key));
    }

    /** 全部可行约束里<b>不</b>成立的那些 —— 用于诊断"为什么她排不出计划"。 */
    public List<String> violationsFor(PlanItem item) {
        return constraints.stream()
                .map(c -> c.evaluate(item, this))
                .filter(r -> !r.satisfied())
                .map(PlanConstraint.ConstraintResult::describe)
                .toList();
    }

    // ─────────────────────────── 身体的窄视图 ───────────────────────────

    /**
     * {@link HumanSnapshot} —— 计划系统需要的<b>那一小部分</b>身体信息。
     *
     * <h3>为什么是接口而不是直接引用 {@code human.body.PhysiologicalState}</h3>
     * 三个理由, 每一个都足够:
     * <ol>
     *   <li><b>依赖方向。</b>{@code human/life/plan} 直接 import {@code human/body}
     *       会让"身体"成为"计划"的编译期依赖。而身体的变化（加一条感官通道、
     *       改一个生理量）不应该导致计划包重新编译 —— 它们是两个可以独立演进的概念;</li>
     *   <li><b>接口隔离。</b>身体持有几十个生理量, 而计划可行性只关心其中三四个
     *       （撑得住吗、冷不冷、困不困）。直接拿到整个 {@code PhysiologicalState}
     *       会让 {@link PlanIntent} 的实现者开始读各种它不该依赖的量 ——
     *       因为它们就在那里;</li>
     *   <li><b>可测试性。</b>给计划系统写测试时, 一个 {@code HumanSnapshot} 的
     *       匿名实现只要三行; 而构造一个真实的 {@code PhysiologicalState}
     *       要先把稳态模型、账本、感官通道全装配起来。</li>
     * </ol>
     *
     * <p>将来真实机器人的传感器数据接进来时, 也是实现这个接口 —— 计划系统不需要知道
     * 那些数字是仿真出来的还是从真人体感器读来的。
     */
    public interface HumanSnapshot {

        /**
         * 读一个生理通道的当前值。
         *
         * <p>通道名用 {@code CoreEventCatalog.Channels} 里的常量
         * （{@code body.warmth}、{@code body.energy}…）。返回
         * {@link Optional#empty()} 表示"她此刻没有这个通道"（比如系统还没开始模拟体温）,
         * 而 {@code Optional.of(0.0)} 表示"这个通道的值是零" —— 两者含义完全不同。
         */
        Optional<Double> channel(String channel);

        /** 一句话描述她此刻的状态, 进 LLM context。例: "有点冷, 精力还行"。 */
        String summary();

        /**
         * 她是否处于可执行计划项的状态。
         *
         * <p>睡着的时候不该被排进"写作业"。这个判断在身体侧（因为只有身体知道
         * 她睡没睡）, 但结论要被计划侧用到。
         */
        default boolean availableForActivity() {
            return true;
        }

        /** 一个什么都没有的占位实现 —— 给测试与启动瞬间用。 */
        static HumanSnapshot unknown() {
            return new HumanSnapshot() {
                @Override
                public Optional<Double> channel(String channel) {
                    return Optional.empty();
                }

                @Override
                public String summary() {
                    return "状态未知";
                }
            };
        }
    }
}
