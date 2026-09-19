package com.luxera.companion.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §8.6.7 —— 启动时那一行摘要的内容。
 *
 * <h2>为什么它是一个值对象, 而不是"装配方法里拼一个字符串"</h2>
 * 因为这一行有四个数, 而它们**来自四个不同的地方**(注册表、世界与人的数量、
 * 恢复过程、配置)。若拼字符串的代码住在装配方法里, 那么:
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
 * <h2>四个数各回答一个运维问题</h2>
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
 * </table>
 *
 * <p><b>{@code opaqueEntries} 是这四个里唯一一个"不该出现在生产日志里"的数。</b>
 * 其余三个正常时各有一个合理的取值, 只有它在健康状态下必须是 <b>0</b> ——
 * 所以它被单独放在 {@link Recovery} 里, 而不是与"类型数"平铺在一起。
 * 一个平铺的列表会让读者以为它们是同一类量, 而它们不是:
 * 那三个是<b>描述</b>, 这一个是<b>警告</b>。
 */
public record StartupSummary(
        int typeCount,
        int namespaceCount,
        List<String> conflicts,
        int worldCount,
        int humanCount,
        Recovery recovery,
        long tickMs) {

    public StartupSummary {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
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
                + " / 恢复: 账本 " + recovery.ledgerEntries() + " 条("
                + recovery.opaqueEntries() + " 条替身)、计划 " + recovery.planItems() + " 项、"
                + "世界历史 " + recovery.historyDays() + " 天"
                + " / tick 间隔 " + tickMs + "ms";
    }

    /**
     * 场景里有没有人 —— <b>它是这一整行摘要里唯一一个"为假就说明有事"的字段</b>。
     *
     * <h2>为什么"零个人"值得单独一个方法, 而不是让调用方自己比 {@code humanCount == 0}</h2>
     * 因为那个比较会让每个调用方各自决定"零是不是要紧"。而事实是: 一个座位数为零的运行时,
     * 心跳照常跳、日志照常打"装配完成"、计数照常增长 —— <b>它与一个正常的运行时在
     * 任何一处计数上都没有区别</b>, 唯一的差别就是没有人。所以"没有人"这件事必须是一个
     * 有名字的判断, 而不是一个散在各处的整数比较。
     *
     * <p>它与 {@link Recovery#hasOpaqueEntries()} 是同一种东西: 那一个是"读不回来",
     * 这一个是"没有人" —— 两个都不是异常, 两个都必须被喊出来。
     */
    public boolean hasHumans() {
        return humanCount > 0;
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
