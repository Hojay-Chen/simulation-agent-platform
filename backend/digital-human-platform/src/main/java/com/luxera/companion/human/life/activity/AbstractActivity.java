package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §8.4 —— {@link Activity} 的骨架: 把"一次执行"的公共部分写死, 让子类只说自己的样子。
 *
 * <h2>它固定了什么, 留给子类什么</h2>
 * <table border="1">
 *   <tr><th>由本类固定</th><th>由子类（或三方实现）决定</th></tr>
 *   <tr>
 *     <td>
 *       ① 状态机（{@code RUNNING → CONCLUDED/ABANDONED}, 不可逆）<br>
 *       ② 进度的算法（已过时间 / 估计时长, 收尾后取记录值）<br>
 *       ③ <b>不可变性</b> —— 收尾返回新实例<br>
 *       ④ 收尾时的合法性检查（放弃必须说明理由）
 *     </td>
 *     <td>
 *       ① {@link ActivityProfile 档案}（多费注意力、多怕被打断、手机可不可达……）<br>
 *       ② 需要的话, 覆盖 {@link #progressAt} 或 {@link #attentionDemand}
 *          —— 一个"按当前关卡难度算注意力"的三方活动可以两者都不照默认来
 *     </td>
 *   </tr>
 * </table>
 *
 * <h2>为什么把公共部分做成抽象类, 而不是接口的 default 方法</h2>
 * 因为<b>状态迁移需要构造新实例</b>。接口的 default 方法无法凭空造出
 * "和 this 同一个具体类型、但状态不同"的对象; 它需要一个
 * {@link #create(CommonFields)} 钩子, 而那必须由子类实现。
 * 把这一处必要的样板收进一个抽象类, 好过让每一个第三方活动实现类
 * 各写一份形状略有不同的迁移逻辑 —— 那些差异迟早会变成"她有时候能收尾、
 * 有时候收不了尾"这类无法解释的行为。
 *
 * <h2>字段用 {@link CommonFields} 打包传递</h2>
 * 因为一共有八个字段, 而且每一个子类、每一次收尾都要原样传一遍。
 * 一个八参构造函数的后果是可预见的: 某一个子类会把
 * {@code startedAt} 和 {@code endedAt} 传反, 而那个错误在她重新开始时
 * 表现为一个负的时长 —— 一个只在特定路径上出现的症状。
 * 打包成一个 record 之后, 传参顺序问题不存在了。
 *
 * <h2>为什么这些类上没有 Jackson 注解</h2>
 * 因为它们<b>不是</b>直接落库的对象图。{@link Activity} 里持有
 * {@link PlanIntent} —— 一个开放接口, 由第三方实现。把"接口 + 十二个子类 +
 * 第三方子类"整个交给 Jackson 反射绑定, 会让持久化格式跟着<b>类的形状</b>走,
 * 于是重构一次字段名就要写一次数据迁移。
 *
 * <p>落库走的是另一条路: 活动被摊成一个扁平的 {@code activity_record}
 * （JSONB, 类型名由 {@link com.luxera.companion.registry.DomainType} 那个机制记录,
 * 与 {@code world_event} 完全同构）。{@code Activity} 与那份记录之间的转换
 * 由持久化层显式写出来 —— 多写一层映射的代价, 换来的是
 * "数据库里的历史不会因为代码重构而失效"。这部分随 §7 的表设计一起落地。
 */
public abstract class AbstractActivity implements Activity {

    /**
     * 一次执行的全部可变部分。
     *
     * <p>刻意做成 record: 它<b>只被整体替换, 不被逐字段修改</b>。
     * 这正是"不可变"这个约束在代码里的形状 —— 想改一个字段,
     * 唯一的方式是造一个新的 {@code CommonFields}。
     *
     * @param finalProgress 收尾时记录下的进度。未收尾时为空 ——
     *                      空与 0.0 不是一回事: 0.0 表示"她一点没做就结束了"
     */
    protected record CommonFields(
            ActivityId id,
            PlanIntent intent,
            PlanItemId planItemId,
            Instant startedAt,
            ActivityState state,
            Instant endedAt,
            String closingNote,
            Double finalProgress) {

        // 访问修饰符必须写出来, 而且必须与 record 本身的 protected 一致:
        // 省略时紧凑构造器取默认(包私有), 比 record 弱, javac 会报
        // "invalid canonical constructor ... (attempting to assign stronger access privileges; was protected)"。
        // 那句报错的方向看起来是反的, 但结论很清楚 —— 规范要求规范构造器
        // **至少**和 record 本身一样可见。
        protected CommonFields {
            Objects.requireNonNull(id, "活动 id 不能为空");
            Objects.requireNonNull(intent, "活动必须有意图 —— 没有它就没有'她在做什么'");
            Objects.requireNonNull(startedAt, "开始时刻不能为空");
            Objects.requireNonNull(state, "状态不能为空");
            if (state.terminated()) {
                Objects.requireNonNull(endedAt, "已结束的执行必须有结束时刻");
                if (endedAt.isBefore(startedAt)) {
                    throw new IllegalArgumentException(
                            "结束时刻 " + endedAt + " 早于开始时刻 " + startedAt
                                    + " —— 一个负时长的执行会让所有基于它的统计变成噪声");
                }
                if (state.requiresReason()
                        && (closingNote == null || closingNote.isBlank())) {
                    throw new IllegalArgumentException(
                            "放弃一件事必须说明理由 —— '她为什么放弃'是这个平台上最有价值的行为数据之一");
                }
            } else if (endedAt != null) {
                throw new IllegalArgumentException(
                        "还在进行的执行不该有结束时刻: " + endedAt);
            }
        }

        /** 她刚开始做这件事。 */
        static CommonFields start(PlanIntent intent, Instant at, PlanItemId planItemId) {
            return new CommonFields(ActivityId.generate(), intent, planItemId, at,
                    ActivityState.RUNNING, null, null, null);
        }

        /** 从数据库读回来（指定 id）。 */
        static CommonFields restore(ActivityId id, PlanIntent intent, PlanItemId planItemId,
                                   Instant startedAt, ActivityState state, Instant endedAt,
                                   String closingNote, Double finalProgress) {
            return new CommonFields(id, intent, planItemId, startedAt, state,
                    endedAt, closingNote, finalProgress);
        }

        CommonFields concluding(Instant at, ActivityState target, String note, double progress) {
            return new CommonFields(id, intent, planItemId, startedAt, target, at,
                    note, progress);
        }
    }

    private final CommonFields fields;

    protected AbstractActivity(CommonFields fields) {
        this.fields = Objects.requireNonNull(fields, "公共字段不能为空");
    }

    /** 构造一个"她刚开始做"的实例 —— 给子类的便捷构造函数用。 */
    protected AbstractActivity(PlanIntent intent, Instant at, PlanItemId planItemId) {
        this(CommonFields.start(intent, at, planItemId));
    }

    // ─────────────────────────── 子类必须说的两件事 ───────────────────────────

    /**
     * 这类活动的档案 —— 多费注意力、多怕被打断、手机可不可达。
     *
     * <p>做成方法而不是常量, 是为了给"按当下情况算"留出空间:
     * 一个"按当前关卡难度算注意力"的活动完全可以返回动态值。
     */
    protected abstract ActivityProfile activityProfile();

    /**
     * 造一个和 {@code this} 同一个具体类型、但公共字段换成 {@code next} 的新实例。
     *
     * <p>它就是"状态迁移需要的那一处样板"。每个实现类的实现都是一行
     * {@code return new XxxActivity(next);} —— 枯燥, 但正是这一行保证了
     * {@code SleepActivity} 收尾之后还是 {@code SleepActivity},
     * 而不是退化成某个通用类型。
     *
     * <p>为什么这件事重要: 类型是它进 JSONB 时的 {@code _type} 字段
     * （见 {@code PolymorphicSerializer}）。类型退化会让"她今天睡了几个小时"
     * 这个统计在数据里<b>静默地少掉一部分</b> —— 因为那些记录的类型对不上了。
     */
    protected abstract AbstractActivity create(CommonFields next);

    /** 供子类在需要注册类型名时读取自己的公共字段（例如实现 {@code @DomainType} 的辅助构造）。 */
    protected final CommonFields commonFields() {
        return fields;
    }

    // ─────────────────────────── Activity ───────────────────────────

    @Override
    public final ActivityId id() {
        return fields.id();
    }

    @Override
    public final PlanIntent intent() {
        return fields.intent();
    }

    @Override
    public final Optional<PlanItemId> planItemId() {
        return Optional.ofNullable(fields.planItemId());
    }

    @Override
    public final Instant startedAt() {
        return fields.startedAt();
    }

    @Override
    public final ActivityState state() {
        return fields.state();
    }

    @Override
    public final Optional<Instant> endedAt() {
        return Optional.ofNullable(fields.endedAt());
    }

    @Override
    public final Optional<String> closingNote() {
        return Optional.ofNullable(fields.closingNote());
    }

    @Override
    public double attentionDemand() {
        return activityProfile().attentionDemand();
    }

    @Override
    public double interruptibility() {
        return activityProfile().interruptibility();
    }

    @Override
    public double phoneAvailability() {
        return activityProfile().phoneAvailability();
    }

    @Override
    public double moodEffect() {
        return activityProfile().moodEffect();
    }

    // ─────────────────────────── 进度 ───────────────────────────

    /**
     * 她做到几成了。
     *
     * <p>已收尾的执行返回<b>当时记下的那个数</b>（{@code finalProgress}）——
     * 而不是按"已过时间 / 估计时长"重算。
     *
     * <p>这两个数会不一样, 而差别有意义: 一项估 60 分钟的事她做了 15 分钟就被叫走,
     * 按时间算的进度是 0.25。但如果那 15 分钟里她其实把最难的部分做完了,
     * 记下的进度可能是 0.6。前者是<b>推测</b>, 后者是<b>记录</b> ——
     * 收尾之后就该用记录, 否则她"实际做到几成"这个事实会被一个公式覆盖掉。
     */
    @Override
    public double progressAt(Instant at) {
        if (fields.state().terminated()) {
            Double recorded = fields.finalProgress();
            if (recorded != null) {
                return clamp01(recorded);
            }
        }
        Duration estimated = estimatedDuration();
        if (estimated.isZero() || estimated.isNegative()) {
            return 0.0;
        }
        return clamp01((double) elapsedAt(at).toMillis() / (double) estimated.toMillis());
    }

    protected static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    // ─────────────────────────── 收尾 ───────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>本类的实现把三件事挡在门外（它们都会在构造 {@code CommonFields} 时抛异常）:
     * 从已结束的状态再迁移一次、结束时刻早于开始时刻、放弃而不给理由。
     *
     * <p>进度取<b>收尾那一刻的值</b>, 并连同结束时刻一起凝固下来 ——
     * 此后无论谁在什么时刻问 {@code progressAt}, 答案都一样。
     * 这是"已发生的执行是历史"这句话在代码里的落点。
     */
    @Override
    public final Activity conclude(Instant at, ActivityState target, String note) {
        Objects.requireNonNull(at, "收尾必须带时刻");
        Objects.requireNonNull(target, "收尾必须说明结局");
        if (!fields.state().canTransitionTo(target)) {
            throw new IllegalStateException(
                    "不能从 " + fields.state() + " 迁移到 " + target
                            + " —— 已结束的执行不能重新开始。"
                            + "她要继续做这件事, 应当排一条新的计划项, 由此产生一条<新的>执行");
        }
        double progress = progressAt(at);
        return create(fields.concluding(at, target, note, progress));
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        return Activity.super.describe() + " (" + activityProfile().describe() + ")";
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id().value() + " "
                + intent().description() + " " + state().label() + "]";
    }
}
