package com.luxera.companion.human;

import com.luxera.companion.boundary.action.ActionCommand;
import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.event.WorldEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.1.2 —— <b>到达她面前的东西只有三种</b>。
 *
 * <h2>为什么恰好是这三种, 而不是"再来几种"</h2>
 *
 * 因为任何东西要到达她面前, 只有三条路:
 *
 * <ol>
 *   <li>外面发生了事（{@link WorldArrived}）—— 手机响了、下雨了、有人敲门;</li>
 *   <li>时间到了（{@link ClockTicked}）—— 唯一一个"什么都没发生, 但她仍要处理"的时刻;</li>
 *   <li>她做过的事有了回音（{@link ActionCompleted}）—— 那条消息发出去了没有。</li>
 * </ol>
 *
 * <p>第四种写法一定是这三者之一的伪装。文档里点名的那个例子值得抄在这里:
 * <b>"计划到点了"听起来像第四种, 但它不是一条到达她的事件</b> ——
 * 它是 {@code PlanSchedulerJob} 调 {@code Life.advanceTo(now)} <b>产生</b>的一条刺激,
 * 那条刺激随后以 ① 的形式到达（见 §8.5.1 ①与 §8.5.5）。把它也做成一种
 * {@code HumanEvent}, 就会让"到点了"有两个来源: 一条是计划表推出来的,
 * 一条是外面投进来的, 而这两条会在同一次心跳里各到一次。
 *
 * <h2>为什么不是 {@code sealed}</h2>
 *
 * 与 §3.4.7 的 {@code PlanMutation} 同一条理由: Java 17 的 {@code sealed} 是真的
 * （§1.3 P4 用它）, 但它<b>不产生穷尽检查</b> —— 穷尽检查来自 {@code switch} 的模式匹配,
 * 而后者在 17 还是 preview（本仓禁止用它, 见 {@code V22BoundaryArchitectureTest}）。
 *
 * <p>于是"sealed + 一个 switch"这个组合的收益是<b>零</b>: 接口标了 sealed,
 * 实现类还是得用 {@code instanceof} 链去认, 而链尾那个 {@code else} 分支
 * 仍然要自己写。既然如此, 这里靠的是两样别的东西:
 * <ul>
 *   <li><b>三种形状的语义论证</b>（上面那段）—— 它才是真正的约束;</li>
 *   <li><b>一条绊线</b>: {@code accept} 的 instanceof 链的最后一条是
 *       {@code throw IllegalArgumentException}, 而它在类型上写不出来任何"第四种"。
 *       将来真有人加了一种形状, 它会在第一次到达时<b>当场炸掉并说出收到的类型</b>,
 *       而不是被静默地当成"不认识的事件"丢掉 —— 后者才是这类扩展最怕的失败方式
 *       （她少处理了一件事, 没有任何痕迹）。</li>
 * </ul>
 *
 * <h2>为什么 {@link ActionCompleted} 不被建模成一条 {@code WorldEvent}</h2>
 *
 * 因为它是唯一一条<b>指向过去</b>的事件: 它说的是"你刚才那个命令。结果是这样"。
 * 把它摊平成一条"世界发生了 X", {@code command} 与 {@code result} 之间那个
 * 对应关系就只能靠时间去猜 —— 而她可能同时有两条在飞。这条对应关系是
 * {@code Mind.admitActionOutcome(...)} 唯一的输入, 不能丢。
 *
 * <p>由此还能推出一个不那么明显但同样重要的结论: <b>本接口不需要"事件 id"、
 * 不需要去重</b>。①② 的重放语义由 {@code EventFabric} 与事件自己的
 * {@code eventId()} 负责; ③ 是一次函数调用的对偶, 它本来就是"一次"的。
 * 在这里加一层 id 只会让人以为"同一条 HumanEvent 投两次会被合并成一次",
 * 而那件事谁也没做。
 */
public interface HumanEvent {

    /**
     * 这件事<b>发生在</b>哪一刻 —— 不是"她被通知"的那一刻。
     *
     * <p>对 ① 与 ③ 来说, 这个值来自世界侧（事件自己的时间戳、动作结果的完成时刻）,
     * 而她处理它的时刻是 {@code HumanRuntimeContext.now()}。两者<b>不是一回事</b>,
     * 而且刻意不合并: "她三点钟才知道两点钟发生的事"是仿真里最正常不过的一种状态,
     * 把两者对齐就等于把消息传输时间抹成零。
     *
     * <p>对 ② 来说这个值就是心跳时刻本身 —— 那正是"时间到了"的全部含义。
     *
     * <p><b>本接口的任何实现都不许在这里读系统时钟</b>（§3.4.2 与 §8.2.7 的源码扫描）。
     */
    Instant occurredAt();

    /**
     * 一句人话 —— 它会出现在日志、控制台与诊断输出里。
     *
     * <p>它<b>不是</b> {@code toString()} 的别名, 也不该长成字段的罗列。
     * 判据很简单: 把这行字单独抄给一个没看过代码的人, 他能不能说出"她面前发生了什么"。
     * {@code "WorldArrived[event=MessageArrived@2026-09-19T10:00:00Z]"} 不能,
     * {@code "外面来了件事: 她收到一条消息"} 能。
     *
     * <p>这一点值得写成硬要求而不是风格建议, 因为这条字符串是<b>唯一</b>一种
     * "她感觉到什么"的忠实记录中不依赖于事件类型的部分 —— 事件类型会改名、
     * 会分叉, 而这行字是人写的。
     */
    String describe();

    /**
     * ① <b>世界发生了一件事</b> —— {@code EventFabric} 投进来的。
     *
     * <h2>为什么它包着一个 {@code WorldEvent}, 而不是直接就是 {@code WorldEvent}</h2>
     *
     * 因为 {@code WorldEvent} 有十几二十种实现, 而她的入口只能有三种形状。
     * 若 {@code accept} 直接收 {@code WorldEvent}, 那"到达她的东西只有三种"这条
     * 约束就只活在文档里, 代码上不存在。包一层之后, <b>"这是到达她的事件"
     * 与"这是世界内部的事件"变成两个类型</b> —— 前者是她的输入语言, 后者是世界的
     * 记录格式, 而两者将来会朝不同方向演化（世界那边会加回放所需的字段,
     * 她这边不需要）。
     *
     * <p>包一层的代价是: 路由这件事（{@code StateEffectEvent} → 账本、
     * {@code SensoryEvent} → 队列、{@code ScheduledEvent} → 计划表）<b>不在这里做</b>,
     * 而是由 {@code EventFabric.publish} 在做投递时同步完成（§3.1.3 那一行明确写了:
     * "这一步由 EventFabric 在做投递时已经同步完成, {@code accept} 只是把它
     * 纳入 actor 的串行区"）。也就是说这个 record <b>是一张收据, 不是一台分拣机</b>:
     * 她收到它时, 账本与队列已经变了, 她要做的是让该跑的处理器的状态机在
     * 串行区里往前走一步。
     *
     * @param event 刚被投进 fabric 的那条世界事件
     */
    record WorldArrived(WorldEvent event) implements HumanEvent {

        public WorldArrived {
            Objects.requireNonNull(event, "WorldArrived 必须带一条世界事件 —— "
                    + "一条'什么都没说'的到达无法被处理, 也无法被记录");
        }

        /**
         * 世界侧那一刻 —— <b>不是她被通知的那一刻</b>。
         *
         * <p>直接转问事件自己, 不另存一份。存一份就意味着两个字段有机会不一致,
         * 而不一致的那个瞬间没法排查（两条时间戳都"看起来对"）。
         */
        @Override
        public Instant occurredAt() {
            return event.occurredAt();
        }

        @Override
        public String describe() {
            return "外面来了件事: " + event.describe();
        }
    }

    /**
     * ② <b>心跳到了</b> —— 唯一一个"什么都没发生, 但她仍要处理"的时刻。
     *
     * <h2>"什么都没发生"为什么也要有一条事件</h2>
     *
     * 因为她的身体不会因为外面安静就停下来: 体温在往外散、精力在掉、饿在累积。
     * 这些都是<b>时间的函数</b>, 而它们没有任何一条外部触发。若没有这一种事件,
     * 那"推进她的生理状态"就只能由"某个外部事件恰好到了"来搭便车 ——
     * 世界安静一整天, 她就一整天没变过（而账本明明在算)。
     *
     * <h2>它为什么不是"每秒一条刺激"</h2>
     *
     * 因为它<b>不是感觉</b>, 是节拍。心跳不进入注意力闸门 —— 它不会因为她"在忙"
     * 就被丢掉（丢掉了, 她的身体就不再随时间变化了, 而"她忙起来就忘了吃饭"
     * 应该是 Mind 的判断, 不是心跳的丢失）。所以本形状<b>不实现</b>
     * {@code SensoryEvent} 那一套（没有 {@code urgency()}、没有 {@code foldingKey()}）:
     * 那些字段在这里没有意义, 而加一个"没意义但填得出来"的默认值,
     * 就是给下一个读代码的人一个错误的心智模型。
     *
     * @param at 心跳所代表的那个瞬间 —— 由调用方传入, <b>不许在这里读系统时钟</b>
     */
    record ClockTicked(Instant at) implements HumanEvent {

        public ClockTicked {
            Objects.requireNonNull(at, "心跳必须带时刻 —— 心跳就是'时间到了', 没有时刻的心跳"
                    + "无法推进任何与时间有关的东西。时刻一律由调用方传入: human/ 里不许读系统时钟");
        }

        @Override
        public Instant occurredAt() {
            return at;
        }

        @Override
        public String describe() {
            return "心跳到了: " + at;
        }
    }

    /**
     * ③ <b>她刚才那个动作有结果了</b> —— 世界侧的回执。
     *
     * <h2>为什么 command 与 result 必须成对出现</h2>
     *
     * 见接口注释里"为什么不被建模成一条 WorldEvent"。这里补一句本 record 层面的:
     * 它<b>不</b>提供"只给 result"的构造器。一个只有结果、没有"这是哪个命令的结果"
     * 的对象, 调用方只能拿 {@code result.capabilityKey()} 去猜 ——
     * 而同一个能力键她可能同时发了两条（两条消息各回一句）。
     *
     * @param command 她发出的那条命令
     * @param result  世界对它的答复
     */
    record ActionCompleted(ActionCommand command, ActionResult result) implements HumanEvent {

        public ActionCompleted {
            Objects.requireNonNull(command, "动作回执必须带上原命令 —— 少了它, "
                    + "'这个结果对应哪次动作'就只能靠时间去猜, 而 Mind 的工作记忆拒绝一条没有来源的结论");
            Objects.requireNonNull(result, "动作回执必须带结果 —— 一条'有命令、没结果'的回执"
                    + "会让 Mind 无法判断这件事成没成");
        }

        /**
         * <b>世界完成它的那一刻</b>（{@code result.completedAt()}）——
         * 用世界自己的表, 不用她的。
         *
         * <p>不用 {@code ctx.now()} 的理由: 那是"她正在处理它的时刻", 而一个动作
         * 的结果可能在她下一次心跳之前就躺在队列里了。这两个时刻在 {@code ActionResult}
         * 上本来就有确定答案, 让本方法转去读别的东西等于把它抹掉。
         */
        @Override
        public Instant occurredAt() {
            return result.completedAt();
        }

        @Override
        public String describe() {
            return "她的动作有结果了: " + result.describe()
                    + " (对应命令 " + command.commandId() + ")";
        }
    }
}
