package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.persistence.store.ActivityStore;
import com.luxera.companion.persistence.store.EffectLedgerStore;
import com.luxera.companion.persistence.store.OpaqueEffect;
import com.luxera.companion.persistence.store.PlanStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §8.5.6 —— <b>把历史装回她身上, 然后才允许心跳开始跳</b>。
 *
 * <h2>为什么这个类必须在 tick 壳之前跑完</h2>
 * §8.6.3 把第 7 步与第 8 步的先后称为"这一层唯一一个<b>错了会毁掉数据</b>的顺序",
 * 理由是具体的: 恢复做的是"读一段世界历史", 而它读的那些行必须是<b>静止的</b>。
 * 心跳先起来的话, 恢复读到的是一个<b>移动的目标</b> —— 她一边被推进, 一边被重建,
 * 而两边都认为自己是对的。
 *
 * <p>这个顺序在本层<b>不是一条注释, 是一条 DI 边</b>:
 * {@code SimulationConfiguration.simulationTick(...)} 把本类列为构造依赖,
 * 于是 Spring 必须先造出本类(而本类的 {@code recover()} 在造它的那一刻就跑完了),
 * 才可能造出心跳壳。有人把那条参数删掉的话, {@code SimulationConfigurationTest}
 * 会红 —— 见那里的 {@code 恢复必须在心跳之前} 那一条。
 *
 * <h2>§8.5.6 的五步, 今天各自落在哪里</h2>
 * <table border="1">
 *   <tr><th>步骤</th><th>今天</th><th>为什么</th></tr>
 *   <tr>
 *     <td>① Body ← 账本</td>
 *     <td><b>做了</b></td>
 *     <td>{@link EffectLedgerStore#restore} 给出一个装好的账本, 本类把它灌进
 *         她的 {@code fabric.effects()}</td>
 *   </tr>
 *   <tr>
 *     <td>② Life ← 计划表 + 活动</td>
 *     <td><b>做了</b></td>
 *     <td>{@code Life} 的重载构造器收这两个东西, 而装载发生在发布器挂上之前 ——
 *         所以恢复<b>不会</b>发出一串"她刚刚改了计划"的假事件。
 *         见下面"为什么装载走构造参数"那一段</td>
 *   </tr>
 *   <tr>
 *     <td>③ Mind ← 记忆与关系网</td>
 *     <td><b>做不到 —— 但没有产生谎言</b></td>
 *     <td>记忆今天<b>根本没有持久化</b>(全仓只有 {@code InMemoryMemoryStore}),
 *         所以"库里有她记不住的事"这个处境无法出现; 关系网同理。一条不存在的
 *         历史不需要恢复, 也<b>不该</b>被伪装成恢复过。这一条是缺的,
 *         但它缺得诚实 —— 与 ② 不同, 它今天不会让任何人读到一份错的状态</td>
 *   </tr>
 *   <tr>
 *     <td>④ World ← 未投递的世界事件</td>
 *     <td><b>做不到</b></td>
 *     <td>{@code WorldEventStore.append} <b>全仓没有一个调用者</b>(那条路还没有生产者),
 *         而且 {@code World} <b>没有 id</b> —— 恢复要按 {@code worldId} 查, 而那个值
 *         今天没有任何一处定义。所以这里不是"漏了一条查询", 是"这一条路的两端
 *         都还没定下来"。见下面的 {@link #unreplayedWorldEvents()}</td>
 *   </tr>
 *   <tr>
 *     <td>⑤ 运行档</td>
 *     <td><b>做了</b></td>
 *     <td>从花名册取, 经 {@code AgentRegistry.lifecycleOf} 走 §3.6.5 的兜底 ——
 *         于是"认不出来的那一个"与"心跳会跑的那一个"是同一个判断</td>
 *   </tr>
 * </table>
 *
 * <h2>装载走构造参数, 不走一个 load 方法 —— 这是本类最要紧的一处形状</h2>
 * 恢复出来的一版计划与那一条进行中的活动, 是通过 {@code Life} 的<b>构造参数</b>
 * 进去的, 而不是"装好一个空白的她, 再调一个方法把历史塞进去"。这个区别不是风格:
 *
 * <pre>
 *   先装载, 后挂发布器  → 装载那一刻还没有观察者, 它不产生任何事件。这是一次恢复
 *   先挂发布器, 后装载  → 那一次装载被当成**一次真实的计划变更**发出去。
 *                        于是每次重启, 世界都会收到一遍她生前的全部
 *                        plan.revision-created.v1 + plan.item-scheduled.v1,
 *                        而行为分析会把它们算成"她刚刚做的决定"
 * </pre>
 *
 * <p>第二种的后果不是"多了几条日志": 库里那一版计划与那些假事件之间<b>没有任何矛盾</b>
 * 能让人发现这件事 —— 它们看起来完全正常, 而"她今天重排了几次"这个数从此是错的。
 * 把装载放进构造器之后, 这个顺序由代码的书写顺序保证, 而不是由调用方的纪律保证;
 * 而且"后补历史"这个动作<b>根本不存在</b>, 所以它不可能被用错时刻。
 *
 * <p>这与 §8.6.3 第 7 步(恢复必须先于心跳)是同一个道理的两个粒度: 世界那一层,
 * 心跳不能先动; 她这一层, 发布器不能先挂。
 *
 * <h2>拒绝规则: 读回来的东西<b>互相矛盾</b>时, 不许挑一个信</h2>
 *
 * 这是本类里唯一一个<b>有主张</b>的决定, 而它来自 §8.5.6 的第一条硬约束
 * ("读不回来的行不许丢")。那条约束在原文里是针对账本的(用 {@code OpaqueEffect} 留位),
 * 而本类把它<b>提到了整份历史的粒度</b>上:
 *
 * <pre>
 *   账本读不回来一条  → 用替身占位, 数值还对, 只是解释丢了(OpaqueEffect)
 *   计划说她在做某事, 而活动表说她没有 → 挑一半装进去, 两种挑法都会制造假的过去
 * </pre>
 *
 * <p>第二种更坏, 因为替身至少承认自己是替身。所以遇到它时本类的选择是
 * <b>拒绝给这个 agent 一个座位</b>, 并打一条 WARN 说清是<b>哪两行</b>对不上。
 * 她不会被驱动, 于是也不会有任何一行数据被写坏。
 *
 * <p><b>这条规则刚刚被缩小过一次</b>, 而那件事本身值得记下来: 它的第一版拒绝
 * 两类人(库里有计划的、库里有进行中活动的), 理由是"{@code Life} 没有装载入口" ——
 * 那是本仓自己的功能缺口, 而补上那个缺口之后这两条拒绝就该消失。
 * 它们消失了, 而<b>留下的那一条拒绝的是另一种东西</b>: 不是"我装不了",
 * 是"这两行对不上, 我不知道该信哪一行"。见 {@link #whyUnrebuildable}。
 *
 * <p><b>今天这条规则只在数据被写坏时才会响:</b> {@code PlanStore.appendRevision} 与
 * {@code ActivityStore.append} 都没有生产调用者(只有测试), 所以那两张表是空的。
 * 它真正会响的场景是"写完计划表就崩了" —— 那正是"两次写入之间没有事务"的形状。
 *
 * <h2>为什么它可重复调用</h2>
 * {@link #recover()} 跳过已经在座位表上的人, 于是它的语义是
 * <b>"把还没坐下的都坐下"</b>, 而不是"从零开始装一遍"。这让它今天可以被启动流程调用,
 * 明天可以被动运行的一条"她被 provisioning 出来了"的事件再调一次 ——
 * 而不需要第二种装配路径。重复调用不会重复装人({@code WorldRuntime.bind} 会拒绝重复座位)。
 */
@Slf4j
public final class RecoveryRuntime {

    private final WorldRuntime runtime;
    private final AgentRegistry registry;
    private final EffectLedgerStore ledgers;
    private final PlanStore plans;
    private final ActivityStore activities;
    private final SimulationClock clock;
    private final ActionFabric actionFabric;
    private final LiveHumanRegistry live;

    private Report report;

    /** 上一轮被拒绝入座的她 —— 由 {@link #recover()} 覆盖, 只增不减地报给读面。 */
    private List<Refusal> refusals = List.of();

    public RecoveryRuntime(WorldRuntime runtime,
                           AgentRegistry registry,
                           EffectLedgerStore ledgers,
                           PlanStore plans,
                           ActivityStore activities,
                           SimulationClock clock,
                           ActionFabric actionFabric,
                           LiveHumanRegistry live) {
        this.runtime = Objects.requireNonNull(runtime, "恢复必须有一个座位表可谈");
        this.registry = Objects.requireNonNull(registry, "恢复必须有一份花名册 —— "
                + "没有它, '该装谁'就只能靠调用方报一个名单, 而那份名单没有来源");
        this.ledgers = Objects.requireNonNull(ledgers, "恢复必须能读账本");
        this.plans = Objects.requireNonNull(plans, "恢复必须能读计划表 —— "
                + "它正是'她今天打算做什么'那一问的答案所在");
        this.activities = Objects.requireNonNull(activities, "恢复必须能读活动日志");
        this.clock = Objects.requireNonNull(clock, "恢复不许读墙上时钟 —— "
                + "'她昏迷了多久'要算在仿真时间轴上");
        this.actionFabric = Objects.requireNonNull(actionFabric, "恢复必须有一个动作出口, "
                + "因为装出来的是一个完整的她, 不是一个只读的档案");
        this.live = Objects.requireNonNull(live, "恢复必须把手装出来的她登记进去 —— "
                + "否则控制台查不到她, 而'她刚被装进来'与'装配失败了'会长得一样");
    }

    /**
     * 把花名册上"应当跑而还没坐下"的人装进来。
     *
     * <h2>它读的每一个时刻都来自仿真时钟</h2>
     * {@code now} 只取一次, 并用在整轮恢复的全部判断上("她还没走到的时刻"
     * 由此成为一个对所有人一致的概念)。一轮里取两次时钟会让
     * "她的账本按 12:00 结算、而计划按 12:01 判断" —— 而那是一个查不出来的差。
     *
     * @return 这一轮的结果 —— 同时也是 {@link #report()} 之后会给出的那一份
     */
    public Report recover() {
        Instant now = clock.now();
        List<AgentProfileView> runnable = registry.runnable();
        // humanIds() 给的是 List(座位表按登记顺序), 而这里只要 membership 判断。
        // 不做转换是刻意的: 这个列表的长度是"这台机器上有几个她", 不是"全平台有几个 agent"
        // —— 线性查找在这个量级上比多一层集合的转换更容易读, 也不构成性能问题。
        List<String> alreadySeated = runtime.humanIds();

        int seated = 0;
        int skipped = 0;
        long ledgerEntries = 0;
        long opaqueEntries = 0;
        int planItems = 0;
        List<Refusal> refusals = new ArrayList<>();

        for (AgentProfileView view : runnable) {
            String humanId = view.humanId();

            // 已经在座位上的跳过而不是重装 —— 见类注释"为什么它可重复调用"。
            // 重装会撞上 WorldRuntime.bind 的重复检查, 而那个异常的措辞
            // 会把一个正常的"我又跑了一次恢复"说成装配错误。
            if (alreadySeated.contains(humanId)) {
                skipped++;
                continue;
            }

            Optional<String> blocked = whyUnrebuildable(humanId);
            if (blocked.isPresent()) {
                refusals.add(new Refusal(humanId, blocked.get()));
                log.warn("[Sim] 拒绝给 {} 一个座位: {}", humanId, blocked.get());
                continue;
            }

            // ① Body ← 账本。restore 是宽容读 + 重放: 读不回来的行会以 OpaqueEffect 的形式
            //    留在结果里, 而不是被丢掉(§8.5.6 的第一条硬约束)。
            ContinuousEffectLedger restoredLedger = ledgers.restore(humanId, now);

            // ② Life ← 计划表 + 活动。两个都读、两个都传 —— 见 HumanAssembly 那个重载
            //    的说明: 只给其中一半不会报错, 只会让她"以为自己在做一件不存在的事"
            //    或"计划表上有一项正在做而她不记得在做什么"。
            PlanRevision restoredPlan = plans.latest(humanId).orElse(null);
            Activity restoredActivity = activities.runningActivity(humanId).orElse(null);

            HumanAssembly.Parts parts = HumanAssembly.assemble(
                    humanId, registry.lifecycleOf(humanId), actionFabric, clock,
                    restoredPlan, restoredActivity);

            Counted counted = refill(restoredLedger, parts.fabric(), now);
            ledgerEntries += counted.entries();
            opaqueEntries += counted.opaque();

            // 她这一版计划有几项 —— 这一格从"写死的零"变成了真的数。
            // 注意这里数的是**项数**而不是"她今天有几件事要做": 那一版里可能有
            // 已经做完的、被取消的、被推翻的项, 它们同样是"她带着的历史"。
            // 理由与 refill 里那条"条数含已失效的"完全一致。
            if (restoredPlan != null) {
                planItems += restoredPlan.size();
            }

            // 座位登记把两件事一起做完: 接进心跳, 并把她的总线接到世界上。
            // 顺序不能反 —— 先登记处后座位的话, 控制台会读到一个
            // "她在这台机器上"而世界上还没有她那条线的瞬间。
            runtime.bind(parts.actor(), parts.scheduler());
            live.accept(humanId, parts.human());
            seated++;
        }

        // historyDays 仍然是 0, 而且它**不是**"还没接"。它问的是"补了多少天的
        // **世界**历史"(§8.5.6 ④: 读 [lastSeenAt, now) 区间里的 world_event),
        // 而那条路今天两端都没定下来(World 没有 id, WorldEventStore.append 没有生产者)。
        // 拿恢复出来的计划表的 createdAt 去填这一格是一个**看起来很像**的替代品 ——
        // 而那正是本类最不该做的事: 它会把"世界历史"这个词静默地换成"计划历史",
        // 于是下一个读这一行日志的人会以为 ④ 已经做了。
        report = new Report(seated, skipped, refusals.size(),
                ledgerEntries, opaqueEntries, planItems);
        this.refusals = List.copyOf(refusals);

        if (runnable.isEmpty()) {
            log.info("[Sim] 恢复: 花名册上没有应当跑的 agent —— 这个世界还没有人。"
                    + "这不是故障, 是'还没有人'(见 StartupSummary.rosterWarning)");
        } else {
            log.info("[Sim] 恢复完成: {} —— {}", report.describe(), runtime.describe());
        }
        if (opaqueEntries > 0) {
            log.warn("[Sim] 账本里有 {} 条只能以替身重建(OpaqueEffect) —— 数值可能是对的, "
                    + "但'她为什么冷'这类问题会永远失去答案。健康状态是 0 条", opaqueEntries);
        }
        return report;
    }

    /**
     * 她有没有一份<b>读得回来却装不回去</b>的历史。
     *
     * <p>返回的是"为什么装不回去"而不是一个布尔 —— 因为这条路径上唯一的消费者是
     * 一条 WARN 与一个运维面板, 而"她被拒绝了"这句话本身没有可操作性;
     * "她会带着半份历史继续活, 因为 X"才有。
     *
     * <h2>这条规则刚被缩小过一次, 而它剩下的每一条都<b>不是</b>"功能还没做"</h2>
     * 第一版在这里拒绝两类人 —— 凡库里有一版计划表的、凡有一条进行中的活动的。
     * 那时它拒绝的理由是"{@code Life} 没有装载入口", 也就是<b>本仓自己的功能缺口</b>。
     * 那个缺口补上之后({@code Life} 的重载构造器), 这两条拒绝就都该消失了 ——
     * <b>而它们没有全部消失</b>: 留下来的这一条拒绝的是另一种东西。
     *
     * <pre>
     *   过去的拒绝: "这个我装不了"        → 补一个入口就没了(已补)
     *   现在的拒绝: "这两行对不上, 我不知道该信哪一行"  → 补入口解决不了
     * </pre>
     *
     * <h2>剩下这一条: 半份历史</h2>
     * 计划表那一版里有一项 {@code ACTIVE}(她正在做), 而活动表里没有那一条 {@code RUNNING}
     * 的行 —— 或者反过来。两种都是"库里那两行互相矛盾", 而它们各自都无法被单独装进去:
     *
     * <pre>
     *   只装计划 → 她以为自己在做那件事, 而 Life.current 是空的
     *              → begin(...) 不再拒绝 → 同一段时间被做两次
     *   只装活动 → Life.current 指向一件计划表上不存在的项
     *              → 她做完时 concludeCurrent 找不到那一项
     * </pre>
     *
     * <p>而<b>装哪一半都是猜</b>: 猜错的代价是一段时间被做两次(或被丢掉),
     * 而这两种错在事后都无法从数据上看出来。所以这里的选择还是拒绝 ——
     * 与第一版同一个态度(不许用一张空白表顶替), 只是理由从"我做不到"
     * 换成了"这行数据不自洽"。
     *
     * <h2>这一条今天会响的情况</h2>
     * 只有当有人<b>手工改库</b>、或者 {@code ActivityStore.conclude} 与
     * {@code PlanStore.appendRevision} 的写入顺序被打断(写完计划就崩了)时才出现。
     * 后一种是真实的 —— 它正是"两次写入之间没有事务"的形状 ——
     * 而它必须被看见, 不是被猜过去。
     */
    private Optional<String> whyUnrebuildable(String humanId) {
        Optional<PlanRevision> plan = plans.latest(humanId);
        Optional<Activity> running = activities.runningActivity(humanId);

        // 按 lifecycle 判, 而不是 activeAt(someInstant): 后者问的是"某个时刻她归哪一项",
        // 而这里问的是"有没有一项正处于 ACTIVE 这个状态" —— 后者不需要一个时刻,
        // 也不该为了问它去发明一个(Instant.MAX 会在窗口算术里溢出)。
        boolean planSaysSheIsDoingSomething = plan
                .map(revision -> revision.items().stream()
                        .anyMatch(item -> item.lifecycle() == PlanLifecycle.ACTIVE))
                .orElse(false);
        boolean activitySaysSheIs = running.isPresent();

        if (planSaysSheIsDoingSomething && !activitySaysSheIs) {
            return Optional.of("库里那一版计划里有项是**正在做**, 而活动表里没有对应的"
                    + "进行中活动 —— 两行对不上。装计划那一半她会以为自己在做那件事"
                    + "(于是同一段时间会被做两次), 装活动那一半什么也装不上。"
                    + "这不是'缺一个入口', 是**数据不自洽**: 请先确认哪一行是真的"
                    + "(通常是一次写库被打断留下的), 而不是让恢复去猜");
        }
        if (activitySaysSheIs && !planSaysSheIsDoingSomething) {
            return Optional.of("库里有一条**进行中**的活动, 而那一版计划里没有任何一项是"
                    + "正在做 —— 两行对不上。装活动那一半会让她的 current 指向一件"
                    + "计划表上不存在的项(做完时 concludeCurrent 找不到它), "
                    + "装计划那一半等于把这条活动丢掉。这不是'缺一个入口', "
                    + "是**数据不自洽**: 请先确认哪一行是真的, 而不是让恢复去猜");
        }
        return Optional.empty();
    }

    /**
     * 把恢复出来的账本灌进她那条总线上的账本 —— <b>这一步是"装回身上"的字面意思</b>。
     *
     * <h2>为什么是"重放"而不是"换一个账本"</h2>
     * 因为 {@code DefaultEventFabric} 在自己的构造器里 {@code new} 了一个
     * {@code ContinuousEffectLedger}(它是 {@code final} 字段), 而 {@code Body}
     * 每拍结算的是<b>那一个</b>。全仓没有任何一处能把一个现成的账本塞进 fabric ——
     * 于是唯一一条公开的路是 {@code book(...)}, 也就是按原样再入一遍账。
     *
     * <p>这条重放<b>不改变金额</b>: 每一条都带着它自己的 {@code bookedAt} 与
     * {@code expiresAt}(由 {@code resolveExpiry} 从事件本身推出来), 所以
     * "她当时有多冷"与"她多久之后会回到正常"两个数都不依赖重放发生的时刻。
     * 重放发生在 {@code now} 之后的同一拍里会被结算, 而那一拍的结果与恢复前
     * 库里那条影响本来该产生的结果一致。
     *
     * <h2>替身不重放, 但它们<b>不被丢掉</b></h2>
     * {@link OpaqueEffect} 是"这条读不回来"的占位, 把它当普通影响重放会<b>凭空发明
     * 一个数值</b>(它的 magnitude 是读不出来时填的)。所以这里只数不灌。数出来的那个
     * 数字有一条完整的读面: 它进 {@link Report#opaqueEntries()}, 再进
     * {@code StartupSummary.Recovery.hasOpaqueEntries()}, 最后进启动那一行日志。
     * <b>不丢</b>指的是"不许没有痕迹", 不是"必须伪装成正常数据"。
     *
     * <h2>为什么按 {@code history()} 的顺序重放</h2>
     * 因为账本的"替换"语义只在<b>同一 channel + 同一 cancellationKey</b> 内成立,
     * 而"谁替换了谁"是一个时序判断 —— 打乱顺序重放会让被替换的那条反过来遮住新的那条。
     */
    private Counted refill(ContinuousEffectLedger restored, EventFabric fabric, Instant now) {
        long entries = 0;
        long opaque = 0;
        for (ContinuousEffectLedger.EffectRecord record : restored.history()) {
            if (record.event() instanceof OpaqueEffect) {
                opaque++;
                continue;
            }
            // 条数按"账本上有多少条"算(含已失效的), 灌进去的只有此刻还活着的那几条 ——
            // 见 StartupSummary.Recovery 的 @param: "含已失效的"问的是"她还带着多少历史",
            // 不是"此刻有几条影响在生效"。两个数都不是这里能合并的。
            entries++;
            if (record.active(now)) {
                fabric.effects().book(record.event(), record.bookedAt());
            }
        }
        return new Counted(entries, opaque);
    }

    /**
     * 这一轮恢复的结果 —— <b>没跑过就抛, 不给零</b>。
     *
     * <p>"账本 0 条"与"恢复根本没有跑"是两件必须分得开的事(§8.5.6 的第一条硬约束)。
     * 返回一份零会让这两件事在启动日志上印成同一句话 —— 而那正是
     * {@code StartupSummary.Recovery} 的注释里点名的、最难查的一类故障。
     * 所以这里的选择是当场抛, 并把"该怎么修"写在消息里。
     *
     * @throws IllegalStateException {@link #recover()} 还没有跑过
     */
    public Report report() {
        if (report == null) {
            throw new IllegalStateException("恢复还没有跑过, 所以没有结果可报 —— "
                    + "这不是'恢复了 0 条', 而是'恢复没做'。装配层必须在读这一份结果之前"
                    + "调用 recover()(见 §8.6.3 第 7 步: 它在 tick 壳之前)");
        }
        return report;
    }

    /** 被拒绝入座的每一个她, 以及各自的理由 —— 控制台要展开的就是这一张表。 */
    public List<Refusal> refusals() {
        return refusals;
    }

    /**
     * §8.5.6 ④ 今天读不到的条数 —— <b>恒为"没有读"</b>, 而不是"读了 0 条"。
     *
     * <p>这个方法存在的理由与 §8.5.8 里那批 {@code lateItems}/{@code skippedTicks}
     * 完全相同: <b>先有读面, 再有页面</b>。世界还没有 id、{@code world_event}
     * 还没有生产者, 所以"她昏迷期间世界发生了什么"这件事今天答不出来 ——
     * 而答不出来必须有一个方法说出它答不出来, 否则下一个读这一层的人会以为
     * "没有未投递事件"。
     *
     * <p>返回 {@code OptionalLong} 而不是 {@code 0}: {@code 0} 是一个断言
     * ("那个区间里没有事件"), 而这里要表达的是"这个问题今天没有被问过"。
     */
    public java.util.OptionalLong unreplayedWorldEvents() {
        return java.util.OptionalLong.empty();
    }

    public String describe() {
        return report == null ? "恢复: 还没跑" : "恢复: " + report.describe();
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * 一轮恢复的账 —— 六个数, 各回答一个运维问题。
     *
     * @param seated        这一轮装进来几个她。与 {@code skipped} 分开: 那个是"我上一轮
     *                      已经装过了", 而它不该被算成一次成功
     * @param skipped       花名册上该跑、而已经在座位上的
     * @param refused       读得回来却装不回去的 —— 见 {@link #whyUnrebuildable}
     * @param ledgerEntries 补回来的账本条数, <b>含已失效的</b>: 这一格问的是
     *                      "她带着多少历史", 不是"此刻有几条影响在生效"
     * @param opaqueEntries 其中只能以替身重建的条数。它<b>不在</b>{@code ledgerEntries} 之外
     *                      —— 是它的子集
     * @param planItems     补回来的计划表里一共有几项, 同样<b>含</b>完结的项。
     *                      它是所有人之和(三个她各十项就是 30), 不是"她有十项"。
     *                      这一格从"写死的零"变成了真的数 —— 它此前是零, 因为
     *                      计划表根本没有装载入口, 于是"补回来几项"这个问题没被问过
     */
    public record Report(int seated, int skipped, int refused,
                         long ledgerEntries, long opaqueEntries, int planItems) {

        public Report {
            if (seated < 0 || skipped < 0 || refused < 0
                    || ledgerEntries < 0 || opaqueEntries < 0 || planItems < 0) {
                throw new IllegalArgumentException("恢复的计数不能是负数: 入座 " + seated
                        + ", 已坐 " + skipped + ", 拒绝 " + refused
                        + ", 账本 " + ledgerEntries + ", 替身 " + opaqueEntries
                        + ", 计划 " + planItems);
            }
            if (opaqueEntries > ledgerEntries) {
                throw new IllegalArgumentException("替身数(" + opaqueEntries
                        + ")不可能超过账本条数(" + ledgerEntries + ") —— 替身是账本的子集。"
                        + "这个不等式不成立说明数的人把两个集合算混了");
            }
        }

        public boolean hasOpaqueEntries() {
            return opaqueEntries > 0;
        }

        public boolean hasRefusals() {
            return refused > 0;
        }

        public String describe() {
            return "入座 " + seated + " 个, 已在座 " + skipped + " 个, 拒绝 " + refused + " 个, "
                    + "账本 " + ledgerEntries + " 条(其中 " + opaqueEntries + " 条替身), "
                    + "计划 " + planItems + " 项";
        }
    }

    /**
     * 一个<b>被拒绝入座</b>的 agent, 以及为什么。
     *
     * <p>做成一个带理由的记录而不是一个 {@code humanId} 的清单, 是因为
     * "她被拒绝了"这句话在运维面上没有可操作性 —— 有可操作性的是
     * "她会被装成一张空表, 而补它需要 Life 上开一个装载入口"。
     */
    public record Refusal(String humanId, String reason) {

        public Refusal {
            Objects.requireNonNull(humanId, "拒绝记录必须知道拒绝的是谁");
            Objects.requireNonNull(reason, "拒绝必须带理由 —— "
                    + "一条不带理由的拒绝会让读它的人只能去翻代码");
        }
    }

    /** 一次重放的两个数 —— 只在本类内部用, 所以是私有的。 */
    private record Counted(long entries, long opaque) {
    }
}
