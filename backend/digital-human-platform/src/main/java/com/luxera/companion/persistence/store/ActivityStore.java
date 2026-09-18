package com.luxera.companion.persistence.store;

import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.activity.ActivityId;
import com.luxera.companion.human.life.activity.ActivitySnapshot;
import com.luxera.companion.human.life.activity.ActivityState;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.ActivityRecord;
import com.luxera.companion.persistence.repository.ActivityRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code activity_record} 的读写路径:
 * <b>{@code AbstractActivity} 的 javadoc 承诺的那个"扁平形态"的落点</b>。
 *
 * <h2>这张表要保存的是什么（一句话）</h2>
 * "她重启之前正在做什么, 做到哪一步了"。不存它, 进程重启后她<b>手上是空的</b> ——
 * 而那与"她刚好做完了"在数据上完全一样, 于是没有人能回答
 * "她昨天下午的那两个小时去哪了"。
 *
 * <h2>为什么 {@code activity_type} 与 {@code intent_type} 是<b>两套</b>三元组</h2>
 * 这是本表最容易被误读的一处。它们看起来是同一个东西的两份拷贝, 其实不是:
 * <pre>
 *   intent_type  = 她当时<b>想</b>做的那一类   （life.activity.gaming）
 *   activity_type = 她<b>实际</b>做的那一类   （life.activity.other）
 * </pre>
 * 两者不同不是数据损坏, 而是<b>一条真实发生过的事实</b>: 三方应用没注册,
 * 于是 {@code ActivityFactory} 把她落到了 {@code OtherActivity}
 * （见 {@code ActivityFactory.start} 的"未注册的类型名怎么办"）。
 *
 * <p>只存一套的后果非常具体: 恢复时必须二选一。按 {@code intent} 找类,
 * 那一次"她本来要做的事没做成"的记录会在重启后<b>消失</b> ——
 * 她会突然开始打游戏, 而库里没有任何东西解释这个转变;
 * 按 {@code activity_type} 找类, 那她在玩游戏时的注意力/疲劳曲线会被
 * 换成 {@code OtherActivity} 的中庸档案, 而她的行为会因此变样。
 * 两套都存就没有这个问题: {@link #restore} 按 {@code activity_type} 找类,
 * 而 {@code intent} 原样灌回去。
 *
 * <h2>哪些是 UPDATE, 哪些不是</h2>
 * <table border="1">
 *   <tr><th>动作</th><th>SQL</th><th>为什么允许</th></tr>
 *   <tr><td>开始一件事</td><td>INSERT</td><td>——</td></tr>
 *   <tr><td>结束（{@link #conclude}）</td>
 *       <td>UPDATE {@code state} / {@code ended_at} / {@code closing_note} / {@code final_progress}</td>
 *       <td>这是<b>同一次执行的同一个事实</b>从"进行中"变成"已结束"。
 *           写成两行的代价是: 每一处"她现在在做什么"的查询都要先按 id 去重再取最新,
 *           而漏掉一次去重的表现是"她已经结束的那件事还在做"</td></tr>
 *   <tr><td>删除</td><td><b>没有</b></td>
 *       <td>她做过的事不会因为她忘了而没发生过。这也是{@code ActivityRecordRepository}
 *           不声明任何删除方法的原因</td></tr>
 * </table>
 *
 * <h2>为什么恢复必须走 {@link ActivityFactory#restore}</h2>
 * 本类自己 {@code new} 一个活动<b>做不到</b>, 而且这限制是刻意的:
 * {@code AbstractActivity.CommonFields} 是 {@code protected} 的,
 * {@code create} 是 {@code protected abstract} 的 —— 持久化层物理上看不见它们。
 * 于是本类只做"行 ⇄ {@link ActivitySnapshot}"这一半, 把"快照 ⇄ 活动"
 * 交给领域自己的工厂（那是唯一知道 {@code ActivityId} 该怎么复用、
 * 状态该怎么灌回去的地方）。这一分工让"活动内部形状"这件事
 * 不需要在持久化层被复述一遍。
 */
@Slf4j
public class ActivityStore {

    private final ActivityRecordRepository repository;
    private final DomainPayloadCodec codec;

    public ActivityStore(ActivityRecordRepository repository, DomainPayloadCodec codec) {
        this.repository = Objects.requireNonNull(repository, "执行记录仓库不能为空");
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 她开始做这件事了。
     *
     * <p>活动类型三元组取自<b>实现类</b>上的 {@code @DomainType}
     * （{@code codec.write(activity)} 就是干这个的）, 而意图三元组取自
     * {@code activity.intent()}。两者由两个不同的对象各自决定 —— 这正是
     * 类注释里那张表要保住的信息。
     */
    public ActivityRecord append(String humanId, Activity activity) {
        Objects.requireNonNull(humanId, "执行必须属于某个人 —— 没有人的执行查不出来");
        Objects.requireNonNull(activity, "要落库的执行不能为空");
        Objects.requireNonNull(activity.state(), "执行必须有状态 —— 这是恢复时'她还在做吗'的唯一依据");

        DomainPayloadCodec.PersistedForm activityType = codec.write(activity);
        DomainPayloadCodec.PersistedForm intentType = codec.write(activity.intent());

        ActivityRecord record = new ActivityRecord();
        record.setId(activity.id().value());
        record.setHumanId(humanId);
        record.setActivityTypeNamespace(activityType.typeNamespace());
        record.setActivityTypeName(activityType.typeName());
        record.setActivityTypeVersion(activityType.majorVersion());
        record.setIntentTypeNamespace(intentType.typeNamespace());
        record.setIntentTypeName(intentType.typeName());
        record.setIntentTypeVersion(intentType.majorVersion());
        record.setIntentJson(intentType.payload());
        record.setPlanItemId(activity.planItemId().map(PlanItemId::value).orElse(null));
        record.setStartedAt(activity.startedAt());
        record.setState(activity.state().name());
        record.setEndedAt(activity.endedAt().orElse(null));
        record.setClosingNote(activity.closingNote().orElse(null));
        record.setFinalProgress(finalProgressOf(activity));
        return repository.save(record);
    }

    /**
     * 这件事结束了 —— 把"进行中"那一行改写成它的终局。
     *
     * <p>接受一行而不是一个 id: 调用方（收尾那一侧）手上本来就有这一行,
     * 而多一次"按 id 再查一遍"会在收尾与查询之间留一个窗口 ——
     * 万一那期间这一行被别的路径改过, 后写的会覆盖先写的,
     * 而覆盖的方向是<b>丢掉一次收尾</b>（她又回到"正在做"）。
     *
     * @param running  从 {@link #append} 或 {@link #runningRow} 拿到的那一行
     * @param concluded 已经收尾的活动（{@code concluded.state()} 必须是终态）
     * @throws IllegalArgumentException 传入的活动还不是终态
     */
    public ActivityRecord conclude(ActivityRecord running, Activity concluded) {
        Objects.requireNonNull(running, "要收尾的那一行不能为空");
        Objects.requireNonNull(concluded, "收尾后的活动不能为空");
        if (concluded.state().ongoing()) {
            throw new IllegalArgumentException(
                    "活动 " + concluded.id().value() + " 的状态是 " + concluded.state()
                            + ", 它不是终态 —— 本方法只做'收尾'这一件事。"
                            + "允许它写一个进行中的状态会让'她还在做吗'这个问题"
                            + "在库里出现两个互相矛盾的答案");
        }

        running.setState(concluded.state().name());
        running.setEndedAt(concluded.endedAt().orElse(null));
        running.setClosingNote(concluded.closingNote().orElse(null));
        running.setFinalProgress(finalProgressOf(concluded));
        return repository.save(running);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 重启后的第一问: <b>她现在是不是正在做某件事</b>。
     *
     * <p>取最近开始的那一条（{@code findFirstBy...OrderByStartedAtDesc}）——
     * 理论上同一个 agent 的 {@code RUNNING} 行最多一条（她一次只能做一件事）,
     * 但本表<b>没有</b>唯一约束（本仓惯例: 唯一约束只在"重复会静默破坏语义"时加,
     * 而这个场景重复的后果是"有两件正在做的事", 那会在应用层被发现）。
     * 用"取最近一条"而不是 {@code Optional} 抛异常, 是为了让一处历史脏数据
     * 不至于让她<b>起不来</b> —— 详见类注释末段对该权衡的说明。
     */
    public Optional<Activity> runningActivity(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人 —— 本层不做'当前 agent'这种隐式假设");
        return repository
                .findFirstByHumanIdAndStateOrderByStartedAtDesc(humanId, ActivityState.RUNNING.name())
                .map(this::restore);
    }

    /**
     * 一行 → 一个真正的 {@code Activity}。
     *
     * <h2>为什么这里用<b>会抛</b>的读法, 而 {@code WorldEventStore} 用宽容读</h2>
     * 因为这两个场景里"读不出来"的含义完全不同（{@code DomainPayloadCodec.tryRead}
     * 的对照表把这件事写全了）:
     * <pre>
     *   事件读不出来 → 某条历史少了一笔, 分析结果偏一点 → 记一条 WARN 继续
     *   活动读不出来 → 她带着<b>半个自己</b>继续活: 她不知道自己在干什么,
     *                  却仍然占着"正在做某件事"这个状态 → 必须炸
     * </pre>
     * 一个状态不全的 agent 比一个起不来的 agent 难查得多 —— 因为它的表现
     * 是"她今天有点奇怪", 而没有人会去查一个"有点奇怪"的日志。
     */
    public Activity restore(ActivityRecord record) {
        Objects.requireNonNull(record, "要恢复的行不能为空");

        PlanIntent intent = codec.read(record.getIntentTypeNamespace(), record.getIntentTypeName(),
                record.getIntentTypeVersion(), record.getIntentJson(), PlanIntent.class, true);

        ActivitySnapshot snapshot = new ActivitySnapshot(
                ActivityId.parse(record.getId()),
                intent,
                record.getPlanItemId() == null ? null : PlanItemId.of(record.getPlanItemId()),
                record.getStartedAt(),
                parseState(record),
                record.getEndedAt(),
                record.getClosingNote(),
                record.getFinalProgress());

        return ActivityFactory.restore(activityTypeOf(record), snapshot);
    }

    /** 她这一段时间做了什么 —— 时间轴与行为分析。走 {@code idx_activity_human_time}。 */
    public List<ActivityRecord> timeline(String humanId, Instant from, Instant to) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        Objects.requireNonNull(from, "起始时刻不能为空 —— 本层不读时钟, 所以区间必须由调用方给");
        Objects.requireNonNull(to, "结束时刻不能为空 —— 同上");
        return repository.findByHumanIdAndStartedAtBetweenOrderByStartedAtAsc(humanId, from, to);
    }

    /**
     * 这一项计划实际被执行过几次（正序）。
     *
     * <p>「计划 vs 实际」这一问的落点: 计划表说"下午写作业",
     * 而这里能看到她实际做了三次、每次二十分钟 —— 那是完全不同的一个下午。
     */
    public List<ActivityRecord> executionsOf(String planItemId) {
        Objects.requireNonNull(planItemId, "计划项 id 不能为空");
        return repository.findByPlanItemIdOrderByStartedAtAsc(planItemId);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 从一行里取 {@code activity_type} 的前两段, 拼成 {@code ActivityFactory} 的<b>注册键</b>。
     *
     * <h2>为什么<b>不</b>带版本段 —— 这里曾经是一个 bug, 值得完整写下来</h2>
     * 曾经这里拼的是 {@code namespace + "." + name + ".v" + version}
     * （也就是 {@code EventTypeId.toString()} 的形状）, 而
     * {@code ActivityFactory.CREATORS} 的键是 {@code @DomainType} 的<b>原值</b>:
     * <pre>
     *   @DomainType(value = "life.activity.study")   → 工厂的键是 "life.activity.study"
     *   三个列 (life.activity, study, 1)              → 曾经拼出 "life.activity.study.v1"
     * </pre>
     * 两者永远不相等, 于是 {@code ActivityFactory.restore} 对<b>每一行</b>都
     * 落到"没有注册任何实现"的分支 —— 而它的降级对象是 {@code OtherActivity}
     * （中庸档案）。后果不是一条报错, 而是:
     * <pre>
     *   她重启之前 "写作业"（高专注、低可打断）
     *   她重启之后 仍然是"写作业"这件事, 但档案换成了中庸的 —— 注意力曲线、
     *              疲劳累积、可打断性全部变了样
     * </pre>
     * 也就是说, <b>每一次重启都会悄悄改变她的行为</b>, 而库里每一列看起来都是对的。
     * 更糟的是它会让 {@code unregisteredUses} 这个"有多少三方忘了注册"的信号失效:
     * 它会把每一次正常重启都算进去, 于是真正的漏注册被淹没在噪声里。
     *
     * <h2>为什么版本列<b>不</b>参与查表</h2>
     * 因为 {@code ActivityFactory} 是<b>无版本</b>的（{@code register("life.activity.study", ...)}
     * 的契约就是"这个类负责这一类活动"）, 而 {@code ActivityRecord} 的类注释与
     * {@code ActivityFactory} 的类注释都用不带版本的形式称呼这一列
     * （{@code life.activity.sleep}）。两处共识指向同一个答案: 版本是<b>关系型元数据</b>
     * （它记下这一行是哪个版本写出来的, 供审计与将来的迁移判据用）, 而<b>类的身份</b>
     * 是那个 {@code @DomainType} 原值。
     *
     * <p>代价要说清楚: 一个活动类型升到 v2 时, 库里的 v1 行与 v2 行会查到<b>同一个</b>
     * 实现类。这是刻意的 —— 活动类型的版本升格若改变了字段含义, 那属于
     * {@code CommonFields} 或意图的事, 该由意图那一侧的多态机制处理; 而"哪一类活动"
     * 这个身份在重启前后必须稳定, 否则恢复出来的类会随版本而变。
     *
     * <p>另外, 这里仍然是<b>显式拼字符串</b>而不是构造一个 {@code EventTypeId}:
     * 我们只需要一个查表用的键, 不需要一次校验 —— 一次"版本号写坏了"的校验失败
     * 在这里能做的只有抛异常, 把一次降级变成一次崩溃。
     */
    public static String activityTypeOf(ActivityRecord record) {
        return record.getActivityTypeNamespace() + "." + record.getActivityTypeName();
    }

    /**
     * 三个列拼成 {@code EventTypeId} 的完整形状（{@code namespace.name.vN}）——
     * <b>只给审计与诊断用, 绝不能拿去查 {@code ActivityFactory}</b>。
     *
     * <p>它存在的理由是把上一条说明里的那个区别变成两个<b>并排的方法</b>:
     * <pre>
     *   activityTypeOf(row)  →  "life.activity.study"     查表用（工厂的键）
     *   typeIdOf(row)        →  "life.activity.study.v1"  入库/日志/审计用
     * </pre>
     * 只有一个方法时, 那个"要不要带版本"的判断会被下一个人重新做一遍 ——
     * 而他手上多半没有"拼错了也不报错, 只是把她的档案悄悄换成中庸的那一份"
     * 这条线索。两个方法并排、各自写明用途, 这件事就没有第二次机会被做错。
     */
    public static String typeIdOf(ActivityRecord record) {
        return activityTypeOf(record) + ".v" + record.getActivityTypeVersion();
    }

    /**
     * 状态字符串 → 枚举, <b>认不出来时不降级</b>。
     *
     * <p>不降级的理由与 {@code ActivityRecord.getState()} 的说明一致: 状态在本表里
     * 是控制流（"删掉一个状态常量会让历史行读不回来"）, 而不是展示用的标签。
     * 一个读不出来的状态意味着"她当时到底做完了没有"这个问题无法回答 ——
     * 而那正是这张表存在的全部理由。
     */
    private static ActivityState parseState(ActivityRecord record) {
        try {
            return ActivityState.valueOf(record.getState());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "执行 " + record.getId() + " 的状态 \"" + record.getState()
                            + "\" 不是当前代码认识的取值（已知: "
                            + java.util.Arrays.toString(ActivityState.values()) + "）。"
                            + "这不是可以跳过的历史脏数据: 它是'她当时做完了没有'的唯一依据",
                    e);
        }
    }

    /**
     * 收尾进度 —— {@code null} 与 {@code 0.0} 必须能被区分。
     *
     * <p>{@code Activity} 只在收尾时才有进度（{@code progressAt(at)} 是对进行中的
     * 活动算出来的, 不是一个"已存下来的数"）。所以进行中的活动这一列是
     * {@code null}, 而"她结束了但一点没做"是 {@code 0.0} ——
     * 这两件事在行为分析里是两段完全不同的故事, 压成同一个值就再也分不开了。
     */
    private static Double finalProgressOf(Activity activity) {
        if (activity.state().ongoing()) {
            return null;
        }
        return activity.progressAt(activity.endedAt().orElse(activity.startedAt()));
    }

    /** 诊断: 这一行是谁在什么时候做的什么。 */
    public static String describe(ActivityRecord record) {
        Objects.requireNonNull(record, "要描述的行不能为空");
        // 用带版本的那个: 日志要回答的是"这一行是哪个版本写的",
        // 而查表那件事不需要出现在一行给人看的文字里
        return record.getHumanId() + " " + typeIdOf(record) + " [" + record.getId() + "] "
                + record.getState() + " @ " + record.getStartedAt()
                + (record.getEndedAt() == null ? "" : "→" + record.getEndedAt());
    }

    /** 诊断: 一次恢复里读不回来的那些行 —— 只统计, 不解析。 */
    public List<String> describeAll(List<ActivityRecord> records) {
        List<String> out = new ArrayList<>(records.size());
        for (ActivityRecord record : records) {
            out.add(describe(record));
        }
        return out;
    }
}
