package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainTypeRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §8.4 —— <b>把"要做什么"变成一个具体的活动对象</b>。
 *
 * <h2>它解决的问题</h2>
 * 她决定开始写作业。这一刻必须选出一个具体类: {@link StudyActivity}、
 * {@link WorkActivity}, 还是 {@link OtherActivity}? 谁来做这个选择?
 *
 * <table border="1">
 *   <tr><th>做法</th><th>问题</th></tr>
 *   <tr>
 *     <td>❌ 在 {@code Life} 里写 {@code if (intent instanceof HomeworkIntent)}</td>
 *     <td>每加一种活动都要改 {@code Life} 的源码 —— 而 {@code Life} 是核心。
 *         第三方接入"打游戏"要动平台的核心类, 这是设计文档明令禁止的</td>
 *   </tr>
 *   <tr>
 *     <td>❌ 让 {@link PlanIntent} 直接返回 {@code Class<? extends Activity>}</td>
 *     <td>造成 {@code human.life.plan} ⇄ {@code human.life.activity} <b>包循环依赖</b></td>
 *   </tr>
 *   <tr>
 *     <td>❌ 按意图的<b>类名</b>去猜活动类型（{@code "Homework" → Study}）</td>
 *     <td>命名约定不是契约。改一个类名会让她的行为悄悄变样, 而那种变化
 *         在数据里只表现为"她做作业时的注意力变了", 没人会往重命名上想</td>
 *   </tr>
 *   <tr>
 *     <td>✅ 意图<b>声明</b>一个活动类型名, 工厂查表</td>
 *     <td>意图说"我是哪一类", 工厂负责造。加一种活动 = 注册一个名字 + 一个实现类</td>
 *   </tr>
 * </table>
 *
 * <h2>名字与 {@code @DomainType} 是同一套</h2>
 * 十二个内置活动的类型名就是它们 {@code @DomainType} 的值
 * （{@code "life.activity.study"} 等）。这不是巧合: <b>同一个名字既用于
 * JSONB 落库时的 {@code _type}, 也用于这里的工厂查表</b> ——
 * 于是"她知道该怎么造它"与"她知道该怎么读回来"永远是同一个问题的两个面。
 * 两套名字（一套落库、一套工厂）迟早会不一致, 而不一致的症状是
 * "有一类活动读回来就变成了其他"。
 *
 * <h2>未注册的类型名怎么办</h2>
 * 不抛异常, 落到 {@link OtherActivity} 并记一条 <b>WARN</b>。
 * 理由与 {@code EventFabric} 对第三方 handler 的态度一致:
 * 一个三方活动没注册好, 不该让她<b>无法开始做事</b> ——
 * 那会让整个 agent 停摆, 而停摆的行为分析价值是零。
 *
 * <p>但 WARN 必须响: 它的数量是一个可查的指标。数量高说明有一类活动
 * 该被建模了（或者某个三方忘注册了）, 而这两种情况都需要有人看见。
 */
@Slf4j
public final class ActivityFactory {

    /** 造一个活动。{@code planItemId} 可能为 {@code null}（计划表外的活动）。 */
    @FunctionalInterface
    public interface ActivityCreator {
        Activity create(PlanIntent intent, Instant startedAt, PlanItemId planItemId);
    }

    private static final Map<String, ActivityCreator> CREATORS = new LinkedHashMap<>();

    /** 未注册的类型名被用了几次 —— 诊断用。 */
    private static int unregisteredUses;

    static {
        register("life.activity.sleep", SleepActivity::new);
        register("life.activity.study", StudyActivity::new);
        register("life.activity.work", WorkActivity::new);
        register("life.activity.meal", MealActivity::new);
        register("life.activity.commute", CommuteActivity::new);
        register("life.activity.exercise", ExerciseActivity::new);
        register("life.activity.leisure", LeisureActivity::new);
        register("life.activity.social", SocialActivity::new);
        register("life.activity.housework", HouseworkActivity::new);
        register("life.activity.hobby", HobbyActivity::new);
        register("life.activity.rest", RestActivity::new);
        register("life.activity.other", OtherActivity::new);
    }

    private ActivityFactory() {
    }

    /**
     * 注册一类活动。
     *
     * <p>第三方接入的入口。注意<b>同名覆盖会记 WARN</b> 而不是静默生效:
     * 两个不同的实现在抢同一个类型名是一个必须被发现的配置问题 ——
     * 它会让她对同一件事的行为在两个部署之间不一致, 而那种不一致极难归因。
     *
     * @return 之前有没有同名的注册
     */
    public static synchronized boolean register(String activityType, ActivityCreator creator) {
        Objects.requireNonNull(activityType, "活动类型名不能为空");
        Objects.requireNonNull(creator, "活动的构造器不能为空");
        if (activityType.isBlank()) {
            throw new IllegalArgumentException("活动类型名不能是空字符串");
        }
        ActivityCreator previous = CREATORS.put(activityType, creator);
        if (previous != null) {
            log.warn("[ActivityFactory] 类型名 {} 被重复注册 —— 新的实现会覆盖旧的。"
                    + "两个实现抢同一个名字会让她的行为在不同部署之间不一致", activityType);
        }
        return previous != null;
    }

    /**
     * 她开始做这件事。
     *
     * <p>返回的活动类型会与 {@link PlanIntent#activityType()} 声明的一致 ——
     * 不一致时记 WARN 但仍然返回（见 {@link #startChecked} 的说明）。
     */
    public static Activity start(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        Objects.requireNonNull(intent, "意图不能为空 —— 没有它就没有'她在做什么'");
        Objects.requireNonNull(startedAt, "开始时刻不能为空");

        String requested = intent.activityType();
        ActivityCreator creator = CREATORS.get(requested);
        if (creator == null) {
            synchronized (ActivityFactory.class) {
                unregisteredUses++;
            }
            log.warn("[ActivityFactory] 活动类型 {}（意图「{}」）没有注册任何实现, "
                            + "落到中庸档案的 OtherActivity。"
                            + "这通常意味着有一类活动该被建模了, 或者某个三方忘了注册",
                    requested, intent.description());
            return new OtherActivity(intent, startedAt, planItemId);
        }

        Activity created = creator.create(intent, startedAt, planItemId);
        if (created == null) {
            log.error("[ActivityFactory] 类型 {} 的构造器返回了 null —— "
                    + "落到 OtherActivity, 但这是那一段代码的 bug", requested);
            return new OtherActivity(intent, startedAt, planItemId);
        }
        return created;
    }

    /**
     * 从数据库读回来的一次执行 —— <b>{@link #start} 的逆运算</b>。
     *
     * <h2>为什么必须走这里, 而不是让持久化层自己 new 一个</h2>
     * 两条路都不行:
     * <ul>
     *   <li><b>持久化层 new 不了</b>: 它看不见 {@code CommonFields}（{@code protected}）,
     *       也拿不到某类活动的"同类型换字段"那一处样板
     *       （{@code AbstractActivity.create} 是 {@code protected abstract}）。
     *       这两条限制是刻意的 —— 活动内部形状不该被外部直接摆弄;</li>
     *   <li><b>用反射填字段更不行</b>: 它会把"少恢复了一个字段"变成一个静默的
     *       {@code null}, 于是她要带着半个自己的状态继续活 ——
     *       而一个状态不全的 agent 比一个起不来的 agent 难查得多。</li>
     * </ul>
     *
     * <h2>为什么 {@code activityType} 与 {@code snapshot.intent()} 是两个参数</h2>
     * 它们<b>不一定一致</b>, 而那个不一致本身就是数据（见 {@code AbstractActivity}
     * 关于"为什么这些类上没有 Jackson 注解"那一节）:
     * 意图说 {@code life.activity.gaming}, 而 {@code ActivityFactory} 当时因为三方
     * 没注册而落到了 {@code OtherActivity}。于是这一行记录的是
     * "她<b>实际</b>做的那一类"（{@code activity_type} 列）,
     * 而 {@code intent} 是"她当时想的"（{@code intent_json} 列）。
     *
     * <p>恢复时必须<b>按 {@code activityType} 找类</b>, 而不是按
     * {@code intent.activityType()} —— 否则一次"三方忘了注册"的历史会被
     * 恢复成另一类活动, 于是"她本来要做的事没做成"这条记录在重启后消失。
     * 这正是一直复用 {@code start} 会导致的错误。
     *
     * <h2>约束: 能被恢复的活动类型必须继承 {@code AbstractActivity}</h2>
     * 这是本方法唯一的前置条件, 而它<b>会在违反时抛异常</b>而不是降级 ——
     * 降级的后果是"恢复出来的活动的 id 与库里的不一样", 而那会让所有指向
     * 这条执行的后续事件悬空。第三方要接入并被持久化, 继承
     * {@link AbstractActivity} 是必要条件。
     *
     * @throws IllegalStateException 那个类型的实现不是 {@code AbstractActivity} 的子类
     */
    public static Activity restore(String activityType, ActivitySnapshot snapshot) {
        Objects.requireNonNull(activityType, "恢复一次执行必须知道它属于哪一类活动");
        Objects.requireNonNull(snapshot, "恢复一次执行必须带快照 —— 没有字段就无从恢复");

        Activity created = instantiate(activityType, snapshot);
        if (!(created instanceof AbstractActivity base)) {
            throw new IllegalStateException(
                    "活动类型 " + activityType + " 的实现是 " + created.getClass().getName()
                            + ", 它不是 AbstractActivity 的子类 —— 因此无法把库里读出来的"
                            + " id / 状态 / 结束时刻 灌回它。"
                            + "继续下去只有两条路, 而两条都是错的: 要么用一个新生成的 id"
                            + "（于是所有指向这条执行的后续事件悬空）, 要么丢掉库里那个状态"
                            + "（于是她带着错误的状态继续活）。请让这个类型继承 AbstractActivity。");
        }

        AbstractActivity.CommonFields fields = AbstractActivity.CommonFields.restore(
                snapshot.id(), snapshot.intent(), snapshot.planItemId(), snapshot.startedAt(),
                snapshot.state(), snapshot.endedAt(), snapshot.closingNote(),
                snapshot.finalProgress());
        return base.create(fields);
    }

    /**
     * 造一个该类型的实例, <b>字段还是空的</b> —— {@link #restore} 的前半步。
     *
     * <p>刻意与 {@link #start} 共用同一条"找不到就落 {@code OtherActivity}"的降级路径,
     * 但<b>不</b>共用 {@code start} 本身: {@code start} 用的是
     * {@code intent.activityType()} 去找类, 而恢复要找的是
     * {@code activity_type} 那一列（见 {@code restore} 的说明）。
     * 这两个输入不总是一样, 而那正是这张表要保存的信息。
     */
    private static Activity instantiate(String activityType, ActivitySnapshot snapshot) {
        ActivityCreator creator = CREATORS.get(activityType);
        if (creator == null) {
            synchronized (ActivityFactory.class) {
                unregisteredUses++;
            }
            log.warn("[ActivityFactory] 恢复执行 {} 时, 活动类型 {} 没有注册任何实现, "
                            + "落到中庸档案的 OtherActivity。"
                            + "这通常意味着某个三方忘了注册, 或者它已经被卸载",
                    snapshot.id(), activityType);
            return new OtherActivity(snapshot.intent(), snapshot.startedAt(), snapshot.planItemId());
        }
        Activity created = creator.create(snapshot.intent(), snapshot.startedAt(),
                snapshot.planItemId());
        if (created == null) {
            log.error("[ActivityFactory] 类型 {} 的构造器返回了 null（恢复路径）—— "
                    + "落到 OtherActivity, 但这是那一段代码的 bug", activityType);
            return new OtherActivity(snapshot.intent(), snapshot.startedAt(), snapshot.planItemId());
        }
        return created;
    }

    // ─────────────────────────── 查询与诊断 ───────────────────────────

    public static Optional<ActivityCreator> creatorFor(String activityType) {
        return Optional.ofNullable(CREATORS.get(activityType));
    }

    /** 已经注册的全部类型名。 */
    public static Set<String> registeredTypes() {
        return Set.copyOf(CREATORS.keySet());
    }

    public static boolean isRegistered(String activityType) {
        return CREATORS.containsKey(activityType);
    }

    public static synchronized int unregisteredUses() {
        return unregisteredUses;
    }

    /**
     * 一个意图会被造成哪一类活动 —— 不真的造, 只回答类型名。
     *
     * <p>诊断面板与测试用。它让"这一项她做起来是什么样"可以在不产生副作用
     * 的情况下被查到 —— 而"不产生副作用"很重要, 因为造一个活动会给它分配
     * 一个新的 {@link ActivityId}, 而 id 一旦生成就会进序列。
     */
    public static String resolveTypeOf(PlanIntent intent) {
        Objects.requireNonNull(intent, "意图不能为空");
        String requested = intent.activityType();
        return CREATORS.containsKey(requested) ? requested : "life.activity.other";
    }

    /** 全部类型名, 每行一个, 带是否已注册。诊断用。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder("[ActivityFactory] 已注册 ")
                .append(CREATORS.size()).append(" 类活动");
        CREATORS.keySet().forEach(t -> sb.append("\n  · ").append(t));
        int missed = unregisteredUses();
        if (missed > 0) {
            sb.append("\n  未注册的类型被用了 ").append(missed)
                    .append(" 次 ← 需要处理: 有一类活动还没被建模, 或某个三方忘了注册");
        }
        return sb.toString();
    }

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>十二类活动的类型名, 由本类自己登记</b> —— 装配层调用。
     *
     * <h2>为什么登记这件事归这里, 而不归那十二个类各自</h2>
     * 因为 {@link #CREATORS} 已经把"有哪几类活动"写死在同一个文件里了 ——
     * 它的键就是十二个 {@code @DomainType} 的原值, 值就是那十二个类的构造器。
     * 让 {@link StudyActivity} 自己写一个只登记自己的 {@code registerTypes} 并不会
     * 让这张表少一行: 本类仍然必须知道全部十二个(它要把 {@code activityType}
     * 这个字符串解析成类)。于是"加一类活动"从<b>改一个地方</b>变成改两个地方,
     * 而漏掉的那一个不会报错 —— 它的表现是"她做这一类事的那段历史读不回来",
     * 与原因隔着一整个重启。
     * <p>放在这里之后, 新增一类活动仍然只做原来那两件事: 写一个类, 在 static 块里
     * {@code register(...)} 一行。类型名与它的载荷形状因此从不分开。
     *
     * <h2>为什么不扫描 classpath</h2>
     * 见 {@link DomainTypeRegistry} 的类注释: <b>装配顺序可控, 而 classpath 扫描顺序
     * 不可控</b>。扫描还会让 {@code DomainTypeRegistry.unregistered(...)} 那道自检
     * 永远为空 —— 它本来是用来发现"带了类却忘了登记"的, 而扫描把"忘没忘"这件事
     * 从存在层面消掉了。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条 —— 装配层把它汇总进启动日志, 让"这个 agent 的世界里
     *         有哪几类活动"成为一行可读的话。可重复调用: 同一个类登记两次在
     *         注册表那边是一次空操作(它记一条 DEBUG 就跳过), 所以装配层重复装配
     *         或者测试各自装配都不会炸
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(SleepActivity.class);
        registry.register(StudyActivity.class);
        registry.register(WorkActivity.class);
        registry.register(MealActivity.class);
        registry.register(CommuteActivity.class);
        registry.register(ExerciseActivity.class);
        registry.register(LeisureActivity.class);
        registry.register(SocialActivity.class);
        registry.register(HouseworkActivity.class);
        registry.register(HobbyActivity.class);
        registry.register(RestActivity.class);
        registry.register(OtherActivity.class);
        return 12;
    }

    /** 只给测试用 —— 清空到只剩内置的十二类。 */
    static synchronized void resetToDefaults(List<String> keep) {
        CREATORS.keySet().removeIf(k -> !keep.contains(k));
        unregisteredUses = 0;
    }
}
