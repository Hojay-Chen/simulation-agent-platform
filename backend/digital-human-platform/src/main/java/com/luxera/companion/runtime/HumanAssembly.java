package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanId;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.mind.Mind;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import com.luxera.companion.registry.EventHandlerRegistry;

import java.util.Objects;

/**
 * V2.2 §8.6.3 第 5/6 步 —— <b>把一个 {@code humanId} 变成一个活的 {@code Human}</b>。
 *
 * <h2>为什么"装配一个她"值得单独一个类</h2>
 * 因为这七行代码里有<b>三处不会报错的错法</b>, 而它们各自的症状都不是异常,
 * 是"她安静地不像一个人":
 *
 * <table border="1">
 *   <tr><th>错法</th><th>症状</th><th>谁能发现</th></tr>
 *   <tr>
 *     <td>漏掉 {@code body.registerOn(fabric)}</td>
 *     <td><b>她永远不冷、不饿、不累、不觉得疼</b> —— {@code ThresholdDetector} 不在
 *         tick 链上, 于是五条感官通道一条都产不出刺激。而她的状态<b>照样</b>推进
 *         ({@code acceptClockTicked} 自己会调 {@code body.advance}), 心跳也照样每拍成功</td>
 *     <td>{@code Human} 的构造器({@code requireBodyOnTheBus}), 会当场抛</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Mind} 收到的不是 {@code life.plan()}</td>
 *     <td>她的重排进了一张没人调度的表: 决定不影响行为, 重排一条事件都不发</td>
 *     <td>{@code Human} 的构造器({@code requireOnePlanBoard}), 会当场抛</td>
 *   </tr>
 *   <tr>
 *     <td>三块部件不是同一个人的</td>
 *     <td>她"记得"一件没发生过的事</td>
 *     <td>{@code Human} 的构造器({@code requireSamePerson})</td>
 *   </tr>
 * </table>
 *
 * <p><b>第一行曾经是这张表里唯一没有守卫的, 而它现在有了。</b>
 * 在补上守卫之前, {@code Body.registerOn} 的缺席不抛异常、不打日志、不影响任何一个计数:
 * {@code fabric.tick(now, ctx)} 在一张<b>空处理器链</b>上正常返回, 而
 * {@code HumanActor.pulse} 把这一拍计成一次成功的心跳。一个"每拍都成功、
 * 而她什么也没经历"的 agent, 在任何面板上都与健康的她一样。
 *
 * <h2>这一条是被一次勘察发现的, 不是被设计出来的 —— 而且第一版写错了症状</h2>
 * 本仓此前唯一一处完整的 {@code Human} 装配在 {@code WorldRuntimeTest} 的座位夹具里,
 * 而那份夹具<b>没有</b>调 {@code registerOn} —— 它测的是计划表与座位的接线,
 * 身体在它那里本来就不参与, 所以它一直是绿的。把那份夹具原样抄成生产装配
 * 就会把这个缺口带进产品。本类因此不是"把夹具搬过来", 而是<b>把夹具补全之后</b>
 * 才成为装配层的第一步。
 *
 * <p>本类的第一版把症状写成"身体永远不推进", 而 {@code HumanAssemblyTest} 里那条
 * <b>阳性对照</b>当场证伪了它: 漏掉 {@code registerOn} 的她, {@code hasTicked()}
 * 照样变成真。原因是 {@code acceptClockTicked} 在 {@code fabric.tick} 之后
 * <b>自己</b>调了一次 {@code body.advance} —— 于是"她有没有被推进"这个问题的答案
 * 与接线无关。这正是这个缺口能活这么久的原因, 也是它必须由构造器守卫而不是由
 * 一个计数来发现的原因: <b>没有任何一个"她在动"的读数能区分这两种装配。</b>
 *
 * <p>顺带一个可以验证的推论: 因为 {@code fabric.tick} 已经推进过一次, 而
 * {@code acceptClockTicked} 又推进一次, 第二次的 Δt 恒为 0 ——
 * {@code Body.advance} 在 Δt 为 0 时只更新时间基准就返回。所以
 * "接到链上"与"不接到链上"在<b>状态</b>上是等价的, 差别只在检测器有没有跑。
 *
 * <h2>两处诚实的缺口 —— 它们不是"占位", 是"今天就是这个样"</h2>
 * <ol>
 *   <li><b>{@code RelationshipGraph.empty()}</b> —— 她的通讯录是空的,
 *       于是她认不出任何人: {@code meetAccount} 永远解析不到 {@code PersonId},
 *       {@code PersonaSpec} 那份"她是谁的"配置在 V2.2 里<b>没有来源</b>
 *       (旧的 {@code persona} 层正是 {@code AgentRegistry} 替换掉的那一层)。
 *       这里刻意<b>不</b>造一个默认的"主人": {@code PersonaSpec} 自己的注释写着
 *       "一个默认叫'用户'的兜底会让配置错误静默地变成一个人格对象,
 *       而她的通讯录里会永远多一个叫'用户'的人"。空图会让这件事在
 *       "她怎么谁都认不出来"这里显形, 而伪造一个主人会让它永远查不出来;</li>
 *   <li><b>{@code PlanningContext.HumanSnapshot::unknown()}</b> —— 她规划时
 *       看不到自己的保暖值, 于是"天冷了要不要加件衣服"这类判断失去依据。
 *       这一条缺的是一个 {@code PhysiologicalState → HumanSnapshot} 的适配器,
 *       而全仓<b>没有</b>这个适配器({@code HumanSnapshot} 今天只有
 *       {@code unknown()} 一个实现)。做它要先决定"她对自己的身体知道多少"
 *       —— 那是一个通道灵敏度的问题(G5), 不是一次装配能定下来的。</li>
 * </ol>
 *
 * <p>两处都在上面留了名字, 于是它们各有一个可以搜到的落点 —— 而
 * {@code AgentProfileView} 会把它们连到控制台上({@code 不在这台机器上} /
 * {@code 在这台机器的座位表上} 是两句话, 不是一个 {@code null})。
 *
 * <h2>它不做恢复, 但它<b>接受</b>恢复的结果</h2>
 * 本类不读库 —— 它不 import 任何 {@code persistence/} 里的类型, 所以"她有什么历史"
 * 这个问题在它这里没有答案。把历史读出来是 {@link RecoveryRuntime} 的事(§8.5.6)。
 *
 * <p>但那两件事的<b>先后是被强制的</b>, 不是靠调用方记得:
 * 恢复出来的计划表与活动只能通过 {@link #assemble} 的参数进来, 而它们在构造器里
 * 被装到部件上 —— 于是"先装一个空白的她, 再把历史补上"这条路<b>不存在</b>,
 * 因为没有"补"这个动作。这正是 §8.6.3 第 7 步(恢复必须先于心跳)在代码形状上的落实:
 * 那个顺序之所以"错了会毁掉数据", 是因为后补的历史会撞上一个已经在动的世界;
 * 而这里连后补的入口都不提供。
 *
 * <p>两个 {@code restored*} 参数也可以都是 {@code null} —— 那是"库里没有她的历史",
 * 与"她今天刚出生"是同一件事, 而它和"读失败了"不同: 后者由
 * {@link RecoveryRuntime} 拒绝入座, 不允许被伪装成前者。
 */
public final class HumanAssembly {

    /**
     * 工具类 —— 不许有实例。
     *
     * <p>它没有状态, 也不该有: 每个 {@code Human} 的引用归装配层持有
     * ({@code HumanActor} 刻意不暴露它持有的那一个), 而"谁持有了活着的她"
     * 必须是一个能一眼看完的集合 —— 今天是 {@link LiveHumanRegistry}。
     */
    private HumanAssembly() {
    }

    /**
     * 装一个她出来 —— <b>没有恢复, 没有历史, 没有座位</b>。
     *
     * <p>三件事刻意留给调用方: 登记座位({@code WorldRuntime.bind})、把恢复出来的账本
     * 灌进她的 {@code fabric.effects()}、以及把她登记进 {@link LiveHumanRegistry}。
     * 本方法只负责"这三块部件属于同一个人, 而且都接在同一条总线上"。
     *
     * @param humanId      她是谁。必须是 {@code hum_} 前缀之外的裸 id —— 与
     *                     {@code agent_ownership.human_id} 那一列同源
     * @param lifecycle    她此刻的运行档。{@link AgentLifecycle#PAUSED} 会被原样带进
     *                     {@code HumanActor}: §3.6.6 说 PAUSED 停的是<b>认知</b>,
     *                     所以判断点在 tick 的入口, 不在装配处 —— 装配照样装,
     *                     她只是不被推进
     * @param actionFabric 动作出口。全机一个 —— 世界只有一个, 而能力表
     *                     (skill/tool/MCP, §2 的"数字设备世界可被触发")挂在它上面
     * @param clock        唯一的仿真时钟。{@code PlanSchedulerJob} 要它来判断
     *                     "到点了没有", 而这个判断<b>不许</b>读墙上时钟(§8.5.0)
     * @throws IllegalArgumentException 三块部件不属于同一个人、Life 与 Mind
     *                                  拿到的不是同一张计划表, 或 Body 没有被接到
     *                                  这条总线上 —— 三者都由 {@code Human} 的构造器
     *                                  当场抛出, 见类注释那张表
     */
    public static Parts assemble(String humanId, AgentLifecycle lifecycle,
                                 ActionFabric actionFabric, SimulationClock clock) {
        return assemble(humanId, lifecycle, actionFabric, clock, null, null);
    }

    /**
     * 带历史的她 —— <b>§8.5.6 第 ② 步的落点</b>。
     *
     * <h2>它<b>不</b>读库, 它只装</h2>
     * 两个 {@code restored*} 参数是<b>别人读出来的</b>: 读库那一步
     * ({@code PlanStore.latest} / {@code ActivityStore.runningActivity}) 归
     * {@link RecoveryRuntime}, 本类只负责把它们装到部件上。
     * 这条分工不是洁癖 —— 它让"谁读了历史"与"谁装了历史"各有一个名字,
     * 而两件事混在一起时, "恢复跑了没有"就没有单一答案了。
     *
     * <h2>为什么这两个参数必须<b>一起</b>给</h2>
     * 因为它们描述的是同一件事的两面: 库里有 {@code RUNNING} 的活动 ⟺ 那一版计划里
     * 对应的项是 {@code ACTIVE}。只给其中一半会得到两种都不报错的坏状态:
     *
     * <pre>
     *   只给计划 → 她以为自己在做那件事(项是 ACTIVE), 而 Life.current 是空的
     *              → begin(...) 不再拒绝 → 同一段时间被做两次
     *   只给活动 → Life.current 指向一件计划表上不存在的项
     *              → 她做完时 concludeCurrent 找不到那一项
     * </pre>
     *
     * <p>所以 {@link RecoveryRuntime} 一定是两个一起读、两个一起传 ——
     * 而 a-1 那种"库里有一半"的处境由它当场拒绝入座, 不允许走到这里。
     *
     * @param restoredPlan     从库里读回来的那一版计划。{@code null} = 她没有历史
     * @param restoredActivity 从库里读回来的、正在进行的那一件事。{@code null} = 她手头没事
     */
    public static Parts assemble(String humanId, AgentLifecycle lifecycle,
                                 ActionFabric actionFabric, SimulationClock clock,
                                 PlanRevision restoredPlan, Activity restoredActivity) {
        Objects.requireNonNull(humanId, "装配必须知道装的是谁 —— "
                + "一个不知道归属的 Human 会让两个 agent 共用一条总线");
        Objects.requireNonNull(lifecycle, "装配必须知道她此刻的运行档 —— "
                + "用 ACTIVE 兜底会让一个本该暂停的 agent 在装配那一刻就动起来");
        Objects.requireNonNull(actionFabric, "她必须有一个动作出口 —— "
                + "没有它她的决定会走到一处空实现上, 而那看起来像'她想清楚了但没做'");
        Objects.requireNonNull(clock, "装配必须用那个唯一的仿真时钟 —— "
                + "让计划表调度器自己造一个, 就是这一层出现两个时间轴的第一步");

        // ① 身体。它必须先于总线被造出来, 而且必须被接到总线上 ——
        //    漏掉后一句的后果见类注释那张表的第一行: 它不抛异常、不计失败、不留痕迹。
        Body body = new Body(humanId);

        // ② 总线。她是它的唯一投递口(WorldRuntime.bind 会把它接到世界上)。
        EventFabric fabric = new DefaultEventFabric(humanId, new EventHandlerRegistry());

        // ③ 接线。registerOn 注册两个 tick handler: Body(order 10) 与 ThresholdDetector(order 20)
        //    —— 顺序是有语义的: 后者要在**新鲜的状态**上判断越界。
        //    两者都不订阅任何事件类型, 所以它们只出现在 tick 链上, 不出现在到达路由里:
        //    她不会因为"收到了降温事件"而变冷, 她会冷是因为那条事件在账本上留下了持续影响。
        //    漏掉这一行的唯一症状是"她永远不觉得冷" —— ⑦ 那一行的构造器会当场拦住它。
        body.registerOn(fabric);

        // ④ 生活。它自己造计划表 —— 这是 requireOnePlanBoard 存在的理由, 不是缺陷:
        //    PlanEventPublisher 只接在**这一张**上(见 Life 构造器), 所以 Mind 必须拿同一张。
        //    两个 restored* 走构造参数而不是事后 load —— 装载必须发生在发布器挂上之前,
        //    否则恢复本身会发出一串"她刚刚改了计划"的假事件(见 Life 那个构造器的说明)。
        Life life = new Life(humanId, fabric, PlanningContext.HumanSnapshot::unknown,
                restoredPlan, restoredActivity);

        // ⑤ 关系网 —— 空的。这不是占位, 是今天的真实状态: 见类注释第二段。
        RelationshipGraph relationships = RelationshipGraph.empty();

        // ⑥ 认知。**life.plan() 而不是 new PlanBoard()** ——
        //    传错了不会立刻报错, 但 Human 的构造器会当场拦住(§3.1 的装配守卫),
        //    所以这一行写错会在装配那一刻就炸, 而不是以"她不重排"的形式潜伏几天。
        Mind mind = new Mind(humanId, fabric, life.plan(), relationships,
                new MindDecisionPlanner(), LanguageEngine.silent(), PerceptLexicon.generic());

        // ⑦ 合起来。构造器在这一行做三次检查: 三块部件同属一人、Life 与 Mind 同一张表、
        //    以及 ③ 那一行确实被调过(body.isWiredTo(fabric))。
        Human human = new Human(HumanId.of(humanId), body, life, mind, fabric);

        HumanActor actor = new HumanActor(human, actionFabric, lifecycle);
        PlanSchedulerJob scheduler = new PlanSchedulerJob(clock, life);

        return new Parts(human, fabric, actor, scheduler);
    }

    /**
     * 一个装配好的她 —— 四样东西, 各有一个明确的去向。
     *
     * <h2>为什么同时给 {@code human} 与 {@code actor}, 而不是"给 actor 就够了"</h2>
     * 因为这两样东西的用途<b>不重叠</b>, 而且都必须在装配层手里:
     * <ul>
     *   <li>{@code actor} → 交给 {@code WorldRuntime.bind}, 它是心跳里的执行体;</li>
     *   <li>{@code human} → 交给 {@link LiveHumanRegistry} 与恢复流程。
     *       它<b>不能</b>由 actor 转发出来: {@code HumanActor} 刻意不暴露它持有的那个
     *       {@code Human}(见那里的类注释), 因为一旦暴露, "谁在读她的内部"这个问题
     *       就没有答案了。所以需要一个把两者一起递出去的值对象。</li>
     * </ul>
     *
     * <p>{@code fabric} 也单独给出来, 而不是让调用方写 {@code human.fabric()} ——
     * 后者虽然合法({@code fabric()} 不在 ArchUnit 那条禁令里, 它本来就是给
     * {@code WorldRuntime} 用的), 但在本类里直接递出去少一层"读她的内部"的错觉。
     */
    public record Parts(Human human, EventFabric fabric, HumanActor actor, PlanSchedulerJob scheduler) {

        public Parts {
            Objects.requireNonNull(human, "装配结果必须有一个 Human");
            Objects.requireNonNull(fabric, "装配结果必须有一条总线");
            Objects.requireNonNull(actor, "装配结果必须有一个执行体");
            Objects.requireNonNull(scheduler, "装配结果必须有一个计划表调度器");
        }

        /** 她是谁 —— 与 {@code agent_ownership.human_id} 同源。 */
        public String humanId() {
            return human.id().value();
        }
    }
}
