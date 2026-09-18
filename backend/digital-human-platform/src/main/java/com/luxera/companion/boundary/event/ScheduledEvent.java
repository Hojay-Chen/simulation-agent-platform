package com.luxera.companion.boundary.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * V2.2 §5.2 —— <b>C 类: 安排事件</b>。它说的是"世界在某段时间里为你留了一件事",
 * 而要不要做、什么时候做, <b>是她的决定</b>。
 *
 * <h2>把"世界陈述事实"这条规则守住</h2>
 * 本接口最容易越界。一个叫"该写作业了"的事件已经越界了 —— 那是意见。
 * 合法的形状是: "日历上 12:00-13:00 有一项叫'写作业'的安排"。这两句话的差别在于
 * <b>谁拥有那个决定</b>: 前者世界替她决定了, 后者世界只是告诉了她日历上有什么。
 *
 * <p>现实里这个区分是清楚的: 日程表 App 提醒你"14:00 有会", 但你完全可以不去。
 * 事件是那声提醒, 不是那个"必须去"。
 *
 * <h2>为什么这一类不能用队列</h2>
 * 用户对这一点有明确的直觉:
 * <blockquote>
 *   "至于这几个 event 你要用几个队列存储, 甚至于像计划表这种可能用队列还不好实现,
 *    你得想想什么数据结构实现能很好满足要求"
 * </blockquote>
 *
 * <p>他是对的, 而且理由比"不好实现"更硬:
 * <table border="1">
 *   <tr><th>需求</th><th>队列能做吗</th><th>{@code PlanBoard} 怎么做</th></tr>
 *   <tr>
 *     <td>"12:00 到了要做写作业"</td><td>能</td><td>{@code start_at} 索引扫描</td>
 *   </tr>
 *   <tr>
 *     <td>"把写作业改到 12:25 开始"</td>
 *     <td><b>不能</b>。队列只能从头取, 改中间一项要先全倒出来再灌回去</td>
 *     <td>改一个 {@code PlanItem} 的窗口, 重新索引 —— O(log n)</td>
 *   </tr>
 *   <tr>
 *     <td>"把写作业整个删掉, 换成去运动"</td>
 *     <td><b>不能</b>。队列不支持随机删除</td>
 *     <td>一次 {@code REPLACE} 变更, 产出一个新 Revision</td>
 *   </tr>
 *   <tr>
 *     <td>"这两件事有先后依赖, 后一件必须等前一件结束"</td>
 *     <td><b>不能</b>。队列没有约束概念</td>
 *     <td>{@code PlanConstraint} 表达依赖, 重排时一并求解</td>
 *   </tr>
 *   <tr>
 *     <td>"我想看看上周三的计划长什么样"</td>
 *     <td><b>不能</b>。消费掉的就没了</td>
 *     <td>{@code PlanRevision} 链, 永不删除</td>
 *   </tr>
 * </table>
 *
 * <p>最后一行是关键: 用户要求"打断当前正在做的计划 event...他是<b>真的改变了计划表</b>,
 * 让 agent 重新思考重排计划表"。注意"改变计划表"这个动作 —— 一个只能从头部取的结构
 * 是<b>改不了</b>的。所以 C 类的归宿必须是 {@code PlanBoard}(版本化时间线 + 约束图),
 * 不是 {@link RealtimeEventQueue}。
 *
 * <h2>时间窗口, 不是时刻</h2>
 * 用户明确说了"而且是时间段类型的, 比如有一个写作业的 event, 安排在 12:00 持续 1 小时"。
 * 所以 {@link #windowStart()} 与 {@link #windowEnd()} 是<b>成对</b>的, 不是一个 time point。
 * 这直接影响 {@code PlanBoard} 的数据结构选型: 它需要的是一个
 * {@code NavigableMap<Instant, List<PlanItem>>} 按开始时间索引, 而不是一个
 * {@code PriorityQueue}(优先级队列只能回答"下一个是什么", 回答不了"12:15 这一刻有哪些事"<
 * —— 而后者正是打断发生时必须问的问题)。
 */
public interface ScheduledEvent extends WorldEvent {

    /**
     * 这件事在世界时间轴上<b>被安排</b>从哪一刻开始。
     *
     * <p>与 {@link WorldEvent#occurredAt()} 的差别: {@code occurredAt} 是"这条事件
     * 什么时候被产生出来的"(可能是 11:00, 日历同步的时刻), 而本方法是"这件事被安排在
     * 什么时候"(12:00)。两者几乎从不相等, 混用会让重放得到错误的时间线。
     */
    Instant windowStart();

    /**
     * 这件事被安排到哪一刻结束。<b>必须晚于</b> {@link #windowStart()}。
     *
     * <p>没有"开放式结束"这种取值 —— 一个不知道何时结束的安排无法参与重排时的
     * 冲突检测(她没法知道"加一件事会不会撞上它")。若真有说不准时长的事, 给一个
     * <b>她预期的</b>时长, 并在以后用 {@code RESIZE} 变更修正; 这比留空更诚实,
     * 因为空值会静默地让所有冲突检测失效。
     */
    Instant windowEnd();

    /**
     * 这是件什么事 —— <b>意图类型标识</b>, 不是标题文本。
     *
     * <p>用户说"账号 Id 对应的是谁, 这是 agent 自己聊天的时候需要构建的关系网",
     * 同样的道理适用于这里: "12:00 那一小时里该做什么"是<b>她的知识</b>。
     * 世界只提供"日历上有一项安排", 那一项是"写作业"还是"复习"由她解释。
     *
     * <p>所以本方法返回的是一个意图类型({@code EventTypeId}, 如
     * {@code life.write-homework.v1}), 不是给人看的字符串。标题文本如果需要,
     * 由 {@code PlanItem} 在入板时生成 —— 那是 Human 侧的事。
     *
     * <h3>但这里有一个现实约束</h3>
     * 日历是<b>外部系统</b>给的, 它只会给一个标题字符串。所以从外部日历来的
     * {@code ScheduledEvent} 实现类, 会在自己的载荷里带一个原始标题, 并由一个
     * Human 侧的 {@code CalendarIntentionInterpreter} 把它映射成意图类型。
     * <b>映射不在本接口里做</b> —— 那会让世界侧需要知道 Human 的意图词汇表。
     */
    EventTypeId intentType();

    /**
     * 这件事有多可被打断 —— {@code [0, 1]}, 越高越可以随时放下。
     *
     * <p>与 {@link SensoryEvent#urgency()} 一样, 这<b>不是</b>"她有多重视它"。
     * 一个能被任意打断的属性来自事情本身: 看视频可以暂停(0.9), 做手术不能(0.05)。
     * 至于"她今天特别想写完作业", 那是 {@code PlanItem.priority}, 在入板时由她定。
     *
     * <p>默认 {@code 0.5}。刻意给一个中间值而不是 0 或 1: 拿不准时给中间,
     * 让重排算法有空间, 而不是让一个瞎猜的极端值把整张表钉死。
     */
    default double interruptibility() {
        return 0.5;
    }

    /**
     * 这件事是不是<b>必须</b>发生(没有选择余地)。
     *
     * <p>绝大多数安排不是: 会议可以缺席, 作业可以不写。但"8:00 的航班"是 ——
     * 那个时间不由她定。这个标记让 {@code PlanReplanner} 知道哪些项在重排时
     * <b>不能被移动</b>, 只能被绕过。
     *
     * <p>默认 {@code false}。把它默认设成 {@code true} 会让计划表变成一块铁板,
     * 而"计划赶不上变化"恰恰是这个系统要模拟的东西。
     */
    default boolean fixed() {
        return false;
    }

    /**
     * 这件事的来源(谁的日历 / 谁邀请的), 用于她去核对"这是谁安排的"。
     *
     * <p>可以返回空 —— 系统自己产生的定时安排没有外部来源。
     */
    default Optional<String> originator() {
        return Optional.empty();
    }

    /** 安排窗口的长度。派生量, 但值得有一个明确的名字。 */
    default Duration windowLength() {
        return Duration.between(windowStart(), windowEnd());
    }

    /**
     * 校验时间窗口是否自洽。
     *
     * <p>做成 default 方法而不是进构造函数, 是因为实现类多半是 record,
     * 而 record 的紧凑构造器里调不了 default 方法以外的共用逻辑 —— 让实现类
     * 在自己的构造器里调 {@code validateWindow()} 是成本最低的一致性保证。
     * {@code PlanBoard} 在入板时<b>也</b>会调一次(不信任调用方)。
     */
    default void validateWindow() {
        if (windowStart() == null || windowEnd() == null) {
            throw new IllegalArgumentException(
                    "安排事件必须有完整的时间窗口 —— 没有窗口的安排无法参与冲突检测 (" + typeId() + ")");
        }
        if (!windowEnd().isAfter(windowStart())) {
            throw new IllegalArgumentException(
                    "安排事件的结束时刻必须晚于开始时刻: " + windowStart() + " → " + windowEnd()
                            + " (" + typeId() + ")。零长度或倒转的窗口会让 PlanBoard 的索引扫出错误结果");
        }
    }

    /** 便于日志与调试: 这条安排的一行摘要。 */
    default String scheduleDescribe() {
        return typeId() + " [" + windowStart() + " → " + windowEnd() + "]"
                + " intent=" + intentType()
                + (fixed() ? " FIXED" : "");
    }
}
