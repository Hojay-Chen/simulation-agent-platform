package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * V2.2 §8.4 —— <b>她"正在做的一件事"</b>: 睡觉、写作业、开会、通勤、吃饭、发呆。
 *
 * <h2>它取代了什么</h2>
 * 旧实现里这是一张表 + 一个 12 值的字符串:
 * <pre>{@code
 * @Column(nullable = false, length = 32)
 * private String type;   // SLEEP/WORK/STUDY/MEAL/COMMUTE/EXERCISE/LEISURE/
 *                        // SOCIAL/HOUSEWORK/HOBBY/REST/OTHER
 * }</pre>
 *
 * <p>设计文档 §8.3 把这条列成了必须改掉的一项, 理由是它与本设计的核心原则冲突:
 * <b>第三方接入一个"打游戏"或"上网课"的活动时, 不该需要改平台源码</b>。
 * 而一个 {@code String type} 的字段会让那件事变成"要么改 CHECK 约束, 要么塞进 OTHER"。
 *
 * <h2>它与 {@code PlanItem} 的分工 —— 这是本接口最要紧的一段</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code PlanItem}（计划项）</th><th>{@code Activity}（活动）</th></tr>
 *   <tr><td>它是什么</td><td><b>意图</b>: "我打算 12:25 到 13:10 写作业"</td>
 *       <td><b>行为</b>: "她真的在写, 已经写了 15 分钟"</td></tr>
 *   <tr><td>住在哪</td><td>{@code human/life/plan/} 的计划表里, 有版本链</td>
 *       <td>{@code human/life/activity/}, 是"当下"的一件事, <b>没有版本</b></td></tr>
 *   <tr><td>能改吗</td><td>能 —— 重排产生新版本</td>
 *       <td><b>不能</b> —— 已经发生过的执行是历史。她能改的只有"接下来做什么"</td></tr>
 *   <tr><td>数量</td><td>一天里很多项, 未来和过去都有</td>
 *       <td>任何时刻最多一个在 {@link ActivityState#RUNNING}（身体只有一个）</td></tr>
 *   <tr><td>谁创建</td><td>规划器 / LLM / 用户</td>
 *       <td>由 {@code Life} 在某一项真正开始时创建</td></tr>
 * </table>
 *
 * <p>一句话: <b>计划项是"她要做什么", 活动是"她正在做什么"。</b>
 * 把所有活动都排进计划表、并且执行时直接改计划项的状态, 是一个看起来很省事的做法,
 * 但它会立刻撞上一个问题: 计划项是会被重排的（旧项 {@code SUPERSEDED}、换成新 id）,
 * 而"她做到一半的这件事"不该在重排时凭空换 id —— 那会让她正在做的事
 * 在数据里变成另一件事。分成两个概念之后, 这个问题不存在。
 *
 * <h2>被打断时发生什么 —— 请务必读完这一段再实现</h2>
 * 用户对打断的要求是明确的: <b>不是更新剩余时间然后继续, 而是真的改变计划表</b>。
 * 落到本接口上就是:
 *
 * <pre>
 *   12:15 觉得冷
 *     ① 这一次 {@code Activity} 收尾: {@code conclude(12:15, CONCLUDED, "觉得冷")}
 *        —— 它的 {@link #progressAt(Instant)} 停在 0.25 左右, 被记进事件
 *     ② 计划表产生新版本: 插入穿衣、把写作业挪到 12:25（或者干脆删掉它）
 *     ③ 12:25 若她真的又开始写, 那是<b>一条新的 Activity</b>（新的 {@link ActivityId}）
 * </pre>
 *
 * <p><b>所以本接口里没有"剩余时长", 也没有"暂停"。</b>理由不是洁癖,
 * 而是一个具体的后果: 一旦有"剩余时长", 它就与计划表成了<b>两个真相源</b> ——
 * 而重排恰恰会改掉计划表里那一项的窗口。两者不一致时, 没有人能回答
 * "她到底还剩多久"。见 {@link ActivityState} 关于"刻意没有暂停"的完整论证。
 *
 * <h2>进度可以记, 但它不是"续做游标"</h2>
 * {@link #progressAt(Instant)} 回答的是"她做到几成了" —— 一个<b>已经发生的事实</b>,
 * 用于行为分析（"她这一天被打断了三次, 每次都在 20% 左右"）。
 * 它<b>不</b>回答"接下来该从哪继续": 新的一次 Activity 从 0 开始,
 * 而"还剩多少活"是规划器看着意图重新估的。
 *
 * <p>这个区别听起来很细, 但它是"暂停/恢复"与"重新规划"的分水岭:
 * 前者把进度当成<b>输入</b>, 后者把进度当成<b>输出</b>。
 *
 * <h2>实现类应当是<b>不可变</b>的</h2>
 * 状态迁移（{@code RUNNING → CONCLUDED}）返回一个新实例, 而不是原地改。
 * 理由与 {@code PlanItem} 一样: 她"12:15 那一刻做到几成"必须能被回放,
 * 而一个可变对象在日志里只会留下最后的值。
 */
public interface Activity {

    /** 这一次执行的 id。见 {@link ActivityId} —— 它与计划项 id 不是一回事。 */
    ActivityId id();

    /**
     * 她在做的这件事是什么（意图）。
     *
     * <p>为什么持有 {@link PlanIntent} 而不是一个描述字符串: 活动的性质
     * （要不要占地方、需要什么能力、可不可以挪时间）都在意图上,
     * 而"她正在做的这件事需要什么"恰恰是 Mind 决定要不要打断她时的关键输入。
     */
    PlanIntent intent();

    /**
     * 它实现的是计划表上的哪一项。
     *
     * <p>可能为空: 她可能做了一件计划表上没有的事
     * （"突然想给妈妈打个电话"）—— 那是真实的, 不该被禁掉。
     * 为空时这条 Activity 仍然是一次合法的执行, 只是没有计划项与它对应。
     */
    java.util.Optional<PlanItemId> planItemId();

    /** 她什么时候开始做的。 */
    Instant startedAt();

    /** 现在是什么状态。 */
    ActivityState state();

    /** 这次执行是什么时候结束的（还在进行时为空）。 */
    java.util.Optional<Instant> endedAt();

    /**
     * 结束时的说明 —— "做完了"、"被叫走了"、"不想做了"。
     *
     * <p>它进 {@code plan.item-finished.v1} / {@code plan.item-interrupted.v1}
     * 的载荷, 并最终进入她的 LLM context。所以写成<b>第一人称、面向人</b>的句子。
     */
    java.util.Optional<String> closingNote();

    // ─────────────────────────── 注意力与可达性 ───────────────────────────

    /**
     * 这件事占用她多少注意力（0..1）。
     *
     * <p>它的用途很具体: 决定一条通知<b>能不能进入她的意识</b>。
     * 一个 0.9 的活动意味着她此刻对手机的注意力很低 ——
     * 于是消息来了她"没注意到", 而不是"不想理"。
     *
     * <p>这两种情形在数据上必须分开: 前者会被归因成"她太专注了",
     * 后者会被归因成"她在冷落我"。而同样的一个"没回消息",
     * 归因不同会让行为分析得出完全相反的结论。
     */
    double attentionDemand();

    /**
     * 打断这件事的代价（0..1）。越接近 1 越容易被打断。
     *
     * <p><b>它不是"许可"</b> —— 决定要不要打断她的是 Mind（§3.4）。
     * 本方法回答的是"打断她有多难、多伤": 一个 0.12 的活动（开会）
     * 被消息打断时, 代价不只是那几分钟, 还有重新进入状态的时间。
     */
    double interruptibility();

    /**
     * 此刻手机在不在她手边（0..1）。
     *
     * <p>这个值会影响"查看手机"这类动作能不能成功 —— 一个 0.05 的值
     * （手机在包里）应当让她"想查看但够不着", 而<b>够不着的原因要能被她自己理解</b>。
     * 否则她的行为会表现为"她决定不看手机", 而真相是她拿不到 ——
     * 那是一个把物理约束误读成意愿的错误。
     */
    double phoneAvailability();

    /** 这件事对心情的影响（-1..1）。 */
    double moodEffect();

    // ─────────────────────────── 时间与进度 ───────────────────────────

    /** 已经做了多久（在 {@code at} 时刻）。 */
    default Duration elapsedAt(Instant at) {
        Instant end = endedAt().orElse(at);
        Duration d = Duration.between(startedAt(), end);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /**
     * 她做到几成了（0..1）—— 一个<b>已发生的事实</b>, 不是续做游标。
     *
     * <p>见接口注释里"进度可以记, 但它不是续做游标"那一段。
     */
    double progressAt(Instant at);

    /** 她原本估计要做多久。 */
    default Duration estimatedDuration() {
        return intent().expectedDuration();
    }

    /**
     * 做完这件事比她估计的多花了多久。
     *
     * <p>正值 = 超时（她估少了）, 负值 = 提前（她估多了）。
     * 这个数累积起来回答一个很有价值的问题: <b>"她估时间准不准"</b>。
     * 一个总是估少三成的人, 与一个估得准的人, 是两种不同的生活状态。
     */
    default java.util.Optional<Duration> overrunAt(Instant at) {
        if (state() == ActivityState.RUNNING) {
            return java.util.Optional.empty();   // 还没结束, 超时无从谈起
        }
        return java.util.Optional.of(elapsedAt(at).minus(estimatedDuration()));
    }

    // ─────────────────────────── 迁移 ───────────────────────────

    /**
     * 收尾: 把这次执行结束掉, 返回一个新实例。
     *
     * <p>这是本接口<b>唯一</b>的写操作, 而且它只做一件事: 结束。
     * 没有 {@code pause()}、没有 {@code resume()}、没有 {@code updateProgress(d)} ——
     * 它们都存在的话, {@code Activity} 就变成了一个小型的任务调度器,
     * 而用户否定的正是那个模型。
     *
     * @param at    结束的仿真时刻
     * @param state {@link ActivityState#CONCLUDED} 或 {@link ActivityState#ABANDONED}
     * @param note  一句人能读懂的话。放弃时必须给（见 {@link ActivityState#requiresReason}）
     */
    Activity conclude(Instant at, ActivityState state, String note);

    /** 这次执行在数据里怎么被引用 —— 日志与事件载荷用。 */
    default String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(id().value()).append(" 「").append(intent().description()).append("」 ")
                .append(state().label());
        sb.append(" 起于 ").append(startedAt());
        endedAt().ifPresent(e -> sb.append(", 止于 ").append(e));
        planItemId().ifPresent(p -> sb.append(" ← ").append(p.value()));
        return sb.toString();
    }

    /** 一次执行里她需要的能力 —— 转发给意图, 供 {@code PlanningContext} 检查。 */
    default List<String> requiredCapabilities() {
        return List.copyOf(intent().requiredCapabilities());
    }
}
