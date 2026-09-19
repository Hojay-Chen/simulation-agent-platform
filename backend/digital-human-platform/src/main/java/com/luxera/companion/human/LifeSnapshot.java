package com.luxera.companion.human;

import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityId;
import com.luxera.companion.human.life.activity.ActivityState;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanRevision;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.1.4 —— <b>她这一天在过什么日子</b>。计划表 + 手头那件事 + 最近做过的事。
 *
 * <h2>为什么给快照, 不给 {@code Life}</h2>
 * 与 {@link BodySnapshot} 同一条理由（并发 + 方向, 见 {@link HumanContext}）。
 * 只属于 Life 的补充是: {@code Life} 上有 {@code begin} / {@code concludeCurrent} /
 * {@code interruptCurrent} / {@code replan} 这些"替她做决定"的方法 ——
 * 把 Life 交出去, 等于允许任何一个模块<b>替她开始一件事</b>,
 * 而"她决定做什么"只能有一个来源（{@code Decision}, 见 §3.4.7）。
 *
 * <h2>它为什么需要一个时刻参数</h2>
 * 因为它带着<b>进度</b>。{@code Activity.progressAt(Instant)} 与
 * {@code elapsedAt(Instant)} 都是时间的函数（她做作业的进度随分钟涨）——
 * 一个"没有时刻的进度"根本没有值。这也是 {@link #of(Life, Instant)} 比
 * {@link BodySnapshot#of(Body)} 多一个参数的原因, 而那个时刻由调用方给
 * （{@code Human.contextAt(Instant)}）, 不许在这里读墙钟。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不带 {@code Life} 的引用</b>（同 {@code BodySnapshot}: 有一个回到活对象的
 *       字段, "只读"就会在第一个补丁里失效）;</li>
 *   <li><b>不判断她过得好不好</b>。没有"今天效率分"、"计划完成率"。这里只有
 *       "表上有什么、她正在做什么、她做过什么" —— 加起来是判断的材料, 不是判断;</li>
 *   <li><b>不把 {@code Activity} 装进来</b>。见 {@link ActivityView}: 它是活动的
 *       只读投影, 不是活动本身（{@code Activity} 有 {@code conclude}, 那会改历史,
 *       而历史改不了 —— 见 {@code ActivityId} 的类注释）。</li>
 * </ul>
 *
 * @param humanId          这是谁
 * @param at               这份快照说的是哪一刻 —— 进度与耗时都是按它算的
 * @param revisionNumber   当前计划表的版本号（每重排一次 +1）
 * @param revisionId       当前版本的 id
 * @param revisionReason   这个版本<b>为什么是这样</b> —— 重排的理由, 首版是"初始计划"
 * @param revisionCreatedAt 这个版本是什么时候生成的
 * @param items            当前版本上的全部项（含已经了结的 —— 翻历史要用）
 * @param current          她手头正在做的那件事; {@code null} = 此刻手头没事
 *                         注意 {@code null} 与"计划表上下一项还没到点"是两件事,
 *                         这里表达的是"没有正在执行的活动"
 * @param activityLogTail  最近做过的几件事（最新的在最后, 长度上限 {@link #LOG_TAIL}）
 * @param activityLogSize  她一共做过多少件事（含刚才那件）—— 尾巴的条数不等于历史长度
 * @param replanAttempts   一共尝试重排过几次
 * @param replanRejections 其中被驳回（拒稿）几次
 */
public record LifeSnapshot(
        HumanId humanId,
        Instant at,
        long revisionNumber,
        String revisionId,
        String revisionReason,
        Instant revisionCreatedAt,
        List<PlanItem> items,
        ActivityView current,
        List<ActivityView> activityLogTail,
        int activityLogSize,
        long replanAttempts,
        long replanRejections) {

    /**
     * 活动日志取尾巴几条。
     *
     * <p>取尾巴而不是全量, 理由与 {@code BodySnapshot.TREND_TAIL} 不同:
     * 那里是"画不下一页", 这里是<b>日志本身没有上限</b> —— 一天几十件事、
     * 一周几百件, 全量抄进一张会被序列化、会被贴进日志的快照, 代价是线性的。
     * 而"她最近在过什么日子"这个问题, 答案只在尾巴上。
     */
    public static final int LOG_TAIL = 10;

    public LifeSnapshot {
        Objects.requireNonNull(humanId, "快照必须知道这是谁的生活");
        Objects.requireNonNull(at, "生活快照必须带时刻 —— 进度与耗时都是按它算的, "
                + "而且时刻一律由调用方传入: human/ 里不许读系统时钟");
        revisionId = revisionId == null ? "" : revisionId.trim();
        revisionReason = revisionReason == null ? "" : revisionReason.trim();
        items = items == null ? List.of() : List.copyOf(items);
        activityLogTail = activityLogTail == null ? List.of() : List.copyOf(activityLogTail);
        if (activityLogSize < 0 || replanAttempts < 0 || replanRejections < 0) {
            throw new IllegalArgumentException("快照里的计数不能为负 —— 负数的计数会让趋势图无法解释。"
                    + "收到: activityLogSize=" + activityLogSize
                    + ", replanAttempts=" + replanAttempts
                    + ", replanRejections=" + replanRejections);
        }
    }

    /**
     * <b>读一遍她的生活, 造一张快照。</b>
     *
     * <p>纯读: 不 {@code advanceTo}（那会触发到点的计划项、产生刺激 ——
     * 而"到点了"这件事只能由 §8.5.1 的 ① 处理, 不能由一次控制台刷新触发)、
     * 不 {@code begin}、不 {@code replan}。
     *
     * <p>代价要写清楚: 因此本方法<b>不是</b>一个原子读 —— 它读计划表、读活动日志、
     * 读计数器, 中间没有任何锁。真正的 actor 线程可能正好在这些读之间推进了一次心跳,
     * 于是快照里可能出现"活动日志里已经有一件刚结束的事, 而 current 还是它"
     * 这种横跨两个瞬间的组合。这是明知的: 快照是<b>给眼睛看的</b>, 它的用途是诊断,
     * 而不是拿去做判断（拿快照做判断会引入一条依赖链, 而那才是真正不能接受的）。
     * 要一份严格自洽的读, 得由调用方在 actor 的串行区里取 —— 而那条路刻意不存在。
     */
    public static LifeSnapshot of(Life source, Instant at) {
        Objects.requireNonNull(source, "不能给一个空的 Life 造快照");
        Objects.requireNonNull(at, "生活快照必须带时刻 —— 没有它就算不出她的进度");

        PlanRevision revision = source.plan().current();
        List<Activity> log = source.activityLog();

        return new LifeSnapshot(
                HumanId.of(source.humanId()),
                at,
                revision.revisionNumber(),
                revision.revisionId(),
                revision.reason(),
                revision.createdAt(),
                revision.items(),
                source.currentActivity().map(activity -> ActivityView.of(activity, at)).orElse(null),
                tailOf(log, LOG_TAIL, at),
                log.size(),
                source.replanAttempts(),
                source.replanRejections());
    }

    private static List<ActivityView> tailOf(List<Activity> log, int limit, Instant at) {
        if (log == null || log.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, log.size() - limit);
        List<ActivityView> views = new ArrayList<>();
        for (Activity activity : log.subList(from, log.size())) {
            views.add(ActivityView.of(activity, at));
        }
        return List.copyOf(views);
    }

    /** 计划表上还没了结的项 —— 控制台上"她今天还要做什么"那一栏。 */
    public List<PlanItem> livePlanItems() {
        List<PlanItem> live = new ArrayList<>();
        for (PlanItem item : items) {
            if (!item.isTerminated()) {
                live.add(item);
            }
        }
        return List.copyOf(live);
    }

    /** 她此刻手头有活吗。 */
    public boolean busy() {
        return current != null;
    }

    /**
     * 一行摘要。答的是"她这一天在过什么日子"。
     *
     * <p>与 {@code BodySnapshot.describe()} 同一条取舍: 摘要是摘要,
     * 全量在 {@link #describe(long)} 与 {@code toString()} 里。
     */
    public String describe() {
        return "LifeSnapshot[" + humanId + " @ " + at + "] 计划第 " + revisionNumber + " 版("
                + livePlanItems().size() + "/" + items.size() + " 项未了结, 重排 "
                + replanAttempts + " 次), "
                + (current == null ? "此刻手头没事" : "正做着: " + current.description()
                + " (进度 " + Math.round(current.progress() * 100) + "%)")
                + ", 一共做过 " + activityLogSize + " 件事";
    }

    /**
     * 带计划表细节的摘要 —— 控制台那一页用。
     *
     * <p>把它做成一个方法而不是让控制台自己去拼 {@code items()}: 拼法一旦分散,
     * 每一处都会长出自己的一套"什么算未了结"的规则。
     */
    public String describe(long planDetailLimit) {
        StringBuilder sb = new StringBuilder(describe());
        sb.append("\n计划表(").append(revisionId).append(": ").append(revisionReason).append(")");
        int shown = 0;
        for (PlanItem item : items) {
            if (shown++ >= planDetailLimit) {
                sb.append("\n  … 还有 ").append(items.size() - planDetailLimit).append(" 项");
                break;
            }
            sb.append("\n  ").append(item.describe());
        }
        sb.append("\n最近做过的事:");
        for (ActivityView view : activityLogTail) {
            sb.append("\n  ").append(view.describe());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * <b>一次"正在做的事"的只读投影。</b>
     *
     * <h2>为什么不直接用 {@code Activity}</h2>
     * 因为 {@code Activity} 有一个 {@code conclude(...)} —— 它是一个<b>活对象</b>的一部分,
     * 而"结束了"这件事必须按它自己的路径写进日志（{@code Life.concludeCurrent}）。
     * 一次控制台刷新拿到 Activity 之后能做的所有事里, 没有一件是它该做的。
     *
     * <h2>为什么带上进度与处境字段</h2>
     * 因为"她为什么没回这条消息"的答案常常就在这几个数上: {@code attentionDemand}
     * 高（她在专注）、{@code interruptibility} 低（她不想被打断）、
     * {@code phoneAvailability} 低（手机不在手边）—— 这三个数与
     * {@code AttentionContext} 的处境因子是同一批事实的两个出口, 但含义不同:
     * 这里的是<b>活动自己的属性</b>（她做这件事时有多投入）, 那里的是
     * <b>她此刻的处境</b>。两者都不许再打折（§3.4.4 的双罚禁令）。
     *
     * @param id                  这次执行的 id
     * @param description         这件事是什么（"写作业"）
     * @param activityType        它的种类标签（{@code PlanIntent.activityType()}）——
     *                            <b>只给人看</b>, 不许用来判断
     * @param planItemId          它是计划表上哪一项的执行; {@code null} = 没挂在计划表上
     *                            （比如"顺手回一条消息"）
     * @param startedAt           她什么时候开始做的
     * @param state               现在处在哪一档（进行中 / 已结束 / 被中断）
     * @param endedAt             什么时候结束的; {@code null} = 还没结束
     * @param closingNote         结束时的备注（"被电话打断"）; {@code null} = 没有
     * @param progress            按 {@code at} 算出来的进度 0–1
     * @param elapsed             按 {@code at} 算出来的已耗时
     * @param attentionDemand     它占掉她多少注意力
     * @param interruptibility    她做它的时候有多容易被叫走
     * @param phoneAvailability   她做它的时候手机在不在手边
     */
    public record ActivityView(
            ActivityId id,
            String description,
            String activityType,
            String planItemId,
            Instant startedAt,
            ActivityState state,
            Instant endedAt,
            String closingNote,
            double progress,
            Duration elapsed,
            double attentionDemand,
            double interruptibility,
            double phoneAvailability) {

        public ActivityView {
            Objects.requireNonNull(id, "活动的投影必须有 id —— 日志与它按 id 对齐");
            Objects.requireNonNull(state, "活动的投影必须带状态 —— "
                    + "'她还在做'与'她已经做完了'是两件不同的事");
            description = description == null ? "" : description.trim();
            activityType = activityType == null ? "" : activityType.trim();
            if (progress < 0.0 || progress > 1.0) {
                throw new IllegalArgumentException("进度必须落在 0–1: " + progress
                        + " —— 超出的部分不是「更努力」, 是一个算错的时刻");
            }
        }

        /** 从活动投影一格。<b>纯读</b>: 只问它的状态, 不给它任何东西。 */
        public static ActivityView of(Activity activity, Instant at) {
            Objects.requireNonNull(activity, "不能给一个空的活动造投影");
            return new ActivityView(
                    activity.id(),
                    activity.intent().description(),
                    activity.intent().activityType(),
                    activity.planItemId().map(planItemId -> planItemId.value()).orElse(null),
                    activity.startedAt(),
                    activity.state(),
                    activity.endedAt().orElse(null),
                    activity.closingNote().orElse(null),
                    activity.progressAt(at),
                    activity.elapsedAt(at),
                    activity.attentionDemand(),
                    activity.interruptibility(),
                    activity.phoneAvailability());
        }

        /** 还在做吗 —— 与 {@code state} 同义, 只是读起来是意图。 */
        public boolean running() {
            return state.ongoing();
        }

        public String describe() {
            return "[" + id + "] " + description + " " + state.label()
                    + " (进度 " + Math.round(progress * 100) + "%, 已 " + elapsed + ")"
                    + (endedAt == null ? "" : ", 结束于 " + endedAt)
                    + (closingNote == null ? "" : ", 备注: " + closingNote);
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
