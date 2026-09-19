package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.EventFabric;
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
 *     <td><b>做不到 —— 于是拒绝入座</b></td>
 *     <td>{@code Life} 自己造 {@code PlanBoard} 且没有装载入口, 见下面的拒绝规则</td>
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
 * <h2>拒绝规则: 读不回来的历史, 不许用一张空白表顶替</h2>
 *
 * 这是本类里唯一一个<b>有主张</b>的决定, 而它来自 §8.5.6 的第一条硬约束
 * ("读不回来的行不许丢")。那条约束在原文里是针对账本的(用 {@code OpaqueEffect} 留位),
 * 而本类把它<b>提到了整份历史的粒度</b>上, 因为计划表那一侧的失效方式不一样:
 *
 * <pre>
 *   账本读不回来一条  → 用替身占位, 数值还对, 只是解释丢了(OpaqueEffect)
 *   计划表读得回来却没有入口 → 她会被装成**一张空表**。库里那一版计划还在,
 *                        而"她今天打算做什么"这个问题里, 库和内存给的是两个答案
 * </pre>
 *
 * <p>第二种更坏, 因为替身至少承认自己是替身。所以遇到它时本类的选择是
 * <b>拒绝给这个 agent 一个座位</b>, 并打一条 WARN 说出三个数: 谁、为什么、
 * 以及补上它需要哪个入口。她不会被驱动, 于是也不会有任何一行数据被写坏。
 *
 * <p><b>今天这条规则不会响:</b> {@code PlanStore.appendRevision} 与
 * {@code ActivityStore.append} 都没有生产调用者(只有测试), 所以那两张表是空的。
 * 它不是为今天写的, 它是为<b>下一个把计划生产者接上的人</b>写的 ——
 * 那个人会在第一次启动时立刻看到这条 WARN, 而不是在几天后从"她怎么什么都不做"
 * 倒推回来。这正是 §8.5.8 的"先有读面"在装配层的用法。
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

            // ① 账本。restore 是宽容读 + 重放: 读不回来的行会以 OpaqueEffect 的形式
            //    留在结果里, 而不是被丢掉(§8.5.6 的第一条硬约束)。
            ContinuousEffectLedger restored = ledgers.restore(humanId, now);

            HumanAssembly.Parts parts = HumanAssembly.assemble(
                    humanId, registry.lifecycleOf(humanId), actionFabric, clock);

            Counted counted = refill(restored, parts.fabric(), now);
            ledgerEntries += counted.entries();
            opaqueEntries += counted.opaque();

            // 座位登记把两件事一起做完: 接进心跳, 并把她的总线接到世界上。
            // 顺序不能反 —— 先登记处后座位的话, 控制台会读到一个
            // "她在这台机器上"而世界上还没有她那条线的瞬间。
            runtime.bind(parts.actor(), parts.scheduler());
            live.accept(humanId, parts.human());
            seated++;
        }

        report = new Report(seated, skipped, refusals.size(), ledgerEntries, opaqueEntries);
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
     * "她会被装成一张空表, 因为 Life 没有装载入口"才有。
     */
    private Optional<String> whyUnrebuildable(String humanId) {
        if (plans.latest(humanId).isPresent()) {
            return Optional.of("库里有一版计划表, 而 Life 没有'装载一版计划'的入口"
                    + "(它自己 new PlanBoard, 没有 loader) —— 装进来会是一张**空表**, "
                    + "于是'她今天打算做什么'在库里与内存里是两个答案。"
                    + "补它需要在 Life 上开一个装载入口(并让 PlanEventPublisher 用**替换**"
                    + "而不是**追加**的方式接上, 否则恢复本身会发出一串假的计划变更事件)");
        }
        if (activities.runningActivity(humanId).isPresent()) {
            return Optional.of("库里有一条正在进行的活动, 而 Life 没有'装载一条进行中的活动'的入口"
                    + " —— 装进来她会以为自己此刻什么都没在做, 于是同一段时间会被做两次");
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

    /** 一轮恢复的账 —— 五个数, 各回答一个运维问题。 */
    public record Report(int seated, int skipped, int refused, long ledgerEntries, long opaqueEntries) {

        public Report {
            if (seated < 0 || skipped < 0 || refused < 0 || ledgerEntries < 0 || opaqueEntries < 0) {
                throw new IllegalArgumentException("恢复的计数不能是负数: 入座 " + seated
                        + ", 已坐 " + skipped + ", 拒绝 " + refused
                        + ", 账本 " + ledgerEntries + ", 替身 " + opaqueEntries);
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
                    + "账本 " + ledgerEntries + " 条(其中 " + opaqueEntries + " 条替身)";
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
