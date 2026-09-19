package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanId;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.life.Life;
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
 * <h2>它不做恢复</h2>
 * 本类只装配一个<b>空</b>的她 —— 账本、计划表、活动日志都是新的。
 * 把历史装回去是 {@link RecoveryRuntime} 的事(§8.5.6), 而且它<b>必须在本类被调用之前
 * 完成读取</b>: 因为 {@code Life} 自己造计划表({@code this.plan = new PlanBoard()}),
 * 而"一版恢复出来的计划"今天<b>没有</b>装载入口 —— 见 {@code RecoveryRuntime} 的
 * 拒绝规则。把恢复放在装配之后做, 就等于承认"先装一个空白的她, 再把历史补上",
 * 而那正是 §8.6.3 第 7 步说"错了会毁掉数据"的那个顺序。
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
        Life life = new Life(humanId, fabric, PlanningContext.HumanSnapshot::unknown);

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
