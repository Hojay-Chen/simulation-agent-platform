package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.human.life.plan.PlanConstraint;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 持久化测试的夹具 —— <b>本包所有往返测试共用的那几个"最小的领域对象"</b>。
 *
 * <h2>为什么需要一个夹具文件, 而不是每个测试各写各的</h2>
 * 因为这些夹具的形状本身就是被测的东西的一部分: 一个往返测试要证明的是
 * "存下去再读回来是同一个对象", 而这句话在被测对象身上要求两件事 ——
 * <ol>
 *   <li>它的字段覆盖到"会出问题的那些类型"（时间、时长、嵌套 record、集合）;</li>
 *   <li>它<b>有</b> {@code @DomainType}（否则它走的是"未注册"那条路径,
 *       那是另一个测试要证明的事, 见 {@link WorldEventStoreTest}）。</li>
 * </ol>
 * 分成几份写会让"哪些字段被测过"这件事散在好几个文件里, 而漏掉一个字段的
 * 表现是"它恰好没被任何测试覆盖" —— 一种只在真机上才暴露的空白。
 *
 * <h2>为什么这些夹具上<b>没有</b>任何 Jackson 注解</h2>
 * 这正是被测的东西: {@code PolymorphicSerializer} 假定领域对象是普通 record
 * （见 {@code AbstractActivity} 关于"为什么这些类上没有 Jackson 注解"的论证）。
 * 一份需要注解才能往返的夹具, 会让测试证明一件比实际更强的事。
 *
 * <h2>为什么意图夹具是"测试自己写的"而不是复用 {@code human/mind} 里那个</h2>
 * 因为 {@code PlanIntent} 在本仓只有一个实现（{@code IntentionPlanIntent}）,
 * 而它在一个<b>本层不该依赖</b>的包里。这里造一个最小的实现 ——
 * 它同时证明了另一件有价值的事: <b>一个三方意图实现只要标了 {@code @DomainType}
 * 并被注册, 就能被本层原样存取</b>（这正是 P4 那条"平台源码里永远不出现第三方类型名"
 * 的验收形式）。
 */
final class PersistenceFixtures {

    /** 意图夹具的类型名 —— 测试断言会直接引用它。 */
    static final String INTENT_TYPE = "fixture.test-intent.v1";

    /** 一种"持续影响"事件的类型名 —— {@link FixtureWarmth}。 */
    static final String WARMTH_TYPE = "fixture.warmth.v1";

    /**
     * 一个最小的 {@code PlanIntent} 实现 —— <b>三个字段, 覆盖三种序列化难点</b>:
     * <pre>
     *   IntentId        嵌套 record（值对象里套值对象）
     *   expectedMinutes 基本类型（它的 {@code expectedDuration()} 是算出来的）
     *   activityType    字符串枚举式的取值（决定 {@code ActivityFactory} 找哪个类）
     * </pre>
     * {@code expectedDuration()} 被<b>重写</b>成一个由组件算出来的值, 而不是
     * 一个额外的组件: 这样"往返之后 {@code expectedDuration()} 还是同一个值"
     * 就成了一条真的断言（组件对了但算法变了, 会在这里露出来）。
     */
    @DomainType(value = "fixture.test-intent", version = 1,
            description = "持久化测试用的最小意图: 三个字段, 覆盖嵌套值对象与算出来的时长")
    record FixtureIntent(PlanIntent.IntentId id, String description, String activityType,
                         long expectedMinutes) implements PlanIntent {

        static FixtureIntent of(String id, String description, String activityType, long minutes) {
            return new FixtureIntent(PlanIntent.IntentId.of(id), description, activityType, minutes);
        }

        /** 最常见的那一个: 落到一个真的注册过实现的活动类型上。 */
        static FixtureIntent studying(String id) {
            return of(id, "写作业", "life.activity.study", 45);
        }

        @Override
        public Feasibility evaluate(PlanningContext context) {
            return Feasibility.yes("夹具意图, 永远可行");
        }

        @Override
        public List<ActionIntent> decompose(PlanningContext context) {
            return List.of();
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }

        @Override
        public Duration expectedDuration() {
            return Duration.ofMinutes(expectedMinutes);
        }

        @Override
        public String activityType() {
            return activityType;
        }
    }

    /**
     * 一条"持续影响"事件 —— 覆盖 {@code ContinuousEffectLedger} 需要的那四个值。
     *
     * <p>用 {@code expiresAt}（绝对到期）而不是 {@code duration}（相对时长）:
     * 这两个是二选一的关系（见 {@code ContinuousEffectLedger.resolveExpiry}）,
     * 而绝对到期是<b>会被原样写进列</b>的那一种 —— 它让"列上的值与 JSON 里的值
     * 是不是同一个"这个问题有一个确定的答案。相对时长那一支由
     * {@link FixtureChill} 覆盖。
     */
    @DomainType(value = "fixture.warmth", version = 1, description = "测试用的一条保暖影响")
    record FixtureWarmth(EventTypeId typeId,
                         Instant occurredAt,
                         String sourceObjectId,
                         double magnitude,
                         String effectChannel,
                         String cancellationKey,
                         Instant expiresAt) implements StateEffectEvent {

        static FixtureWarmth of(double magnitude, String channel, String key, Instant expiresAt) {
            return new FixtureWarmth(EventTypeId.parse(WARMTH_TYPE), Instant.parse("2026-09-19T08:00:00Z"),
                    "world.test", magnitude, channel, key, expiresAt);
        }
    }

    /**
     * 另一条持续影响 —— <b>只给相对时长, 不给绝对到期</b>。
     *
     * <p>它存在的唯一理由是覆盖 {@code EffectLedgerStore.fidelityOf} 的第二条分支
     * （{@code startedAt + duration()}）。一个只测绝对到期的往返测试会漏掉它,
     * 而漏掉的表现是: 一条"两个小时后失效"的账目在重启之后变成"永不过期"——
     * 她身上的暖意比预期多留了很久, 且没有任何线索指向序列化。
     */
    @DomainType(value = "fixture.chill", version = 1, description = "测试用的一条只给相对时长的影响")
    record FixtureChill(EventTypeId typeId,
                        Instant occurredAt,
                        String sourceObjectId,
                        double magnitude,
                        String effectChannel,
                        String cancellationKey,
                        Duration duration) implements StateEffectEvent {

        static FixtureChill of(double magnitude, String channel, String key, Duration duration) {
            return new FixtureChill(EventTypeId.parse(CHILL_TYPE), Instant.parse("2026-09-19T08:05:00Z"),
                    "world.test", magnitude, channel, key, duration);
        }
    }

    /** {@link FixtureChill} 的类型名。 */
    static final String CHILL_TYPE = "fixture.chill.v1";

    /**
     * 一个<b>没有</b> {@code @DomainType} 的持续影响 —— 它是"读不回来"那条路径的输入。
     *
     * <p>它混在两个已注册的夹具中间, 是为了让"未注册"这件事看起来像它在真实世界里
     * 的样子: <b>一个三方插件被卸载之后, 库里就是混着这两种行的</b>。
     * 一个把未注册的行单独放在一个空库里的测试, 证明不了"其余的行还能读"。
     */
    record UnregisteredEffect(EventTypeId typeId,
                              Instant occurredAt,
                              String sourceObjectId,
                              double magnitude,
                              String effectChannel,
                              String cancellationKey,
                              Instant expiresAt) implements StateEffectEvent {

        static UnregisteredEffect of(double magnitude, String channel, String key, Instant expiresAt) {
            return new UnregisteredEffect(EventTypeId.of("fixture", "retired-plugin-effect"),
                    Instant.parse("2026-09-19T08:10:00Z"), "world.test", magnitude, channel, key,
                    expiresAt);
        }
    }

    /**
     * 一条最小的 {@code PlanConstraint} —— 约束是 {@code PlanStore} 里唯一一处
     * <b>会跨版本重复出现</b>的东西（{@code PlanRevision.next} 把
     * {@code constraints} 原样传给下一版）, 所以它必须有一个能往返的实现。
     *
     * <p>本仓目前<b>没有</b>任何 {@code PlanConstraint} 的实现类
     * （{@code human/life/plan} 下只有接口）—— 于是这一条同时也是
     * "一个三方约束只要标了 {@code @DomainType} 并被注册, 就能被本层原样存取"
     * 的证明, 与 {@link FixtureIntent} 证明的是同一件事。
     *
     * <p>{@code satisfied} 是一个字段而不是常量: 它让"往返之后
     * {@code evaluate} 的行为不变"成为一条真的断言（一个只写死返回 true 的
     * 约束, 会让"评估结果在往返里变了"这件事无从检验）。
     */
    @DomainType(value = "fixture.no-night", version = 1,
            description = "测试用的一条约束: 内容无所谓, 形状才重要")
    record FixtureConstraint(PlanConstraint.ConstraintId id, String label, boolean satisfied)
            implements PlanConstraint {

        static FixtureConstraint of(String id, String label) {
            return new FixtureConstraint(PlanConstraint.ConstraintId.of(id), label, true);
        }

        static FixtureConstraint violated(String id, String label) {
            return new FixtureConstraint(PlanConstraint.ConstraintId.of(id), label, false);
        }

        @Override
        public ConstraintResult evaluate(PlanItem item, PlanningContext context) {
            return satisfied
                    ? ConstraintResult.ok(label)
                    : ConstraintResult.violated(label, Severity.SOFT);
        }

        @Override
        public String describe() {
            return label;
        }
    }

    /** {@link FixtureConstraint} 的类型名。 */
    static final String CONSTRAINT_TYPE = "fixture.no-night.v1";

    // ─────────────────────────── 装配 ───────────────────────────

    /**
     * 一个注册了本夹具全部类型、外加一个平台内建事件的注册表。
     *
     * <p>刻意<b>不</b>注册 {@link UnregisteredEffect} 与
     * {@code WorldEventStoreTest.RetiredPluginEvent} —— 它们的未注册是那些测试的设计,
     * 而不是一个忘了写的东西。
     *
     * <h2>为什么一个"夹具"注册表里会出现平台内建类型 {@code PlanEvents.ItemDue}</h2>
     * 因为它服务的是一条<b>光靠夹具证明不了</b>的断言: "三列元数据是给别人查的,
     * 所以它们得真的被填上"。一条夹具事件的三列填对了, 只能证明夹具自己的
     * {@code @DomainType} 写对了; 而真实世界里被写进 {@code world_event} 的行
     * 绝大多数是<b>平台内建事件</b>（计划到期、状态变化）, 它们的三列才是
     * 那些索引与目录真正要服务的对象。
     * <p>换句话说, 这里不是"夹具注册表顺便多注册了一个类", 而是
     * "这一条用例的被测对象是一个内建事件, 所以它必须在场"——
     * 真装配时的注册表（见 {@code registry} 包的装配入口）会把
     * {@code PlanEvents} 的全部 record 一起注册, 这里复现的是那个装配结果的最小切片。
     */
    static DomainTypeRegistry registry() {
        DomainTypeRegistry registry = new DomainTypeRegistry();
        registry.register(FixtureIntent.class);
        registry.register(FixtureWarmth.class);
        registry.register(FixtureChill.class);
        registry.register(PlanEvents.ItemDue.class);
        return registry;
    }

    /** 与 {@link #registry()} 配套的编解码器。 */
    static DomainPayloadCodec codec() {
        return new DomainPayloadCodec(registry());
    }

    /**
     * 一个<b>什么都不认识</b>的注册表 —— "这一侧没有任何实现类"的极端情形。
     *
     * <p>它服务的断言是"读不出来时必须炸, 而不是降级"。用一个"认识一半"的注册表
     * 测这件事是不充分的: 那样失败的原因可能只是"恰好少了一个", 而这里要证明的是
     * <b>只要读不出来就一定会被看见</b> —— 所以用最空的注册表。
     */
    static DomainTypeRegistry emptyRegistry() {
        return new DomainTypeRegistry();
    }

    /** 与 {@link #registry()} 配套的编解码器, 但用一个显式给了 mapper 的序列化器。 */
    static DomainPayloadCodec codec(DomainTypeRegistry registry) {
        return new DomainPayloadCodec(registry, new PolymorphicSerializer(registry));
    }

    private PersistenceFixtures() {
    }
}
