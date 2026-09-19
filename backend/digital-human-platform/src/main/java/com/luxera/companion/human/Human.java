package com.luxera.companion.human;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.mind.Mind;
import com.luxera.companion.human.mind.MindSnapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.1 —— <b>Agent 在仿真世界中的完整"人"对象</b>。聚合根。
 *
 * <p><b>Human 不持有 World 的任何引用。</b>它没有 phone 字段、没有 environment 字段、
 * 没有 chatPlatform 字段 —— 不是"约定不写", 是 §8.2 的 ArchUnit 测试会编译期拦住
 * （{@code V22BoundaryArchitectureTest.theHumanNeverReachesIntoTheWorld}）。
 *
 * <p>Human 与外界的全部接触面只有两个:
 * <ul>
 *   <li><b>进</b>: {@code EventFabric} 把 {@code WorldEvent} 送进 {@link Body} /
 *       {@link Life} / {@link Mind}；</li>
 *   <li><b>出</b>: {@code ActionFabric} 把 {@code ActionCommand} 送到 World。</li>
 * </ul>
 *
 * <h2>一、她这一个类里, 唯一一件"逻辑"是 {@link #accept}</h2>
 *
 * 其余全是 getter 与一次 {@code Objects.requireNonNull}。这不是偷懒 ——
 * 聚合根的职责是<b>把三块拼成一个能被串行驱动的整体</b>, 而不是替它们做事。
 * 任何一条"顺手在这里也算一下"的逻辑（比如在 {@code accept} 里直接改计划表),
 * 都会让"她为什么这么做"多出一个不在 {@code Decision} 链上的解释。
 *
 * <h2>二、她持有一个 {@code EventFabric} —— 这是对 §3.1 那张骨架的一处补充</h2>
 *
 * §3.1 列出的字段只有 id / body / life / mind 四个, 但同一页的 §3.1.3 行为表里
 * 写着 {@code accept} 要做 {@code fabric.publish(...)} 与 {@code fabric.tick(at, ctx)},
 * 而 §8.5.4 的分工表也明确说 Human 知道"Body / Life / Mind, <b>以及 fabric
 * 与 actionFabric</b>"。三处合起来只有一种读法: 骨架漏写了 fabric。
 *
 * <p>于是这里补上 {@code fabric}, 并说明为什么<b>不</b>像 §8.5.4 说的那样
 * 连 {@code actionFabric} 一起收下: {@link #accept} 的三个分支里没有任何一个
 * 需要它（"出"的那一步是 {@code HumanActor} 的 ④d, 而结果回执由 actor 在同一拍里
 * 回喂进来, 见 {@code HumanEvent.ActionCompleted} 与 §8.5.11.1）。一个"收下但从不用"的字段就是给未来
 * 那条"顺手执行一下"的捷径留的门 —— 而 {@code Decision} 的类注释里写着
 * 那条捷径在类型上不该存在。所以 actionFabric 由 {@code HumanActor} 持有。
 *
 * <h2>三、三个部件谁都不能替她读时钟</h2>
 *
 * 她身上没有一个字段与"现在"有关。时刻要么来自参数（{@code ctx.now()}、
 * {@code event.occurredAt()}）, 要么来自部件自己记下的<b>上一次推进</b>。
 * 这条由 §8.2.7 的源码扫描守住（{@code Instant.now()} 之类）。
 * 唯一的例外是 {@link #lastTouchAt} —— 它存的也是"她被推进到过哪一刻",
 * 而那个值来自参数, 不是读来的。
 */
public final class Human {

    private final HumanId id;
    private final Body body;
    private final Life life;
    private final Mind mind;
    private final EventFabric fabric;

    /**
     * 她最后一次被推进到哪一刻 —— <b>来自 {@code accept} 的参数, 不是读来的</b>。
     *
     * <h2>为什么需要一个这样的字段</h2>
     * 因为 {@link #context()} 要造 {@code MindSnapshot} 与 {@code LifeSnapshot},
     * 而这两个都<b>必须带一个时刻</b>（一个没有时刻的进度没有值）。
     * 快照面不该自己发明时刻 —— 它能用的最诚实的那个时刻, 就是"她上一次被处理的时刻"。
     *
     * <h2>为什么是 volatile</h2>
     * 因为写它的是 actor 线程, 读它的是控制台的 HTTP 线程。"她此刻的页码"读到一个
     * 稍旧的值是完全可以接受的（那是快照本来就有的性质), 读到半个 {@code Instant}
     * 则不可能 —— 引用写是原子的, 而 {@code Instant} 不可变。所以这里
     * <b>不需要锁</b>, 也刻意不加锁: 加锁会让人以为快照是一条原子的读路径,
     * 而它不是（见 {@link #context()}）。
     */
    private volatile Instant lastTouchAt;

    public Human(HumanId id, Body body, Life life, Mind mind, EventFabric fabric) {
        this.id = Objects.requireNonNull(id, "Human 必须有 id");
        this.body = Objects.requireNonNull(body, "Human 必须有身体 —— 一个没有身体的她收不到任何刺激");
        this.life = Objects.requireNonNull(life, "Human 必须有生活 —— 一个没有生活的她没有计划表可推进");
        this.mind = Objects.requireNonNull(mind, "Human 必须有认知 —— 一个没有认知的她不产生任何决定");
        this.fabric = Objects.requireNonNull(fabric, "Human 必须有 EventFabric —— "
                + "它是世界到她的唯一一条路(另一条是 ActionFabric, 由 HumanActor 持有)");
        requireSamePerson();
    }

    /**
     * 三个部件必须都是<b>她</b>的。
     *
     * <h2>为什么这条检查值得写出来, 而且值得让它在构造时就炸</h2>
     * 因为把"别人的记忆"装配到她身上是<b>一次静默的错</b>: 消息会进到错误的
     * {@code RelationshipGraph}、进度会记到错误的计划表、保温值会算在错误的身上 ——
     * 而这一切都符合类型、都能跑、都不会抛异常。它的症状是"她记得一件没发生过的事",
     * 而那要等到几天后有人看日志才会被发现。
     *
     * <p>这正是 {@link HumanId} 那个值类型要防的同一类错误的<b>第二个战场</b>:
     * 值类型让"把 userId 传进 humanId"编译不过, 而这里挡住的是"三个都是 HumanId,
     * 只是不是同一个人" —— 后者类型系统看不见, 因为三处签名都写对了。
     */
    private void requireSamePerson() {
        requireMatches("Body", body.id());
        requireMatches("Life", life.humanId());
        requireMatches("Mind", mind.humanId());
        requireOnePlanBoard();
    }

    /**
     * {@code Life} 与 {@code Mind} 必须共用<b>同一张</b>计划表 —— 同一个对象, 不只是相等的两张。
     *
     * <h2>为什么这条检查必须存在, 而且必须与上面三条并排</h2>
     * 因为这两块<b>各自都持有一张计划表</b>, 而它们的持有方式不一样:
     * <pre>
     *   Life  → 自己造一张 (this.plan = new PlanBoard())
     *   Mind  → 由构造参数收一张
     * </pre>
     * 于是"装配时把 {@code life.plan()} 传给 {@code Mind}"就成了一条<b>只写在别人脑子里</b>
     * 的隐式约定。写错的代价不是变慢或报错, 是这三件事同时发生:
     * <ul>
     *   <li>{@code Mind.decide} 把重排的结果应用到了 A 表;</li>
     *   <li>{@code PlanSchedulerJob} → {@code Life.advanceTo} 读的是 B 表 ——
     *       于是<b>她的决定从来不影响她实际要做什么</b>;</li>
     *   <li>B 表上挂着 {@code PlanEventPublisher}（它在 {@code Life} 的构造器里被接上）,
     *       而 A 表上没有 —— 于是重排<b>一条事件都不发</b>, 前端日程视图与行为分析
     *       都看不到这件事发生过。</li>
     * </ul>
     * 三条合起来的效果是: 她不打断、不重排、不改变计划, 而<b>日志里一片正常</b> ——
     * 用户专门纠正过两次的那条"打断 = 真的去改计划表"整条是死的, 且没有任何报错。
     *
     * <p>这一条与上面三条是同一类错误的第三个战场: 值类型让"把 userId 传进 humanId"
     * 编译不过; {@code requireMatches} 挡住"三个 HumanId 不是同一个人";
     * 这里挡住"两张表都是 PlanBoard, 只是不是同一张"。<b>三者类型系统全都看不见。</b>
     *
     * <p>为什么用 {@code ==} 而不是 {@code equals}: 因为这里要的正是<b>同一个对象</b>。
     * 一个按值相等的 {@code equals} 会让"两张内容相同但各自演化的表"通过检查 ——
     * 而它们从下一拍起就会分叉, 那正是这条检查要防的东西。
     */
    private void requireOnePlanBoard() {
        if (life.plan() != mind.plan()) {
            throw new IllegalArgumentException("装配错了: 这个 Human 是 " + id.value()
                    + ", 但它的 Life 与 Mind 各自持有<b>不同的</b>计划表 —— "
                    + "Life 自己造一张, Mind 收一张, 而它们不是同一个对象。"
                    + "后果是她重排进了一张没人调度的表: 她的决定不影响她要做什么, "
                    + "重排一条事件也不会发出去, 而这一切没有任何报错。"
                    + "装配时请把 life.plan() 传给 Mind 的构造参数。");
        }
    }

    private void requireMatches(String part, String actual) {
        if (!id.value().equals(actual)) {
            throw new IllegalArgumentException("装配错了: 这个 Human 是 " + id.value()
                    + ", 但它的 " + part + " 属于 " + actual
                    + " —— 把别人的部件装到她身上不会立刻报错, 只会让她"
                    + "在几天后'记得'一件没发生过的事。收到的值: [" + actual + "]");
        }
    }

    // ─────────────────────────── 她是谁 / 她的部件 ───────────────────────────

    /** 她是谁。给所有人和所有表用 —— 它是个不可变的值, 拿出去没有风险。 */
    public HumanId id() {
        return id;
    }

    /**
     * 她的身体。 —— <b>只给 {@code HumanActor} 用。</b>
     *
     * <h2>为什么它是 public, 而"只给 actor 用"这句话写在注释里</h2>
     * 因为 {@code HumanActor} 住在 {@code com.luxera.companion.runtime} ——
     * <b>另一个包</b>。Java 没有"包内可见 + 指定的那个类也可见"这种可见性
     * （没有 friend）: 要么把它标成 package-private 而把 actor 搬进 {@code human} 包,
     * 要么 public 而靠文档与纪律约束。前者过不了 §8.4 那张包图（执行体属于
     * {@code runtime} 那一层, 把它塞进领域包里会让"谁是驱动程序"这个问题变模糊),
     * 所以选后者。
     *
     * <p>纪律的内容是:<b>除了 {@code HumanActor}, 任何人读她的状态都应当走
     * {@link #context()}</b> —— 那是 §3.1.4 为跨模块只读专门开的门,
     * 它给的是不可变快照。绕过它直接读 {@code body()} 的后果不是"不安全"
     * （读本身不改任何东西）, 而是<b>它会读到写了一半的状态</b>
     * （actor 线程正在 {@code Body.advance()} 里做多步写入,
     * 而读的人不是那条线程）。
     *
     * <p>这条纪律现在<b>被机器守住了</b> —— {@code V22BoundaryArchitectureTest}
     * 的 {@code onlyTheActorAndTheHumanPackageReadHerInternals} 断言:
     * 除了 {@code human/} 自己与 {@code runtime.HumanActor}, 任何类调
     * {@code body()} / {@code life()} / {@code mind()} 都会把这个模块打红,
     * 而且它自带一条阳性对照（{@code theHumanInternalReadRuleWouldActuallyCatchAViolation}）
     * 保证自己不是空转的。
     *
     * <p>写清楚它守的是<b>什么</b>: 它守的是"谁在读她的内部"这个问题有一个
     * 可以回答的答案, 不是"读她很危险"。读本身不改任何东西; 真正被防住的是
     * 上面那条"读到一个写了一半的状态", 以及"某天有人顺手在别处 new 一个
     * 读她状态的组件, 而没人知道"。
     */
    public Body body() {
        return body;
    }

    /**
     * 她的生活（计划表 + 活动日志）。<b>只给 {@code HumanActor} 用</b> ——
     * 理由与 {@link #body()} 完全相同, 不再重复。
     *
     * <p>值得单独说一句的是: 拿到 {@link Life} 的人<b>能替她开始一件事</b>
     * （{@code Life.begin}）与<b>改她的计划表</b>（{@code Life.replan}）。
     * 这两个动作都必须由 {@code HumanActor} 在串行区里、在一次 {@code Decision}
     * 之后做 —— 从别处调用它们不会报错, 只会让"她为什么做这件事"多出一个
     * 不在决定链上的解释。
     */
    public Life life() {
        return life;
    }

    /**
     * 她的认知。<b>只给 {@code HumanActor} 用</b> —— 理由与 {@link #body()} 完全相同。
     *
     * <p>这一块的危险比另两块高一档: {@code Mind.remember(...)} 与
     * {@code Mind.meetAccount(...)} 会<b>永久地改她</b>, 而写入的记忆没有
     * "从哪来"的回执。谁能拿到 Mind, 谁就能往她的记忆里塞一条她从未经历过的事。
     */
    public Mind mind() {
        return mind;
    }

    /**
     * 她的那条进线 —— {@code WorldRuntime.bind(humanId, fabric)} 用它把世界接到她身上。
     *
     * <p>它是 public 的, 而且是<b>刻意</b>的: §8.5.2 里 {@code WorldRuntime} 装配
     * actor 时必须拿到它（{@code world.bind(humanId, actor.fabric())}）,
     * 而 {@code WorldRuntime} 在 {@code runtime} 包。把 fabric 藏起来只会逼出
     * 一条"由 actor 转发"的路, 而那条路没有增加任何安全性:
     * {@code EventFabric} 本来就是世界与她之间的<b>边界对象</b>,
     * 它上面没有一条方法能改她的状态（投递的事由订阅者决定）。
     */
    public EventFabric fabric() {
        return fabric;
    }

    // ─────────────────────────── 唯一的入口 ───────────────────────────

    /**
     * <b>处理一封来自世界外部的信。</b>
     *
     * <p>这是 Human 的唯一入口, 也是单线程 actor 的执行体 ——
     * 同一个 Human 的事件严格串行, 不同 Human 之间完全并行（§7.3）。
     * "严格串行"这件事由 {@code HumanActor} 的锁保证, 不在这里 ——
     * 本方法自己没有锁, 也<b>不该</b>有: 它是普通对象方法, 语义上可以在
     * 测试里被直接调用（那正是它能被单测的原因）。
     *
     * <h2>三个分支</h2>
     * <ol>
     *   <li>{@link HumanEvent.WorldArrived} → {@code fabric.publish(...)};</li>
     *   <li>{@link HumanEvent.ClockTicked} → {@code fabric.tick(...)} 之后再
     *       {@code body.advance(...)};</li>
     *   <li>{@link HumanEvent.ActionCompleted} → {@code mind.admitActionOutcome(...)}。</li>
     * </ol>
     *
     * <p><b>时刻一律从 {@code ctx.now()} 取, 不读墙钟</b>（§3.1.3 最后一行,
     * 由 §8.2.7 的扫描守住）。注意"取"这个词的准确含义: 她用 {@code ctx.now()}
     * 表达"我现在在处理它", 而事件自己的 {@code occurredAt()} 说的是"它发生在什么时候"
     * —— 两个都在, 且都有人用（比如 {@code ActionCompleted} 分支刻意用
     * {@code result.completedAt()} 由 {@code Mind} 自己处理, 见那里的注释）。
     */
    public void accept(HumanEvent event, HumanRuntimeContext ctx) {
        Objects.requireNonNull(event, "要处理的事件不能为空 —— 一次'什么都没有'的处理"
                + "会推进她的内部状态却没有任何原因");
        Objects.requireNonNull(ctx, "处理事件必须带运行上下文 —— 时刻从它取, 不许读系统时钟");

        // 先记下"她走到过哪一刻"。放在分支之前, 是为了让三条路的语义一致:
        // 无论刚才发生的是什么, "她最后一次被推进"都应该是这一刻。
        this.lastTouchAt = ctx.now();

        // 为什么是 instanceof 链而不是 switch 的模式匹配:
        // 后者在 Java 17 还是 preview, 本仓禁止用（见 §3.4.7 与同目录 PlanBoard.applyOne）。
        if (event instanceof HumanEvent.WorldArrived arrived) {
            acceptWorldArrived(arrived, ctx);
        } else if (event instanceof HumanEvent.ClockTicked ticked) {
            acceptClockTicked(ticked, ctx);
        } else if (event instanceof HumanEvent.ActionCompleted completed) {
            acceptActionCompleted(completed, ctx);
        } else {
            // 绊线 —— 见 HumanEvent 的"为什么不是 sealed"。
            // 走到这里说明有人加了一种新的 HumanEvent 而忘了在这里处理它。
            // 让它当场炸掉并说出收到的类型: 另一种做法（静默忽略）的症状是
            // "她少处理了一件事", 没有异常、没有日志、没有指向这里的线索。
            throw new IllegalArgumentException("不认识的事件形状: "
                    + event.getClass().getName()
                    + " —— HumanEvent 只有三种形状(见 §3.1.2), 第四种一定是这三者之一的伪装。"
                    + "收到: " + event.describe());
        }
    }

    /**
     * ① 世界发生了一件事 —— 重新投进 fabric。
     *
     * <h2>为什么这一分支是"再 publish 一次"</h2>
     * 因为 §3.1.3 那张表写得很清楚: 路由（{@code StateEffectEvent} → 账本、
     * {@code SensoryEvent} → 队列、{@code ScheduledEvent} → 计划表）与入账
     * "由 {@code EventFabric} 在做投递时已经同步完成", 她这一步做的是
     * <b>"把它纳入 actor 的串行区"</b> —— 也就是说, 真正需要串行的是
     * <b>各个 handler 的状态机</b>（{@code Body} 的阈值检测哨兵、
     * {@code Life} 的计划表观察者、{@code Mind} 的注意力窗口), 而不是入队本身。
     *
     * <p>订阅关系在装配时就定好了（{@code Body.registerOn(fabric)} 之类),
     * 所以这条 publish 会走到她自己身上的三块部件 —— 这正是"她说'我知道外面发生了事'"
     * 的那一步。
     *
     * <h2>一处要说实话的代价</h2>
     * {@code EventFabric.publish} 的契约是"投递即完成, 同一条事件投两次会进两次"
     * （它<b>不去重</b> —— 去重是发布者的责任）。于是本方法的前提是:
     * <b>别人不会把一条已经在 fabric 里走过一遍的事件再包成 {@code WorldArrived} 送进来</b>。
     * 若世界侧既直接 publish 又经由 actor 转发同一条事件, 她的阈值哨兵与注意力窗口
     * 会各看到它两次 —— 症状是"她为同一条消息动了两次念头", 而这在日志里看起来
     * 像两次真实的事件。这条前提目前只能靠装配纪律, {@code HumanEvent} 上加一个
     * 事件 id 也挡不住（同一条事件包两层是不同的对象）。它是本次实现里
     * <b>唯一一条"要靠约定而不是靠类型"</b>的性质。
     */
    private void acceptWorldArrived(HumanEvent.WorldArrived arrived, HumanRuntimeContext ctx) {
        fabric.publish(arrived.event());
    }

    /**
     * ② 心跳到了 —— 结算账本, 然后让身体往前走一步。
     *
     * <h2>顺序为什么是"先 tick 再 advance"</h2>
     * 因为 {@code Body.advance} 要吃的是<b>这一 tick 结算出来的净效应</b>
     * （{@code TickReport.settlement()}）—— 外面的温度、她身上湿掉的衣服、
     * 刚喝下去的那杯热水, 全都在那个对象里。先结算再推进, 于是"她为什么冷"
     * 与"她冷了没有"用的是同一份数据; 反过来做, 身体就会永远在用上一 tick 的账。
     *
     * <h2>为什么这里<b>不</b>调 {@code Life.advanceTo(now)}</h2>
     * 因为它已经在心跳的 ① 里跑过了（{@code WorldRuntime.pulse} 的第一步就是
     * {@code planSchedulerJob.tickAt(now)}, 而它调 {@code Life.advanceTo}）。
     * §3.1.3 的表里专门写了这一句 —— "已经跑过"。再调一次不是"多跑一遍没事":
     * {@code advanceTo} 会把同一批到点的计划项再触发一次, 于是
     * <b>"她开始写作业了"这条刺激会在同一秒里产生两条</b>,
     * 而那看起来就像世界重复投递了一次。
     *
     * <p>为什么 {@code accept} 不自己判断"要不要推进计划表"而把这个顺序
     * 交给调用方: 因为"谁负责哪一步"必须有一个确定答案, 而 §8.5.1 已经给了 ——
     * ① 是计划表, ④a 是身体。两个地方都"顺手也做一下"就没人知道哪一步是该做的。
     */
    private void acceptClockTicked(HumanEvent.ClockTicked ticked, HumanRuntimeContext ctx) {
        EventFabric.TickReport report = fabric.tick(ticked.at(), ctx);
        body.advance(report.settlement(), ticked.at(), fabric);
    }

    /**
     * ③ 她的动作有结果了 —— 把结果交给 Mind。
     *
     * <h2>为什么用 {@code ctx.now()} 而不是 {@code result.completedAt()}</h2>
     * 因为 {@code Mind.admitActionOutcome(result, at)} 里那个 {@code at} 的含义是
     * <b>"她什么时候知道的"</b> —— 它决定这条结果在时间轴上的位置,
     * 而工作记忆的排序与"她等了多久才等到回音"都按它算。
     * 世界完成它的时刻（{@code completedAt}）是另一个事实, 它没有被丢掉:
     * 它就在 {@code result} 上, {@code Mind} 想用可以自己拿
     * （{@code ActionCompleted.occurredAt()} 也返回它）。
     *
     * <p>把"她知道"与"它发生"分开, 是仿真里最常见的一种真实:
     * 一条消息可能在她发出十分钟之后才送到 —— 那两个时刻不是一个。
     *
     * <p><b>不做的事</b>: 不重试。§3.1.3 那列写得很清楚 —— "重试是 Mind 的决定,
     * 不是 {@code accept} 的"。一个失败的结果在这里被记进工作记忆,
     * 至于"要不要再发一次", 那是下一次 {@code Decision} 的事。
     *
     * <h2>谁生产这一支（§8.5.11.1）</h2>
     * {@code HumanActor.dispatchDecisions} —— 它调完
     * {@code ActionFabric.execute(command)} 之后, 在同一拍里把
     * {@code new ActionCompleted(command, outcome)} 回喂进来。所以"她做完了就知道"
     * 是对 {@code execute} 同步契约的如实表示, 而不是一条捷径。
     *
     * <p>重复入账（同一个结果的第二条回执）由<b>类型</b>挡住: 一条不能同步给出
     * 结果的能力必须返回 {@code ActionResult.Status.UNAVAILABLE}, 于是它这一拍
     * 没有给出结果, 将来那条真正的完成回执是第一条而不是第二条。
     * 换句话说, <b>这一支的入账责任在世界侧</b> —— 谁想投递它, 谁就得先保证
     * 自己没有同步给出过一个结果。这是本类唯一一处无法用参数校验表达的契约。
     */
    private void acceptActionCompleted(HumanEvent.ActionCompleted completed, HumanRuntimeContext ctx) {
        mind.admitActionOutcome(completed.result(), ctx.now());
    }

    // ─────────────────────────── 只读面 ───────────────────────────

    /**
     * <b>看她一眼 —— 现场构造三张快照。</b>控制台、运维面、{@code AgentProfileProjector} 走这条路。
     *
     * <p>它与 {@link #body()} / {@link #life()} / {@link #mind()} 的区别不是"更安全",
     * 而是<b>方向</b>: 快照上没有一条方法能改她, 所以"外部模块改了她的状态"这件事
     * 在类型上不可能（§3.1.4 的第二个理由 —— 它比并发那条更硬, 因为并发可以用锁兜住,
     * 而一个存在过的写入口迟早会被用)。
     *
     * <h2>它用的时刻: 她最后一次被处理的时刻</h2>
     * 也就是 {@code accept} 上那个 {@code ctx.now()}。<b>不是</b>读系统时钟 ——
     * 一个控制台请求的处理时刻与她被推进到的时刻是两回事, 用前者会让快照说
     * "她的进度是按我这次刷新的时间算的"。
     *
     * <p>若她还没有处理过任何事件, 本方法<b>抛异常</b>而不是凑一个时刻出来 ——
     * 一个没有时刻的快照无法回答"她的进度是多少", 而"凑一个现在"正是
     * {@code EventFabric.describe(Instant)} 的注释里记着的那个真实 bug。
     * 需要一份"她还没动过"的快照时, 用 {@link #contextAt(Instant)} 显式给一个时刻。
     *
     * @throws IllegalStateException 她还没有处理过任何事件
     */
    public HumanContext context() {
        Instant at = lastTouchAt;
        if (at == null) {
            throw new IllegalStateException("她还没有处理过任何事件, 所以没有'当前时刻'可用 —— "
                    + "这不是'现在就是零时刻', 而是'她还不知道时间'。"
                    + "要一份指定时刻的快照, 用 contextAt(Instant)");
        }
        return contextAt(at);
    }

    /**
     * <b>按给定的时刻看她一眼。</b>
     *
     * <p>时刻要由调用方给: 回放时要看的是那一刻的她, 而不是"她最后一次被推进的时刻"
     * —— 两者在回放里差得很远, 而混用会让回放出一份"过去的身体 + 现在的进度"的图。
     *
     * <h2>它不是一条原子的读路径</h2>
     * 本方法会分别问 Body、Life、Mind, 中间不加锁: 真正的 actor 线程可能正好在
     * 这些读之间推进了一次心跳, 于是三张快照可能各自属于相邻的两个瞬间。
     * 这是明知的: 快照给眼睛看, 拿它去做判断才是不可接受的用法
     * （见 {@link LifeSnapshot#of(Life, Instant)} 里同一条说明）。
     * 要一份严格自洽的读, 得在 actor 的串行区里取 —— 而那条路刻意不存在:
     * 一旦存在, 就会有人在里面写逻辑。
     */
    public HumanContext contextAt(Instant at) {
        Objects.requireNonNull(at, "快照必须带时刻 —— 不许读系统时钟");
        BodySnapshot bodySnapshot = BodySnapshot.of(body);
        LifeSnapshot lifeSnapshot = LifeSnapshot.of(life, at);
        MindSnapshot mindSnapshot = mind.snapshot(at);
        return new ReadOnlyView(id, bodySnapshot, lifeSnapshot, mindSnapshot);
    }

    /** 一行摘要 —— 日志用。它只答"她还活着吗、走到哪了、手上有什么事"。 */
    public String describe() {
        return "Human[" + id.value() + "] "
                + (lastTouchAt == null ? "尚未处理过任何事件" : "推进到 " + lastTouchAt)
                + " | " + life.describe()
                + " | " + body.describe();
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * {@link HumanContext} 的实现 —— 一个私有的、不可变的、什么都不做的记录。
     *
     * <p>它故意是 {@code private}: 外面的人只能通过 {@link #context()} 拿到它,
     * 而且拿到的静态类型是接口 {@code HumanContext}。于是"这上面还有什么别的
     * 方法可以用"这个问题在类型层面就没有答案 —— 想加一个"顺手也把
     * {@code Body} 带出来"的访问器, 得先把这个 {@code private} 改成 {@code public}。
     */
    private record ReadOnlyView(
            HumanId humanId,
            BodySnapshot body,
            LifeSnapshot life,
            MindSnapshot mind) implements HumanContext {
    }
}
