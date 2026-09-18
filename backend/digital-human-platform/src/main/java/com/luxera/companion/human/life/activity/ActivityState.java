package com.luxera.companion.human.life.activity;

/**
 * V2.2 §8.4 —— <b>一次执行处在什么状态</b>。
 *
 * <h2>为什么这里用枚举是合规的, 而 {@code Activity} 用枚举是不合规的</h2>
 * 这是本设计里最容易搞错的一处分界, 所以把它写清楚。
 * 判据在 {@code PlanLifecycle} 的注释里给过, 这里再具体化一次:
 *
 * <blockquote>
 * 这个取值集合是由<b>本设计的内部逻辑</b>决定的, 还是由<b>外部世界的多样性</b>决定的?
 * </blockquote>
 *
 * <table border="1">
 *   <tr><th></th><th>外部世界的多样性 → 不能用枚举</th>
 *       <th>内部逻辑 → 用枚举是对的</th></tr>
 *   <tr><td>例子</td>
 *       <td>{@code Activity} 的种类: 睡觉/写作业/开会/通勤/…<br>
 *           <b>第三方会带来第 13 种、第 40 种</b>, 而平台源码不该为此改动</td>
 *       <td>一次执行的状态: 正在做 / 做完了 / 没做完就放下<br>
 *           <b>不会有第三方向平台提议"其实还有第四种状态"</b> ——
 *           一段已经开始的时间只有这三种结局</td></tr>
 *   <tr><td>如果用错</td>
 *       <td>第三方被迫把自己的活动塞进 {@code OTHER}, 于是
 *           "她在打游戏"和"她在发呆"变成同一件事</td>
 *       <td>每个状态都要能注册新的实现 —— 而她"半做完了"这件事
 *           在语义上根本不存在（做了一半就是没有"做完"）</td></tr>
 * </table>
 *
 * <h2>三个状态, 以及为什么没有第四个</h2>
 * <ul>
 *   <li>{@link #RUNNING} —— 她正在做。任何时刻最多只有一个 Activity 处在这个状态
 *       （她的身体只有一个）。这条约束由 {@code Life} 保证, 见 {@code Life#currentActivity()};</li>
 *   <li>{@link #CONCLUDED} —— 做完了, 或者她主动决定停下来。
 *       <b>"被打断"也归到这里</b>, 而打断的细节（被什么打断、做到几成）由
 *       {@code plan.item-interrupted.v1} 承载。为什么不给"被打断"单开一个状态:
 *       那样"她被打断后没回来"与"她被叫走又回来了"会分成两个状态, 而
 *       它们在 Activity 这个层面是同一件事 —— 一次执行结束了。
 *       "她后来回来了没有"是<b>计划表</b>的问题（有没有新的一版把她排回去）,
 *       不是这次执行的问题;</li>
 *   <li>{@link #ABANDONED} —— 她放弃了, 而且不打算再做。
 *       与 {@code CONCLUDED} 的区别在于<b>意图的方向</b>:
 *       做完是"这件事结束了", 放弃是"这件事我不做了"。
 *       这个区别必须留下, 因为行为分析要区分
 *       "她今天做完了三件事、被打断两次" 与 "她今天放弃了三件事" ——
 *       前者是忙碌, 后者是消沉, 而两者的执行次数一样。</li>
 * </ul>
 *
 * <h2>刻意没有的东西: "暂停"</h2>
 * 不存在 {@code PAUSED}, 也不存在从 {@link #CONCLUDED} 回到 {@link #RUNNING} 的迁移
 * （见 {@link #canTransitionTo}）。
 *
 * <p>用户明确否定过那条路: "不是简单把当前在做的计划 event 更新剩余时间
 * 然后立马执行一个计划 event, 再把被中断的计划 event 继续执行"。
 * "暂停/恢复"的实现必然需要两个东西: 一个 {@code PAUSED} 状态,
 * 和一个记着"还剩多久"的字段。这两个东西一旦存在,
 * "她根本不想继续了"就变成了一个<b>需要额外分支才能表示</b>的例外,
 * 而它其实是最常见的情形之一。
 *
 * <p>所以正确的表示是: 这一次执行以 {@link #CONCLUDED} 结束（做到几成记录在事件里）,
 * 她要继续的话是<b>一次新的 {@code Activity}</b>, 由计划表的新版本决定。
 * 于是"继续做"和"去做别的"在新版本里是<b>同等的两个选项</b> ——
 * 这正是用户要的那个语义。
 */
public enum ActivityState {

    /** 她正在做这件事。 */
    RUNNING("正在做"),

    /** 这一次执行结束了 —— 做完了, 或者她主动停下来了。 */
    CONCLUDED("已结束"),

    /** 她放弃了这件事, 不打算再做。 */
    ABANDONED("已放弃");

    private final String label;

    ActivityState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 还会继续下去吗（还能接受"她做到几成"这种更新）。 */
    public boolean ongoing() {
        return this == RUNNING;
    }

    /** 已经结束了（不管是做完还是放弃）。 */
    public boolean terminated() {
        return this != RUNNING;
    }

    /**
     * 从这个状态能不能走到那个状态。
     *
     * <p>只有 {@code RUNNING → CONCLUDED / ABANDONED} 是合法的。
     * <b>反向迁移一律不允许</b> —— 把"她回到了这件事"实现成一次状态回退,
     * 就等于把暂停/恢复偷偷放了回来, 而且它会带来一个更难查的问题:
     * 回退之后, 那段中间的时间（她被叫走的那 25 分钟）属于哪一次执行?
     * 回退式实现在这里只能靠给 Activity 加一个"被中断的时间段列表"来打补丁,
     * 而那个列表会一路长下去, 最后变成一份与计划表平行的第二本账。
     */
    public boolean canTransitionTo(ActivityState target) {
        if (this == target) {
            return false;   // 同状态之间的"迁移"是调用方的 bug, 不是一次迁移
        }
        return this == RUNNING && target.terminated();
    }

    /** 一次合法的收尾需要说明理由吗（放弃需要, 做完不需要）。 */
    public boolean requiresReason() {
        return this == ABANDONED;
    }
}
