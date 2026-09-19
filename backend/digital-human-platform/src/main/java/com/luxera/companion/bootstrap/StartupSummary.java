package com.luxera.companion.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §8.6.7 —— 启动时那一行摘要的内容。
 *
 * <h2>为什么它是一个值对象, 而不是"装配方法里拼一个字符串"</h2>
 * 因为这一行有十来个字段, 而它们**来自五个不同的地方**(类型注册表、世界与人的数量、
 * 花名册、恢复过程、配置)。若拼字符串的代码住在装配方法里, 那么:
 * <ul>
 *   <li>它<b>测不了</b> —— 要断言"替身数是 0"就得起一个完整上下文;</li>
 *   <li>它会随着装配方法的增长而增长, 而装配方法本来就是最容易变长的那种方法;</li>
 *   <li>更要紧的: "哪些数该被报出来"这件事会散在拼接代码里, 于是加一个新数的人
 *       不会去想"它有没有对应的运维问题"。</li>
 * </ul>
 * 做成值对象之后, {@link #describe()} 可以在一个只造这一个对象的测试里断言,
 * 而"字段是不是都在回答一个运维问题"这件事在声明处就看得见。
 *
 * <h2>为什么它属于装配层, 而不属于任何一个领域对象</h2>
 * 因为它是**装配结果**的陈述, 而不是领域事实。领域对象不知道"一共有多少类型",
 * 它只知道自己那几种 —— 让 {@code World} 或 {@code Human} 去数一个全局的数,
 * 就是让它们认识一个它们不该认识的全局。这条与 §3.4.2 的边界是同一条理由,
 * 只是方向相反: 那边是"她不许认识世界", 这边是"世界不许认识全局装配"。
 *
 * <h2>每一个数各回答一个运维问题</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>它回答的问题</th><th>它非正常时的样子</th></tr>
 *   <tr>
 *     <td>{@link #typeCount} / {@link #namespaceCount}</td>
 *     <td>三方应用接进来了吗</td>
 *     <td>数字比预期小 → 某个 {@code registerTypes} 没被装配层调到,
 *         于是它那几种事件会以 {@code _untyped} 落库(见 §8.6.2)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #conflicts}</td>
 *     <td>有没有两个类抢同一个类型名</td>
 *     <td>非空 → 必须打 WARN: 后注册的那个会遮住先注册的,
 *         而"哪一个赢"取决于装配顺序 —— 一个纯粹的实现细节泄漏</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Recovery#opaqueEntries}</td>
 *     <td>有没有东西<b>读不回来</b></td>
 *     <td>非零 → 有一批历史只能以替身重建。数值上可能是对的(见
 *         {@code OpaqueEffect}), 但"她为什么冷"这类问题会永远失去答案</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #tickMs}</td>
 *     <td>仿真跑多快</td>
 *     <td>与配置不符 → 生产与测试跑的不是同一套参数</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #roster} / {@link #humanCount}</td>
 *     <td>"库里说该跑的人"与"座位上真有的人"是不是同一批</td>
 *     <td>两者不等 → 有 agent 应当跑却没有座位。见
 *         {@link #unseatedAgents()} 与 {@link #rosterWarning()}</td>
 *   </tr>
 * </table>
 *
 * <p><b>{@code opaqueEntries} 是这几个里唯一一个"不该出现在生产日志里"的数。</b>
 * 其余几个正常时各有一个合理的取值, 只有它在健康状态下必须是 <b>0</b> ——
 * 所以它被单独放在 {@link Recovery} 里, 而不是与"类型数"平铺在一起。
 * 一个平铺的列表会让读者以为它们是同一类量, 而它们不是:
 * 那几个是<b>描述</b>, 这一个是<b>警告</b>。
 *
 * <h2>{@link #humanCount} 一个人都回答不了"是不是有人"</h2>
 *
 * 这一点值得写在类型上, 因为它是一个反复出现的误读: {@code humanCount} 是<b>这台机器上
 * 坐了人</b>, 而不是<b>库里有没有人</b>。那个数在两种完全不同的处境下都是 0:
 *
 * <pre>
 *   库里一个 agent 都没有        → 座位 0。这是"还没有人", 正常;
 *   库里有 3 个, 都该跑, 座位 0  → 这也是 0。这是"有人而没被装进来",
 *                                  §8.6.3 的第 5/6 步还没落地。
 * </pre>
 *
 * <p>所以 {@link #roster} 不是"多报一个数", 它是让 {@code humanCount} 那个 0
 * <b>有意义</b>所需要的另一半。把两者放在同一个 record 里 —— 而不是让调用方
 * 各自去别处查一次花名册 —— 是让"必须一起看"这件事在类型上成立。
 */
public record StartupSummary(
        int typeCount,
        int namespaceCount,
        List<String> conflicts,
        int worldCount,
        int humanCount,
        Roster roster,
        Recovery recovery,
        long tickMs) {

    public StartupSummary {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        Objects.requireNonNull(roster, "花名册计数不能为空 —— "
                + "'库里有没有 agent'与'座位上有没有人'是两件事, "
                + "省略前者就会让 seatCount 那个 0 无法解释(见类注释)");
        Objects.requireNonNull(recovery, "恢复摘要不能为空 —— "
                + "哪怕一个字都没恢复, 那也是一个必须被说出来的事实(见 Recovery 的注释)");
        if (typeCount < 0 || worldCount < 0 || humanCount < 0) {
            throw new IllegalArgumentException(
                    "计数不能是负数: 类型 " + typeCount + ", 世界 " + worldCount
                            + ", 人 " + humanCount + " —— 负数只可能来自一处数错了的地方,"
                            + " 而它会让这一整行摘要失去可信度");
        }
    }

    /**
     * 花名册那一侧的两个数 —— {@code agent_ownership} 里有什么, 以及其中有多少
     * <b>应当跑</b>。
     *
     * <h2>为什么只报两个数, 而不把整份档案塞进来</h2>
     * 因为启动这一行回答的是"装配对不对", 不是"她是谁"。逐个人名、账号、
     * 运行档字面值属于控制台那一页({@code AgentProfileView.describe()})——
     * 把它们塞进一行日志, 得到的是一行没人读得完的日志, 而它还会随着人数增长。
     *
     * <h2>{@code runnable} 与 {@code registered} 是两个口径, 不是一个的过滤</h2>
     * {@code runnable} <b>不是</b> {@code registered} 的子集计数那样简单 ——
     * 它含被软删与被暂停之外的全部, 而且判法必须是
     * {@code AgentLifecycle.of(那一列)} 而不是 {@code 那一列 == "active"}
     * （§3.6.5 的兜底: 认不出来的运行档<b>要跑</b>）。
     * 那条规则的具体后果写在 {@code AgentRegistry} 的类注释里, 这里不重复 ——
     * 重复一次就多一个走样的副本。
     *
     * @param registered 在册的 agent 数(含被软删的)
     * @param runnable   其中<b>现在应当跑</b>的 —— 装配层的第 5/6 步要的就是这一批
     */
    public record Roster(long registered, long runnable) {

        public Roster {
            if (registered < 0 || runnable < 0) {
                throw new IllegalArgumentException("花名册计数不能是负数: 在册 " + registered
                        + ", 应当跑 " + runnable);
            }
            if (runnable > registered) {
                throw new IllegalArgumentException("应当跑的(" + runnable
                        + ")不可能多于在册的(" + registered
                        + ") —— 前者是后者的一个子集。这个不等式不成立, 说明两个数"
                        + "不是从同一份名单上数出来的");
            }
        }

        /** 库里一个 agent 都没有 —— "还没有人", 而不是"人没被装进来"。 */
        public boolean empty() {
            return registered == 0;
        }
    }

    /**
     * <b>应当跑却没有座位</b>的 agent 数 —— 本类里唯一那个"两个来源对不上"的数。
     *
     * <p>它的两个输入来自两条完全不同的路径: {@code roster.runnable()} 是
     * <b>库</b>说的({@code agent_ownership} 那一列), {@code humanCount} 是
     * <b>这台机器</b>说的({@link com.luxera.companion.runtime.WorldRuntime#seatCount()})。
     * 前者是意图, 后者是事实。
     * 两者相等时装配是完整的; 不等时有一批 agent 在库里写着"跑", 而世界上没有她。
     *
     * <p>取 {@code max(0, ...)} 是因为反方向的不等(<b>座位比应当跑的多</b>)是另一个
     * 问题 —— 她已经被暂停/软删了, 而座位还在 —— 那不该被算成"负数个没座位的人"。
     * 今天它不需要单独的警告: 那一条路径的写侧
     * ({@code AgentRegistry.setLifecycle} 之后要调 {@code WorldRuntime.applyLifecycle})
     * 还没接上, 所以此刻这个方向<b>必然</b>为 0。
     */
    public long unseatedAgents() {
        return Math.max(0L, roster.runnable() - humanCount);
    }

    /**
     * 恢复过程报回来的四个数。
     *
     * <h2>为什么"什么都没恢复"也必须是一个有值的对象</h2>
     * 因为"账本 0 条"与"恢复根本没有跑"是两件必须分得开的事(§8.5.6 的第一条硬约束
     * 就是"恢复必须在 tick 壳之前跑完")。一个可空的 {@code Recovery} 会让这两件事
     * 在日志上都印成"没有恢复数据"—— 而那正是最难查的一类: 分不清"没有"与"没做"。
     *
     * @param ledgerEntries 账本恢复了多少条(含已失效的)
     * @param opaqueEntries 其中有多少条只能以替身重建 —— <b>健康状态必须是 0</b>
     * @param planItems     计划表恢复了多少项(跨全部版本之后当前那一版)
     * @param historyDays   世界历史覆盖了多少天(不是"多少条" —— 条数与时间跨度
     *                      是两个不同的问题, 而"她记得多久以前的事"问的是后者)
     */
    public record Recovery(long ledgerEntries, long opaqueEntries, int planItems, int historyDays) {

        public Recovery {
            if (ledgerEntries < 0 || opaqueEntries < 0 || planItems < 0 || historyDays < 0) {
                throw new IllegalArgumentException("恢复计数不能是负数: 账本 " + ledgerEntries
                        + ", 替身 " + opaqueEntries + ", 计划 " + planItems
                        + ", 历史天数 " + historyDays);
            }
            if (opaqueEntries > ledgerEntries) {
                throw new IllegalArgumentException("替身数(" + opaqueEntries
                        + ")不可能超过账本总数(" + ledgerEntries
                        + ") —— 替身是账本的子集。这个不等式不成立说明数的人把两个不同的集合算混了");
            }
        }

        /** 有没有读不回来的东西 —— 这是本对象唯一一个"健康状态为假"的判据。 */
        public boolean hasOpaqueEntries() {
            return opaqueEntries > 0;
        }
    }

    /**
     * §8.6.7 那一行摘要。
     *
     * <p>格式刻意保持一行(可以折行显示, 但内容是一句): 日志里的一行可以被
     * {@code grep} 到, 而一个多行的"摘要块"会被别的日志插进去, 于是再也拼不起来。
     */
    public String describe() {
        return "[Sim] 装配完成: " + typeCount + " 个类型(" + namespaceCount + " 个命名空间)"
                + " / " + worldCount + " 个 World / " + humanCount + " 个 Human"
                + " / 在册 " + roster.registered() + " 个 agent(应当跑 " + roster.runnable() + " 个)"
                + " / 恢复: 账本 " + recovery.ledgerEntries() + " 条("
                + recovery.opaqueEntries() + " 条替身)、计划 " + recovery.planItems() + " 项、"
                + "世界历史 " + recovery.historyDays() + " 天"
                + " / tick 间隔 " + tickMs + "ms";
    }

    /**
     * "人是不是没被装进来"的告警 —— 没有话要说时返回空, 形状与
     * {@link #conflictWarning()} 一致, 于是调用方写
     * {@code summary.rosterWarning().ifPresent(log::warn)} 就是全部。
     *
     * <h2>为什么"零个人"必须是<b>一个有名字的判断</b></h2>
     *
     * 因为它不是一个整数比较那么直白。一个座位数为零的运行时, 心跳照常跳、
     * 日志照常打"装配完成"、计数照常增长 —— <b>它与一个正常的运行时在任何一处
     * 计数上都没有区别</b>, 唯一的差别就是没有人。所以这件事必须有人说出声,
     * 而不是指望每个调用方自己记得比一下。
     *
     * <h2>但 {@code humanCount == 0} 这个比较本身是<b>错的</b></h2>
     *
     * 这是本方法存在、而不只是把那个比较包一层的原因。那个比较在两种处境下都成立,
     * 而它们要做的事完全不同:
     *
     * <pre>
     *   ① 在册 0 个                     → 库里就是没人。世界是空的, 这没有任何问题:
     *                                     平台还没建出第一个 agent。打 INFO 都说多了;
     *   ② 在册 3 个, 应当跑 3 个, 座位 0 → 有人, 而且写着该跑, 而世界上没有她们。
     *                                     这是装配层的缺口(§8.6.3 第 5/6 步),
     *                                     必须 WARN —— 因为它不是一个"还没到时候"
     *                                     的状态, 是一个"有人而没被装进来"的事实。
     * </pre>
     *
     * <p>把它们混成一句"一个 Human 都没有"的后果是具体的: 第 ② 种处境会被读成
     * 第 ① 种, 于是"有 3 个人已经建好了、只是还没被装进来"这件事在日志里
     * <b>长得和"还没建过人"一模一样</b>。
     *
     * <h2>反过来, 全部被暂停时它<b>不该</b>响</h2>
     *
     * 在册 3 个、应当跑 0 个、座位 0 —— 这时候空座位表是<b>对的</b>, 不是缺口。
     * 所以判据是 {@link #unseatedAgents()} 而不是 {@code humanCount == 0}:
     * 前者问的是"该坐的人有没有座位", 后者问的是"有没有座位"。
     * 一个每次全员暂停都喊一遍的告警, 会在第三次之后被所有人忽略。
     */
    public Optional<String> rosterWarning() {
        if (roster.empty()) {
            return Optional.empty();
        }
        long unseated = unseatedAgents();
        if (unseated == 0) {
            return Optional.empty();
        }
        return Optional.of("[Sim] 库里在册 " + roster.registered() + " 个 agent, 其中 "
                + roster.runnable() + " 个应当跑, 而座位表上只有 " + humanCount
                + " 个 —— 有 " + unseated + " 个 agent 没有被装进来。"
                + "这不是「还没有人」, 是「有人而没被装配进来」: 心跳会照常跑, "
                + "而这一天不存在。见 SimulationConfiguration 关于 §8.6.3 第 5/6 步的说明");
    }

    /**
     * 类型名冲突的告警 —— 没有冲突时返回空, 于是调用方写
     * {@code summary.conflictWarning().ifPresent(log::warn)} 就是全部。
     *
     * <h2>为什么不把它并进 {@link #describe()}</h2>
     * 因为"装配完成"这一行是 INFO, 而冲突是 WARN —— 两个不同的日志级别
     * 不能挤在同一行里。合并的代价是运维面按级别过滤时, 要么漏掉冲突,
     * 要么把正常的装配完成也当成告警捞出来。
     */
    public Optional<String> conflictWarning() {
        if (conflicts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("[Sim] 有 " + conflicts.size() + " 个类型名冲突: " + conflicts
                + " —— 后注册的会遮住先注册的, 而'哪一个赢'取决于装配顺序。"
                + "这不是运行时错误, 是两种事件被当成了同一种");
    }
}
