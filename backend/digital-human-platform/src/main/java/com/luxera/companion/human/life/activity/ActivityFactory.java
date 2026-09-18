package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
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

    /** 只给测试用 —— 清空到只剩内置的十二类。 */
    static synchronized void resetToDefaults(List<String> keep) {
        CREATORS.keySet().removeIf(k -> !keep.contains(k));
        unregisteredUses = 0;
    }
}
