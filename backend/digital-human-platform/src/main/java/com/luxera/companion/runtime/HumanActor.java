package com.luxera.companion.runtime;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.boundary.action.ActionCommand;
import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanEvent;
import com.luxera.companion.human.HumanId;
import com.luxera.companion.human.body.senses.SensoryStimulus;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.attention.AttentionContext;
import com.luxera.companion.human.mind.cognition.ReasoningContext;
import com.luxera.companion.human.mind.cognition.ReasoningResult;
import com.luxera.companion.human.mind.decision.ActionIntent;
import com.luxera.companion.human.mind.decision.Decision;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.PlanItemIntention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

/**
 * V2.2 §8.5.4 —— <b>一个 Human 的单线程执行体</b>。
 *
 * <p>{@code Human.accept(HumanEvent, HumanRuntimeContext)}（§3.1）是<b>语义</b>上的入口；
 * {@code HumanActor} 是<b>执行</b>它的那个东西。两者的分工是:
 *
 * <table border="1">
 *   <tr><th></th><th>{@code Human}</th><th>{@code HumanActor}</th></tr>
 *   <tr><td>知道什么</td><td>Body / Life / Mind，以及 fabric</td>
 *       <td>一个 {@code Human}、一个运行档、一串计数</td></tr>
 *   <tr><td>负责什么</td><td>"收到这件事她怎么办"</td>
 *       <td>"<b>同一时刻只有一件事在办</b>"</td></tr>
 *   <tr><td>不许知道</td><td>World、Phone、聊天平台</td><td>她的内部结构</td></tr>
 * </table>
 *
 * <p>（表里的 {@code actionFabric} 由本类持有而不是 {@code Human} ——
 * 理由见 {@code Human} 的类注释第二条。）
 *
 * <h2>一、为什么必须串行（§8.5.4 原文的两句）</h2>
 *
 * 同一个人身上的两条事件若能并发处理，会发生这样的事: "手机响了"和"12:00 到了"
 * 同时在跑，两边都读到"她闲着"，于是两边都去改计划表 —— 第二个写覆盖第一个，
 * 而两条事件<b>各自都成功处理了</b>。这类丢失没有异常、没有日志，
 * 只在时间轴上表现为"她少做了一件事"。单线程 actor 从结构上消灭它。
 *
 * <p>不同 Human 之间<b>完全并行</b> —— 她的手机响不会让另一个 agent 变慢。
 * 所以这里锁的是<b>一个 actor 实例</b>（{@code private final ReentrantLock}），
 * 而不是任何全局的东西。
 *
 * <h2>二、{@code PAUSED} 的处理点在这里, 且只有这里（§8.5.4 最后一段）</h2>
 *
 * {@code lifecycle != ACTIVE} 时 {@code pulse} 直接返回，<b>不 drain 刺激、不推进账本</b>
 * —— 她的刺激队列会积压，而这正是 §3.6.6 说的"她不看"。
 * 积压的是 B 类队列；A 类的账本<b>仍然每 tick 结算</b>（外面的温度不会因为她被暂停
 * 就不冷了），只是没有人去看结算结果。恢复时 {@code RecoveryRuntime}（§8.5.6）
 * 要读的是<b>世界历史</b>，不是那堆积压的刺激。
 *
 * <p>说清楚这句话在本实现里的确切形状: 账本结算发生在 {@code EventFabric.tick} 里,
 * 而 {@code tick} 只被 {@link #pulse} 的 a 步调用。所以暂停期间<b>本 actor 不结算</b>
 * —— 而"账本仍然每 tick 结算"这句话的<b>结果</b>仍然成立: 账目上的每条效应都带时刻,
 * 到期与否是按 {@code now} 算出来的（{@code ContinuousEffectLedger.settle} 的语义),
 * 所以她醒来那一刻的第一次结算, 得到的是"按此刻该有的值", 不会因为中间少结算了几次
 * 而多冷一分。这不是我将就的写法, 而是这个结构本来就有的一条性质。
 *
 * <h2>三、为什么用 {@code ReentrantLock} 而不是 {@code synchronized}</h2>
 *
 * 因为这里真正需要的能力是 <b>"拿不到就跳过"</b>（{@code tryLock()}),
 * 而 {@code synchronized} <b>表达不出</b>它 —— {@code synchronized} 只有"等"。
 * 等在这里是错的: 心跳线程被一个卡住的 tick 拖住, 会让<b>所有人的心跳</b>一起塌。
 *
 * <p>不沿用 {@code PlanSchedulerJob} 的 {@code AtomicBoolean.compareAndSet} 是另一条理由,
 * 而且这条更常被忽略: <b>重入</b>。{@code tryLock} 对<b>已经持有它的那条线程</b>
 * 永远成功（只是把计数 +1), 而 {@code compareAndSet} 会把自己人当场判成"重入"
 * 并跳过, 于是那次嵌套调用<b>无声消失</b>。
 *
 * <p>说清楚它今天的处境, 免得被读成一条已经生效的保护: 本类里
 * <b>还没有任何一条会嵌套调用 {@code pulse} 的路径</b>。动作结果在同一拍里回喂
 * （§8.5.11.1, 见 {@code dispatchDecisions}）走的是 {@code Human.accept} ——
 * 它进聚合根, 不再进 {@code pulse}, 所以它<b>不</b>消费这条性质。
 * 留着它的理由是可观测性, 不是"重入是对的": 真出现嵌套调用时, tryLock 让那次
 * 调用真的跑起来并留下计数与日志, 而 CAS 让它什么都不留。
 *
 * <h2>四、跳过意味着什么（相对 {@code PlanSchedulerJob} 的 skip 语义）</h2>
 *
 * {@code PlanSchedulerJob} 的跳过是"这一秒的到点检查晚一点做, 而迟到会被如实记下"。
 * 本类的跳过不一样, 值得写清楚:
 * <ul>
 *   <li><b>计划表不会因此漏掉任何东西</b> —— "到点了"是 ① 的事
 *       （{@code PlanSchedulerJob}, 由 {@code WorldRuntime} 直接驱动, 不经过 actor）;</li>
 *   <li><b>时间也不会漏</b> —— {@code Body.advance} 吃的是"两次推进之间的差",
 *       下一次推进会自动把这一秒补上（而账目本身是按时刻算的）;</li>
 *   <li><b>刺激也不会丢</b> —— 它们还在 Body 的各条通道缓冲里等着 drain,
 *       通道满了才有丢弃, 而那件事有自己的丢弃计数（{@code SensoryChannel.discarded}）;</li>
 *   <li><b>真正延后的是"这一刻的想法"</b> —— 这一 tick 没有新感知进意识,
 *       于是基于这一刻的处境本该产生的那次决定没有发生。它不会被补做:
 *       下一 tick 的处境已经变了, 而"她当时想不想回"这个问题没有事后答案。
 *       这是跳过的真实代价, 也是为什么它必须被计数。</li>
 * </ul>
 *
 * <p><b>为什么不排队。</b>与 {@code PlanSchedulerJob} 同一条理由: 积压的 tick
 * 集中释放时, 它们会读到<b>同一份处境</b>（处境是这一 tick 的, 不是那一刻的),
 * 于是同一批刺激被重复判断、结论互相覆盖 —— 那正是本节开头那段"两边都读到
 * 她闲着"的重演, 只不过是在一条线程里排队重演。
 *
 * <p>还有一条只属于本类的理由: <b>今天不该发生</b>。唯一的调用方是
 * {@code WorldRuntime} 的心跳线程, 而它自己保证了一次只叫一个 actor 一下。
 * 所以 {@code skipped} 一旦非零, 就是一个<b>装配错误或不请自来的第二个调用点</b>
 * 的信号, 而不是抖动 —— 与 {@code PlanSchedulerJob} 的 {@code skippedTicks}
 * （那里的确是抖动）含义不同。计数保留, 因为"不排队"这条语义要在这里就定下来,
 * 而不是等到有人加了第二个 pulse 调用方时再定。
 *
 * <h2>五、失败怎么办 —— 与 {@code PlanSchedulerJob} 同一张三档表</h2>
 *
 * <ol>
 *   <li><b>她自己的能力在 {@code ActionResult} 里失败</b> —— 不抛异常, 正常返回
 *       （{@code ActionFabric.execute} 的契约）。这不是本类要处理的"失败"，
 *       它是一条正常的结果, 回执会交给 Mind（§3.1.3）;</li>
 *   <li><b>处理器抛出运行时异常</b> —— 吞掉、计数、记 error, <b>下一 tick 照常</b>。
 *       理由是那句话: <b>她的一天不能因为一个坏处理器停下来</b>。一个下午的仿真
 *       停在一封格式不对的通知上, 是比"这一 tick 少做一件事"大得多的损失;</li>
 *   <li><b>时钟被要求倒流</b> —— <b>不吞, 直接抛出去</b>。这条刻意放在 try 之外,
 *       见 {@link #pulse} 的开头。倒流不是"某个处理器坏了", 而是整个仿真的时间轴
 *       失效了 —— 那时继续跑下去得到的每一帧数据都是不可信的, 而它看起来
 *       和正常数据一模一样。</li>
 * </ol>
 *
 * <h2>六、{@code pulse} 之外, 没有任何方法能进到一个 Human 里面去</h2>
 *
 * §8.5.4 的最后一句话。本类因此<b>不提供</b> {@code human()}、{@code context()}
 * 这类"看一眼她"的方法 —— 要看她走 {@code Human.context()}, 那是 §3.1.4
 * 给所有外部读者开的门。想读到 {@code Human} 本体的话, 装配代码自己留着引用
 * （它本来就要用它来构造这个 actor）。
 */
@Slf4j
public final class HumanActor {

    /**
     * 一个心跳最多从世界侧的队列里取几条刺激。
     *
     * <h2>为什么要有上限, 而不是"一次全取出来"</h2>
     * 理由是 {@code RealtimeEventQueue.drain(int)} 自己的那段话, 这里照抄它的意思:
     * <b>一次醒来面对 200 条刺激不是"更完整的还原", 而是一个真人不会有的处境</b> ——
     * 真人醒来只会注意到几件最响的事。上限让这个仿真保持可信。
     *
     * <p>而"取不完"在这里<b>不是丢失</b>: 队列按"紧迫度 ↓ / 发生时刻 ↑ / 入队序 ↑"
     * 定序（{@code RealtimeEventQueue.ORDER}), {@code drain} 取走的正是最该先进意识的那几条,
     * 剩下的留在队里等下一 tick。真正会丢的只有队列<b>满</b>的时候, 而那时
     * 丢弃是被记账的（{@code RealtimeEventQueue} 的丢弃历史）——
     * "她漏听了什么"在那个读面上答, 不在这里。
     *
     * <h2>为什么是 8</h2>
     * 一个 tick 是世界的一秒。一秒里能"响"的事本来就少（闹钟、一条消息、
     * 一次到点), 而 8 已经比一个真人在一秒里会注意到的多得多。
     * 取 8 而不是 {@code DEFAULT_CAPACITY}(256) 是关键 —— 后者是<b>队列容量</b>,
     * 不是"一拍的注意力预算"; 把容量当预算用, 等于宣布"她可以在一秒里读完 256 条消息"。
     */
    public static final int DRAIN_LIMIT = 8;

    private final Human human;
    private final ActionFabric actionFabric;

    /**
     * 串行区的锁。
     *
     * <p>{@code private} 且从不外传: 外部拿不到它就<b>不可能</b>绕过 {@code pulse}
     * 去持有串行区 —— 而"绕过"这件事正是 §8.5.4 那句"pulse 之外没有任何方法
     * 能进到一个 Human 里面去"要防的。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /** 运行档 —— 会被 {@code AgentRegistry} 在运行期改（默认 {@code ACTIVE}）。 */
    private volatile AgentLifecycle lifecycle;

    private final AtomicLong pulses = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong pausedPulses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong stimuliFromWorld = new AtomicLong();
    private final AtomicLong stimuliFromBody = new AtomicLong();
    private final AtomicLong stimuliAbsorbed = new AtomicLong();
    private final AtomicLong decisionsDispatched = new AtomicLong();
    private final AtomicLong commandsSent = new AtomicLong();

    /** 她最后一次真的跑过的心跳时刻 —— 诊断用, 也是"时钟倒流"的判据。 */
    private volatile Instant lastPulseAt;

    public HumanActor(Human human, ActionFabric actionFabric) {
        this(human, actionFabric, AgentLifecycle.ACTIVE);
    }

    public HumanActor(Human human, ActionFabric actionFabric, AgentLifecycle lifecycle) {
        this.human = Objects.requireNonNull(human, "actor 必须有一个她");
        this.actionFabric = Objects.requireNonNull(actionFabric,
                "actor 必须有一个 ActionFabric —— 它是 Mind 唯一的出口(§3.4.7); "
                        + "少了它, 她的决定会被算出来然后丢掉, 而那看起来就像'她不想回应'");
        this.lifecycle = Objects.requireNonNull(lifecycle, "运行档不能为空 —— "
                + "认不出来的档位请用 AgentLifecycle.of(raw), 它会兜底到 ACTIVE");
    }

    // ─────────────────────────── 心跳 ───────────────────────────

    /**
     * <b>推她一下</b> —— §8.5.1 心跳的第 ④ 步。
     *
     * <pre>
     * pulse(now)
     *   ├ a. human.accept(ClockTicked(now), ctx)
     *   │       —— fabric.tick(账本结算 + ON_TICK handler) 与 Body.advance() 都在这里
     *   ├ b. fabric.stimuli().drain(N) + body.drainSensoryStimuli()
     *   │       —— B 类刺激出队, <b>两条来源</b>: 世界侧的队列(不经过 Body)
     *   │          与身体侧的五条通道。见 absorb(...) 的说明
     *   ├ c. mind.absorbAll(世界侧) / mind.absorb(身体侧) —— 同一个处境, 同一个闸门
     *   │       —— 注意闸门: 不是每条刺激都进得了她的意识
     *   ├ d. 若 Mind 产出 Decision → actionFabric.execute(command)
     *   │       —— Mind 唯一的出口(§3.4.7)
     *   └ e. 把这一 tick 的摘要记下来(计数, 不落库)
     * </pre>
     *
     * <h2>为什么 a 是"把 ClockTicked 交给她"而不是直接调 fabric.tick</h2>
     * 因为"心跳到了她怎么办"是<b>她的事</b>（§3.1.3 的表里写着顺序: 先结算、
     * 再让身体走一步)。本类<b>不许知道她的内部结构</b> —— 它只知道
     * "有一件事到了她面前", 而这件事该走哪条路、按什么顺序走, 是 {@code accept} 的知识。
     * 这里多绕的一层是刻意的: 它让"心跳"与"世界里的某件事"在她面前是同一种东西。
     *
     * <h2>为什么 b 在 a 之后: 这一拍刚产生的刺激也要在这一拍进意识</h2>
     * a 会做两件事, 而两件都会产生刺激:
     * <ul>
     *   <li>{@code fabric.tick} 跑 ON_TICK handler, 而 {@code Body.advance}
     *       顺带跑阈值检测（"冷到一定程度"就是在那里产生的）——
     *       那些刺激刚被放进五条通道, 紧接着的 {@code drainSensoryStimuli()} 就能拿到它们。
     *       于是"她冻着了"这件事在<b>同一次心跳里</b>就进了她的意识, 而不是等下一次 ——
     *       差一秒听起来无关紧要, 但时间加速模式下那就是差一分钟;</li>
     *   <li>同一 tick 里心跳 ①（{@code PlanSchedulerJob}）与 ②（设备演化）投进来的事件
     *       已经<b>同步</b>进了 world 侧队列（{@code publish} 的契约), 所以
     *       {@code drain(DRAIN_LIMIT)} 拿到的也包括"计划到点了"与"手机刚响了"。</li>
     * </ul>
     *
     * <p>这两条合起来是"为什么 ④ 在 ① 与 ②③ 之后"（§8.5.1）在代码里的样子:
     * 她这一拍感知到的, 就是这一拍的世界。反过来（b 在 a 之前）会让她永远看着
     * 上一 tick 的世界, 而每一条反应都晚一秒。
     *
     * <h2>时刻</h2>
     * 参数就是 §8.5.1 里"一次心跳只读一次"的那个时刻, 由 {@code WorldRuntime} 读好传进来。
     * 本方法<b>不读系统时钟</b>（也不许: 同一条规则在 {@code human/} 里由源码扫描守着,
     * 在这里由纪律与同一条规则的精神守着）。
     *
     * @param now 这一次心跳代表的瞬间
     * @throws IllegalArgumentException 仿真时间倒流（见类注释第五节的第 3 档）
     */
    public void pulse(Instant now) {
        Objects.requireNonNull(now, "心跳必须带时刻 —— 时刻一律由调用方传入, 不许读系统时钟");

        Instant previous = lastPulseAt;
        if (previous != null && now.isBefore(previous)) {
            throw new IllegalArgumentException("仿真时间倒流: 上一次心跳在 " + previous
                    + ", 这一次在 " + now + " —— 这不是'某个处理器坏了', 而是整个仿真的"
                    + "时间轴失效了。继续跑下去得到的每一帧数据都不可信, 而它看起来与正常数据"
                    + "一模一样, 所以这里不吞(与 PlanSchedulerJob 的时钟检查同一条理由)");
        }

        if (!lock.tryLock()) {
            // 跳过, 不排队 —— 见类注释第四节。这里的 warn 是刻意的:
            // 它今天不该发生, 所以它一出现就值得被人看见。
            long count = skipped.incrementAndGet();
            log.warn("[HumanActor/{}] 上一个心跳还没跑完, 这一次(在 {})被跳过 —— "
                            + "今天唯一的调用方是单线程的 WorldRuntime, 所以这个数一旦非零, "
                            + "说明多了一个调用点或者有一个 handler 卡住了。累计跳过 {} 次",
                    humanId(), now, count);
            return;
        }

        try {
            if (lifecycle != AgentLifecycle.ACTIVE) {
                // §8.5.4: 暂停时直接返回 —— 不 drain 刺激、不推进账本。
                // 注意这里<b>不</b>更新 lastPulseAt: 她没有被推进过,
                // 而"她最后一次跑是什么时候"必须说的是真话(否则倒流检查的基准也会漂)。
                pausedPulses.incrementAndGet();
                return;
            }

            pulses.incrementAndGet();
            lastPulseAt = now;
            runOneTick(now);
        } catch (RuntimeException e) {
            // 第 2 档: 吞掉、计数、下一 tick 照常 —— 她的一天不能因为一个坏处理器停下来。
            // 只记 message 与异常本身, 不重抛: 重抛会让 WorldRuntime 的一个 for 循环
            // 停在半路, 于是"她后面那个人今天一次心跳都没跑"。
            failures.incrementAndGet();
            log.error("[HumanActor/{}] 心跳 {} 里有一个处理器抛了异常 —— "
                            + "这一次的剩余步骤被放弃, 她的下一天照常开始(累计失败 {} 次)",
                    humanId(), now, failures.get(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 一个心跳里的 a–e。
     *
     * <p>它<b>只在持锁时被调用</b>（唯一调用点是 {@link #pulse} 的 try 块内),
     * 而这件事没法用 Java 的可见性表达出来 —— 所以它写成 {@code private},
     * 让"下一个想调它的人"必须先进 {@code pulse}。
     */
    private void runOneTick(Instant now) {
        HumanRuntimeContext context = HumanRuntimeContext.at(humanId().value(), now);

        // a. 心跳到了她面前 —— 账本结算 + Body.advance 都在她的 accept 里。
        human.accept(new HumanEvent.ClockTicked(now), context);

        // b. 两条来源都要出队 —— 见 {@link #absorb} 的说明与 Body 类注释的那张图。
        //    顺序(先世界侧再身体侧)不承载语义: 两边都要过同一个闸门,
        //    而闸门的排序在 AttentionService 里, 不在出队顺序上。
        List<SensoryEvent> external = human.fabric().stimuli().drain(DRAIN_LIMIT);
        List<SensoryStimulus> internal = human.body().drainSensoryStimuli();
        stimuliFromWorld.addAndGet(external.size());
        stimuliFromBody.addAndGet(internal.size());

        // c. 注意闸门 —— 只有过得了闸门的感知才进得了她的意识。
        List<AttendedPercept> attended = absorb(external, internal, now);

        // d. Mind 唯一的出口。把它自己的 context 传下去 —— 动作结果要在同一拍里
        //    回喂给她, 而"同一拍"的那个时刻就在 context 里(见 dispatchDecisions)。
        dispatchDecisions(attended, context);

        // e. 摘要(计数, 不落库)。
        //    为什么不落库: §8.5.1 的原文就是"计数, 不落库" —— 每秒一条的摘要
        //    会把事件流淹掉, 而"她今天怎么样"要靠事件流与账本来回答,
        //    不能靠每秒钟一行的心跳日志。所以这里只有 debug 级的痕迹。
        if (log.isDebugEnabled()) {
            // Body.describe() 会遍历通道与账本, 所以它只在 debug 打开时才算 ——
            // 这也是这一行不写成 log.info 的原因之一。
            log.debug("[HumanActor/{}] {} 这一拍: 出队 世界 {} 条 / 体内 {} 条, 进意识 {} 条 | {}",
                    humanId(), now, external.size(), internal.size(), attended.size(),
                    human.body().describe());
        }
    }

    /**
     * <b>c. 注意闸门。</b>把刚出队的刺激送进她的意识 —— <b>两条来源, 同一个闸门</b>。
     *
     * <h2>为什么是两条来源: 它们走的是两条不同的路</h2>
     * {@code Body} 的类注释里那张图把这件事画得很清楚（这里抄其中的两行）:
     * <pre>
     *   世界 ──WorldEvent──> EventFabric ──> 队列(实时刺激) ──> Mind  ← ① 不经过 Body
     *   传感器 ──RawSignal──> Body.apply() ──> 五条感官通道 ──> 刺激      ← ② 体内感受
     * </pre>
     * <ul>
     *   <li><b>① 世界侧</b>: 手机响、闹钟响、<b>计划到点了</b> —— 这些是
     *       {@code SensoryEvent}（带紧迫度、可折叠、可按来源路由), 进的是
     *       {@code EventFabric} 的 {@code RealtimeEventQueue}, 而那条路
     *       <b>刻意不经过 Body</b>: 它们是"她听说/看到的事", 不是"她身体上的感觉"。
     *       所以出队的是 {@code fabric.stimuli().drain(...)}, 喂给
     *       {@code Mind.absorbAll(List<SensoryEvent>, ...)};</li>
     *   <li><b>② 身体侧</b>: 冷、饿、疼、累 —— 这些是 {@code SensoryStimulus}
     *       （由阈值检测与五条通道产出, 带 diff), 出队的是
     *       {@code body.drainSensoryStimuli()}, 喂给
     *       {@code Mind.absorb(SensoryStimulus, ...)}（它走
     *       {@code Perception.explainInteroceptive}）。</li>
     * </ul>
     *
     * <p>两条路的类型<b>不一样, 而且不该一样</b>: {@code SensoryStimulus} 的类注释
     * 明确写着"我不是 {@code SensoryEvent}"。所以这里既<b>不</b>把刺激包装成事件,
     * 也<b>不</b>造一个适配器 —— 那两种写法都会让"这条刺激从哪来"这个问题在
     * 类型上消失, 而它决定了她能不能分辨"外面在下雨"与"我自己在发抖"
     * （真人分不清, 但仿真的诊断面必须分得清, 否则"她为什么突然想穿衣服"
     * 就没法归因）。结论是: <b>两个入口各用一个, 而不是挑一个。</b>
     *
     * <p>历史上这里的第一个版本<b>只做了 ②</b>, 于是 ① 那条队列全仓没有消费者:
     * 手机响了、闹钟响了、计划到点了 —— 都只是躺在队列里, 她永远不知道。
     * 那不是一个漏掉的细节, 而是"打断 = 真的去改计划表"这条链的入口整条断掉。
     *
     * <h2>为什么一个批次共享同一份处境</h2>
     * 因为处境是"她此刻的" —— 一个 tick 里她只有一份（见 {@link #attentionAt})。
     * 两条来源、一批刺激都用它, 于是"这一条为什么进了意识"与"那一条为什么没进"
     * 能被同一个处境解释。若每条刺激各算一份处境, 那批里就会出现"同一秒里
     * 她一会儿有空一会儿没空", 而那种数据没法调参。
     *
     * <h2>为什么处境在这里现场构造</h2>
     * 见 {@link #attentionAt}: 它必须取自她此刻的活动。快照面
     * （{@code Human.context()}）刻意不参与 —— 那是给眼睛看的, 不是给判断用的。
     */
    private List<AttendedPercept> absorb(List<SensoryEvent> external,
                                         List<SensoryStimulus> internal, Instant now) {
        if (external.isEmpty() && internal.isEmpty()) {
            return List.of();
        }
        AttentionContext attention = attentionAt(now);
        List<AttendedPercept> attended = new ArrayList<>();
        if (!external.isEmpty()) {
            // 世界侧: 一次一批 —— absorbAll 的存在理由就是"一批刺激共享一份处境",
            // 而这正是这里的情形。
            attended.addAll(human.mind().absorbAll(external, attention));
        }
        for (SensoryStimulus stimulus : internal) {
            // 身体侧: 逐个 —— Mind 上没有"一批 SensoryStimulus"的入口,
            // 因为阈值的产物天然是一条一条来的(每个通道各自越界)。
            attended.addAll(human.mind().absorb(stimulus, attention));
        }
        stimuliAbsorbed.addAndGet(attended.size());
        return List.copyOf(attended);
    }

    /**
     * 她此刻的处境 —— §3.4.4 那个"只许被乘一次"的对象。
     *
     * <h2>三个因子各取自哪里, 以及为什么刻意留一个不用</h2>
     * <ul>
     *   <li>{@code taskAttention} ← 当前活动的 {@code attentionDemand()}（闲着是 0）。
     *       这是"她有多忙", 也就是"她有多少注意力没被占掉";</li>
     *   <li>{@code notificationFactor} ← <b>恒为 1.0</b>。不是没写, 是刻意不写 ——
     *       见下一段;</li>
     *   <li>{@code relevance} ← {@code Relevance.undiscounted()}。见再下一段。</li>
     * </ul>
     *
     * <h2>为什么第二个因子是 1.0, 而不是 {@code activity.interruptibility()}</h2>
     * 因为那会造成<b>双罚</b>（§3.4.4 的铁律, 它点名说过这条在 V11 Phase 2 已经被踩过一次）。
     * 我们实际会写出的那一行长这样:
     * <pre>
     *   // ❌ 双罚: 一个专注的活动 attentionDemand 高(0.9) 且 interruptibility 低(0.1),
     *   //    于是"她在专注"这件事被两个系数各表达了一次, 乘积 0.09
     *   taskAttention = activity.attentionDemand();
     *   notificationFactor = activity.interruptibility();
     * </pre>
     * 这两个值不是两个事实, 是同一个"专注度"的两个出口（一个说"占了多少",
     * 一个说"好不好被叫走")。乘两次不会报错, 数值也"挺合理", 毁掉的是可调参性:
     * 当"她怎么没注意到这条消息"需要调的时候, 两个旋钮都在说同一件事,
     * 而把它们一起动会让另一个场景（她在休息但没在专注）偏掉。
     *
     * <p>那么真正的"她愿不愿意被手机打断"从哪里来? {@code Activity} 上还剩一个
     * {@code phoneAvailability()}（"手机在不在手边"), 但它按 {@code AttentionContext}
     * 自己的注释属于<b>设备侧</b>, 而那一半明确归 World（音量、震动）——
     * 它的作用是"这条刺激有多强", 不是"她此刻的意愿"。也就是说:
     * <b>这一版里没有"意愿"的来源</b>, 于是留 1.0（"没有理由因为她的意愿再打折"）。
     * 这是一个已知的缺口, 不是省略: 把它填上要先回答"谁代表她的意愿",
     * 而那个答案不属于本次改动。
     *
     * <h2>为什么相关性不打折</h2>
     * 因为 {@code Relevance.of(SourceRef)} 需要"这个来源与她是什么关系",
     * 而 {@code SourceRef} 上<b>没有账号</b>（那是它的类注释里写死的禁令: 账号到人的翻译
     * 只发生在 {@code RelationshipGraph.resolve} 一处）。于是从 {@code SourceRef}
     * 到 {@code RelationshipGraph} 目前没有桥, 而现造一座桥会绕开那条禁令。
     * {@code undiscounted()} 的语义正是"没有理由因为来源降低她的注意",
     * 与"还没有桥"这个事实相符。
     *
     * <h2>为什么 goals 是空的</h2>
     * 因为"她此刻在追的目标"没有来源: {@code Life} 不暴露"她在追什么",
     * 而把当前活动塞进去会与 {@code situation} 表达同一件事 ——
     * 同一个事实两个出口, 就是双罚在目标维度上的翻版。
     */
    private AttentionContext attentionAt(Instant now) {
        Optional<Activity> current = human.life().currentActivity();
        double taskAttention = current.map(Activity::attentionDemand).orElse(0.0);
        String situation = current
                .map(activity -> "正做着「" + activity.intent().description() + "」")
                .orElse("手头没事");
        return AttentionContext.of(now, situation, taskAttention,
                AttentionContext.NEUTRAL_RELEVANCE, AttentionContext.Relevance.undiscounted(),
                List.of());
    }

    /**
     * <b>d. Mind 唯一的出口（§3.4.7）。</b>她想到什么, 就从这里送出去。
     *
     * <h2>为什么"没什么可想的"也要走一遍完整的判断</h2>
     * 因为"她闲着"与"她想过之后决定不做"是两件必须在数据上分得开的事
     * （§3.5.6 那条 {@code KeepActive} 的理由）。{@code ReasoningContext}
     * 自己有一个判据 {@code hasAnythingToThinkAbout()}, 这里用它, 而不是在
     * 本类里另写一个 {@code if (attended.isEmpty()) return;} —— 后者会让
     * "什么时候不用想"有两个实现, 而它们会漂移。
     *
     * <h2>候选从哪来（§8.5.13）</h2>
     * {@code MindDecisionPlanner} 的类注释写明"<b>不生成候选</b>, 候选从
     * {@code ReasoningContext.candidates()} 来"。而在 §8.5.13 之前那个参数
     * <b>没有生产者</b>: 递进去的是 {@code List.of()}, 于是候选恒空、
     * {@code mind.decide} 从不被调用、{@code commandsSent} 恒为 0 ——
     * <b>她只感知、不行动</b>。
     *
     * <p>今天它由 {@link #candidatesAt} 供给: 一条<b>规则桥</b>
     * ({@code PlanItem → Intention}), 把"她此刻日程上排着的那几件事"当成候选。
     * 选它是因为它不用 LLM、确定性、可测 —— 而 §8.5.0 明确禁止在心跳里调模型
     * （一次外呼会把整条心跳拖住, 那是<b>所有人</b>的心跳, 不只是她的）。
     * 设计文档里"提出候选意图"的正主是 LLM（§3.4.5 的分工表),
     * 而它是这条规则桥的<b>替代实现</b>, 不是前置条件。
     *
     * <p>本类的形状因此没变: <b>它不发明候选, 它只负责把她的处境与日程交给
     * {@code Mind} 里装配的那个推理引擎</b>。LLM 提议器接上时,
     * 换的是 {@code candidatesAt} 一个方法, 这条路径其余部分不用改一个字节。
     *
     * <p><b>而这条链今天是空转的, 理由不在本类</b>: 计划表恒空
     * (没有生产者往它里面放项), 所以候选恒空。这件事必须说清楚 ——
     * 它是"她安静地坐着"与"她坏了"在面板上长得一样的<b>唯一</b>原因。
     * 见 {@code SimulationConfiguration} 的类注释与 {@code PlanItemIntention} 的类注释。
     *
     * <h2>为什么这里不调 {@code mind.speak(...)}</h2>
     * 因为它是<b>外呼</b>（语言引擎可能是 LLM), 而"决定"必须在不依赖任何一次
     * 网络往返的前提下成立（§3.4.5: <b>先有"她决定要说"这个值, 模型才被调用</b>）。
     * 措辞属于动作执行之后的路径, 不属于心跳。
     *
     * <h2>为什么结果在同一拍里回喂给她（§8.5.11.1）</h2>
     * 因为 {@code ActionFabric.execute(ActionCommand)} 的契约<b>就是同步返回结果</b>的
     * —— 它返回 {@code ActionResult}, 不返回 {@code Future}, 也不返回一个待会儿
     * 才有人填的空壳。于是"她在同一拍里知道了结果"不是本类图省事, 而是对代码
     * 实际行为的<b>如实表示</b>。
     *
     * <p>那"将来接上真正的异步动作, 会不会重复入账"（同一个结果的第二条回执)?
     * 不会, 而且是被<b>类型</b>挡住的: 一条不能同步给出结果的能力, 必须在这一次
     * 返回 {@code Status.UNAVAILABLE} —— 那个取值本来就是为这件事准备的
     * （见 {@code ActionResult} 类注释"为什么 REJECTED 与 UNAVAILABLE 必须分开"）。
     * 于是它这一拍<b>没有</b>给出结果, 将来那条真正的完成回执是<b>第一条</b>,
     * 不是第二条。规则一句话:
     * <blockquote>
     * 同步返回的那个结果就是结果。一条能力若不能同步给出结果, 它必须返回
     * {@code UNAVAILABLE}, 并由真正的完成时刻投递那条唯一的 {@code ActionCompleted}。
     * </blockquote>
     *
     * <h2>为什么回喂必须由这里发起: 它要落在串行区里</h2>
     * 这一次 {@code accept} 是在 {@code pulse} 的锁内被调用的, 所以"结果进入工作记忆"
     * 与"她的其余状态推进"属于<b>同一个串行区</b> —— 两者之间不会有别的线程插进来。
     * 反过来说: 如果这条回执由外部线程投递（比如某个 HTTP 线程直接
     * {@code human.accept(new ActionCompleted(...))}）, 它会与 {@code pulse} 的 a/b/c 步
     * 并发, 而那正是 §8.5.4 要消灭的那类丢失（两边各自都成功处理了,
     * 而其中一边的写入被覆盖）。这才是"她做完了就知道"必须由本类自己发起的理由 ——
     * 不是为了省一次投递, 是为了让它落在串行区里。
     *
     * <h2>一处更正: 它<b>没有</b>消费 {@code tryLock} 的可重入性质</h2>
     * 类注释第三节原先写着"将来把回执接上时, 重入那条性质就有消费者了"。这句当时
     * 写错了, 这里改对: {@code Human.accept(...)} <b>不会</b>再进 {@code pulse} ——
     * 它进的是聚合根, 而聚合根不持有这把锁。所以它既不需要锁重入, 也不会被
     * {@code AtomicBoolean.CAS} 那种写法拦下（CAS 拦的是"再次调用 {@code pulse}"）:
     * 换成 CAS, 这一次回喂<b>照样跑得完</b>。
     *
     * <p>至于那条性质本身: 它今天<b>仍然没有消费者</b>。真出现嵌套调用
     * （某个 handler 从 {@code pulse} 里再调一次 {@code pulse}）时,
     * {@code tryLock} 会让那次嵌套真的跑起来（留下计数与日志的痕迹）,
     * 而 CAS 会让它无声消失。两者都不明显正确 —— 所以这条取舍真正站得住的依据是
     * <b>可观测性</b>: 一个会留下痕迹的错误, 比一个什么都不留的错误好查。
     *
     * <p>时刻用 {@code ctx.now()}, <b>不用</b> {@code outcome.completedAt()} ——
     * 与 {@code Human.acceptActionCompleted} 的注释同一条理由: 那个 {@code at} 的
     * 含义是"她什么时候知道的", 而不是"世界什么时候做完的"。心跳里这两个值是同一个
     * （{@code execute} 就是同步的), 但语义不同, 而语义不同的两个值不该互相顶替。
     */
    private void dispatchDecisions(List<AttendedPercept> attended, HumanRuntimeContext ctx) {
        Instant now = ctx.now();
        // 处境只造一份, 三个读者共用它: 候选意图(构造时要带着它)、
        // ReasoningContext.situation、以及 Mind.decide。分头造三份会让"她这一刻
        // 有没有空"在同一拍里有三个答案 —— 而那正是 §8.5.4 要消灭的那类不一致,
        // 只不过换成了一条线程内的版本。
        PlanningContext planning = planningAt(now);
        IntentionContext situation = IntentionContext.from(planning);
        ReasoningContext reasoning = new ReasoningContext(
                now, attended, candidatesAt(now, planning), List.of(), Set.of(), situation, Map.of());

        if (!reasoning.hasAnythingToThinkAbout()) {
            return;
        }

        ReasoningResult result = human.mind().think(reasoning);
        if (!result.hasCandidates()) {
            return;
        }

        Decision decision = human.mind().decide(result, situation);
        if (!decision.hasActions()) {
            // "决定不做"也是一个决定 —— 而它已经在 Mind.decide 里记过账了
            // （mind.decision-made.v1）。本类不重复记, 也不假装它没发生。
            return;
        }

        decisionsDispatched.incrementAndGet();
        for (ActionIntent intent : decision.actions()) {
            ActionCommand command = ActionCommand.of(humanId().value(), intent.capabilityKey(),
                    now, intent.arguments());
            ActionResult outcome = actionFabric.execute(command);
            commandsSent.incrementAndGet();
            // 先记日志, 再回喂: 动作<b>已经在世界里发生了</b>, 这条痕迹不该因为
            // 她的思维层接下来抛了异常而消失(那会让"她做过什么"在运维面上少一行)。
            // 一行动作结果的日志, 是这件事唯一的痕迹 —— 心跳摘要不落库(§8.5.1)。
            log.info("[HumanActor/{}] {} 送出动作 {} → {}", humanId(), now,
                    intent.describe(), outcome.describe());
            // 同拍回喂 —— 她是"做完了就知道"的。理由见上面的
            // "为什么结果在同一拍里回喂给她"。
            human.accept(new HumanEvent.ActionCompleted(command, outcome), ctx);
        }
    }

    /**
     * 她此刻的处境 —— <b>决策侧的原始形状</b>, 而 {@code IntentionContext} 是它的投影。
     *
     * <h2>为什么本类要握着 {@code PlanningContext} 本身, 而不只留那份投影</h2>
     * 因为候选意图需要它。{@link PlanItemIntention} 桥的是一条<b>计划项</b>, 而计划项的
     * 可行性判定与动作拆分收的都是 {@code PlanningContext}(见 {@code PlanIntent})。
     * 从 {@code IntentionContext} 回不到 {@code PlanningContext} —— 那个方向有损
     * (投影里只有一句 {@code humanSummary} 字符串, 而计划侧要的是一个快照对象),
     * 所以唯一的办法是<b>在造投影之前先留着原件</b>。
     *
     * <h2>为什么能力清单取自 {@code ActionFabric} 而不是世界</h2>
     * 因为"她此刻做得了什么"的答案在世界那边（设备在不在线、能力有没有被停用),
     * 而 {@code ActionFabric.availableCapabilities(now)} 正是那件事的<b>边界出口</b>
     * —— 它属于 {@code boundary}, 于是 {@code human/} 里不需要、也不许出现
     * 任何平台概念（§3.4.2）。本类在 {@code runtime}, 它拿这个清单是它作为
     * 装配层的本分。
     *
     * <p>{@code humanSummary} 取自 {@code Life.humanSnapshot().summary()} ——
     * 那是 {@code Life} 给重排器看的同一份人话摘要, 这里不另造一句:
     * 两句关于同一个她的处境的话, 迟早会有一句是错的。
     */
    private PlanningContext planningAt(Instant now) {
        List<String> available = actionFabric.availableCapabilities(now).stream()
                .map(capability -> capability.key())
                .toList();

        // 桥上没有的两样东西, 刻意不补:
        //  - 地点 —— 她"此刻在哪儿"只有世界知道(§3.4.2 不许 human/ 读它), 而计划侧
        //    要它时是从 attributes 里带过来的。这里没有那个来源, 于是它保持空 ——
        //    空比一个猜出来的地点好: 猜出来的地点会让"她没去那个地方"变成一个无声的错误;
        //  - 约束列表 —— 那是重排器要的东西(PlanningContext 里有), 与"这件事此刻做不做得了"
        //    无关, 而 IntentionContext.from 已经明确丢掉它。
        return PlanningContext.minimal(now, human.life().humanSnapshot())
                .withCapabilities(Set.copyOf(available));
    }

    /**
     * <b>d 步的候选: 她此刻日程上排着的那几件事。</b>见 {@link PlanItemIntention}
     * 与 §8.5.13 —— 这是设计文档指定的那第一条"规则桥"。
     *
     * <h2>为什么用 {@code activeAt} 而<b>绝对不能用</b> {@code dueAt}</h2>
     * 这是一个会静默损坏仿真的陷阱, 不是风格问题:
     * {@code PlanBoard.dueAt(moment)} 在返回结果之<b>前</b>把每一项记进内部的
     * {@code triggered} 表(它的类注释写明去重就该在那里做, 因为调用方每个 tick 都会问)。
     * 于是从本类调一次 {@code dueAt} =
     * <b>把"到点了"这件事从调度器手里偷走</b> —— {@code PlanScheduler} 随后再问
     * 就什么都问不到了, 那一项<b>永远不会被触发</b>。症状是她按计划该做的事
     * 一件都没发生, 而没有任何异常、没有任何计数变红。
     *
     * <p>{@code activeAt} 是纯查询(它只读当前那一版, 不碰 {@code triggered}),
     * 而它回答的也正是本步要问的问题: "这一刻她归哪几项"。语义上它比
     * "到点了"更贴切 —— 她该不该去做某一项, 是决定引擎要判断的事;
     * 本类只负责把"日程上排着的事"摆到她面前。
     *
     * <h2>为什么每一拍都重新包一遍, 一个都不缓存</h2>
     * 因为 {@link PlanItemIntention} <b>是一张快照</b>(见它的类注释): 它握着的是
     * 构造那一刻的处境与那一版计划项。重排会让老的计划项变成 {@code SUPERSEDED},
     * 而一个被缓存下来的桥<b>不会知道</b>, 却会继续按老样子回答。
     * 重读的代价是一次内存里的区间查询 —— 与 {@code PlanScheduler} 选择
     * "轮询而不是给每一项挂定时器"是同一笔账。
     *
     * <h2>空列表是正常的, 而且它意味着"世界还没有内容", 不是"她坏了"</h2>
     * 今天 {@code PlanStore.appendRevision} 全仓没有一个生产调用者
     * (见 {@code SimulationConfiguration} 的类注释), 所以计划表恒空、
     * 候选恒空、{@code commandsSent} 恒为 0 —— <b>她会坐在座位上, 而世界无事发生</b>。
     * 本方法因此不假装自己有内容: 它返回什么, 取决于计划表里真的有什么。
     */
    private List<Intention> candidatesAt(Instant now, PlanningContext planning) {
        return human.life().plan().activeAt(now).stream()
                .map(item -> (Intention) new PlanItemIntention(item, planning))
                .toList();
    }

    // ─────────────────────────── 门面 ───────────────────────────

    /** 这是谁的 actor —— 日志与座位表用。注意它<b>不是</b> {@code Human} 本身。 */
    public HumanId humanId() {
        return human.id();
    }

    /**
     * 她的进线 —— {@code WorldRuntime.bind} 用它把世界接到她身上。
     *
     * <p>转手出去的是 {@code Human} 里那一个（装配时接上的是同一根),
     * 不是新造的: {@code EventFabric} 的订阅关系是在装配时登记进去的,
     * 一个"看起来一样"的新实例会静默地丢掉全部订阅。
     */
    public EventFabric fabric() {
        return human.fabric();
    }

    public AgentLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * 改运行档 —— 由 {@code AgentRegistry} 在运维面上调用。
     *
     * <p>它<b>不</b>加锁: 运行档是"下一个心跳要不要跑"的一个开关, 而 {@code pulse}
     * 在自己持锁之后才读它。也就是说, 改档与心跳之间只有"这一拍生效还是下一拍生效"的
     * 差别 —— 而这个差别不需要更强的保证: 唯一不能接受的结果是"暂停指令被丢了",
     * 而它不可能丢（{@code volatile} 写一旦可见, 下一个心跳必然读到）。
     *
     * <p>顺带说清一个不明显的顺序问题: 暂停<b>不</b>会打断正在跑的那一次 {@code pulse}
     * （它已经过了检查点)。那一次会跑完, 于是"暂停"的边界落在两次心跳之间 ——
     * 落在中间会让她的状态停在一个多步写入的中间, 那才是真的坏。
     */
    public void setLifecycle(AgentLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "运行档不能为空");
    }

    // ─────────────────────────── 计数与诊断 ───────────────────────────

    /** 真的跑过的心跳数（不含被跳过的与暂停时被挡下的）。 */
    public long pulses() {
        return pulses.get();
    }

    /** 因为拿不到串行区而被跳过的心跳数 —— 今天非零即为装配错误的信号（见类注释第四节）。 */
    public long skipped() {
        return skipped.get();
    }

    /** 因为 {@code PAUSED} 而直接返回的心跳数 —— 她"不看"的那些拍, 在数字上就是它。 */
    public long pausedPulses() {
        return pausedPulses.get();
    }

    /** 处理器抛异常的次数 —— 每一次都意味着那一拍剩下的步骤没跑。 */
    public long failures() {
        return failures.get();
    }

    /**
     * 从<b>世界侧</b>队列里出队的刺激条数（手机响、闹钟响、计划到点了）——
     * 出队≠进意识: 闸门在 {@link #stimuliAbsorbed()} 那一格。
     */
    public long stimuliFromWorld() {
        return stimuliFromWorld.get();
    }

    /** 从<b>身体侧</b>五条通道里出队的刺激条数（冷、饿、疼、累）。 */
    public long stimuliFromBody() {
        return stimuliFromBody.get();
    }

    /** 进过她意识的感知条数（过闸门之后 —— 没过闸门的不算, 那正是闸门的意义）。 */
    public long stimuliAbsorbed() {
        return stimuliAbsorbed.get();
    }

    /** 有动作被送出去的决定数 —— 与命令数的区别见 {@link #commandsSent()}。 */
    public long decisionsDispatched() {
        return decisionsDispatched.get();
    }

    /**
     * 送出去的 {@code ActionCommand} 条数。
     *
     * <p>它为什么是<b>另一个</b>计数: "一个原因 + 一串动作"是 {@code Decision} 的形状
     * （§3.4.7）—— 一次决定可能送出好几条命令。把两者合成一个数,
     * 会让"她今天发了三条消息"与"她今天做了三次决定"混成一个, 而这两个数
     * 回答的是不同的问题（她有多活跃 / 她有多犹豫）。
     */
    public long commandsSent() {
        return commandsSent.get();
    }

    /** 她最后一次真的跑过的心跳时刻; 没有过时为空。 */
    public Optional<Instant> lastPulseAt() {
        return Optional.ofNullable(lastPulseAt);
    }

    /** 一行摘要 —— 运维面上"她这个 actor 还正常吗"的答案。 */
    public String describe() {
        return "HumanActor[" + humanId() + "] " + lifecycle.wire()
                + " | 心跳 " + pulses.get()
                + " (跳过 " + skipped.get() + ", 暂停 " + pausedPulses.get() + ")"
                + " | 失败 " + failures.get()
                + " | 刺激 出队 世界 " + stimuliFromWorld.get() + " 条 / 体内 "
                + stimuliFromBody.get() + " 条, 进意识 " + stimuliAbsorbed.get() + " 条"
                + " | 决定 " + decisionsDispatched.get() + " 次 / 命令 " + commandsSent.get() + " 条"
                + " | 最后一次心跳 " + (lastPulseAt == null ? "(还没有过)" : lastPulseAt.toString());
    }

    @Override
    public String toString() {
        return describe();
    }
}
