package com.luxera.companion.runtime;

import com.luxera.companion.human.HumanContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.2 §3.6.7 / §8.5.7 —— <b>她的档案: 一份只读快照, 没有任何 setter</b>。
 *
 * <h2>它为什么是 {@code record} 而不是一棵带 setter 的 DTO</h2>
 *
 * 旧实现的 {@code persona.Persona} 是一棵嵌套 DTO, 由 {@code CompanionService}
 * 手工从好几张表拼出来, 而它<b>有 setter</b>。§3.6.7 把读模型从存储降级为投影时,
 * 记下的理由是这一类 bug:
 *
 * <blockquote>
 * 手工拼的读模型与写模型分叉, 是这类系统里最典型的一类 bug —— 界面上显示"她心情不错",
 * 而她的情绪状态里其实是"疲惫", 两个值来自两条不同的计算路径, 各自都对, 合起来错。
 * </blockquote>
 *
 * <p>把它定成投影之后, 读模型<b>没有自己的存储</b>, 于是"分叉"在结构上不可能发生。
 * 而 {@code record} 是这条结论的机械保证: 没有 setter, 所以没有"谁改了一下视图"
 * 这件事; 每个字段都来自一次现场投影, 每次读都是此刻的她。
 *
 * <h2>三个 ID 在字段名上就分得开（§3.6.3）</h2>
 * <pre>
 *   humanId          hum_xxx    仿真世界里的那个人
 *   ownerUserId      user_xxx   平台用户 —— <b>不是</b>聊天账号
 *   accounts[].chatAccountId  acc_xxx / agent_xxx  聊天平台上的账号
 *   accounts[].personId       （无前缀）           聊天平台上的"人"
 * </pre>
 * 四个都是 {@code String}, 所以用错一个在类型上完全合法。字段名里带着那个前缀的
 * 意思, 是让"把聊天账号传进了 ownerUserId"这件事<b>在代码评审时看得出来</b> ——
 * 而它的症状是"控制台显示她属于一个不存在的人", 库里的 id 却看起来完全正常。
 *
 * <h2>{@code lifecycleRaw} 与 {@code lifecycle} 为什么是两个字段</h2>
 *
 * 这是本视图上唯一一处"看起来冗余"的地方, 而它不是冗余 —— 它是 §3.6.5 那条读侧兜底
 * 规则在界面上的**回声**, 而少了它就会出现一个查不出来的故障:
 *
 * <pre>
 *   agent_ownership.lifecycle = "pausd"     （有人打错了, 或者写了一个我们没定义的值）
 *
 *   AgentLifecycle.of("pausd") = ACTIVE     （兜底: 只有字面 paused 才停 —— 刻意往
 *                                            危险的方向兜, 因为"误判成停"会让 agent 变哑巴）
 *   于是: 她在跑。账单在涨。而控制台上那个开关显示"运行中"。
 * </pre>
 *
 * <p>三件事各自都对, 合起来是一个没人能解释的现场: 运维说"我明明按了暂停",
 * 代码说"库里不是 paused", 而库里那个值<b>没有任何地方显示出来</b>。
 * 所以这里同时给出: {@link #lifecycleRaw}（那一列的字面值, 打错也能看见）、
 * {@link #lifecycle}（运行时真正会照做的那一档）、以及
 * {@link #lifecycleDrifted()}（两者对不上 —— 也就是"这一列有字, 而它不是我认识的"),
 * 好让控制台能把"库里写着 pausd, 而她在跑"这句话直接说出来。
 *
 * <h2>{@code context} 可以为 {@code null} —— 而这是本视图最重要的一条语义</h2>
 *
 * 它表示<b>她此刻不在这个进程里</b>: 世界还没被装配起来、或者她还没被装进座位表。
 * 这不是"她没有身体", 而是"这台机器上没有她的身体"。
 *
 * <p>两种处理这一情形的方式, 而只有一种是对的:
 * <ul>
 *   <li>给一个空的/默认的 {@code HumanContext} —— 界面上看起来她活着, 身体各项是 0。
 *       <b>这是错的</b>: 零值与"她真的冷到零度"在屏幕上长得一模一样;</li>
 *   <li>给 {@code null}, 并让 {@link #materialized()} 说不 —— 界面于是只能显示
 *       "她此刻不在这台机器上"。这一句是<b>真的</b>。</li>
 * </ul>
 *
 * <p>这与 {@code SimulationConfiguration} 里那句"一个'看起来装了人'的装配层
 * 会让人以为她已经活着, 而她的座位上其实一个人都没有"是同一条纪律:
 * <b>不要把'还没有'渲染成'是零'。</b>
 *
 * @param humanId           {@code hum_xxx} —— 仿真身份（§3.6.3）
 * @param displayName       她的名字。可空: {@code human} 行上就没有这一列时可空,
 *                          而"还没起名字"与"叫空字符串"是两件事
 * @param ownerUserId       {@code user_xxx} —— 平台用户, 不是聊天账号
 * @param lifecycleRaw      {@code agent_ownership.lifecycle} 那一列的<b>字面值</b>
 * @param lifecycle         {@code AgentLifecycle.wire()} —— 运行时真正会照做的那一档
 * @param paused            她要不要跑认知 —— 由 {@link #lifecycle} 决定, 不由 {@link #lifecycleRaw}
 * @param alive             她还活着吗 —— 由 {@code deleted_at} 决定, 与运行档是两个问题
 * @param running           {@code alive && !paused}。这个合取只能由调用方自己写出来,
 *                          本类的 {@link #running} 也只是把它写了一遍 —— 见
 *                          {@code AgentOwnershipRecord} 关于"不要包成 running()"的说明
 * @param createdByClientId 三方客户端 id; {@code null} = 平台自建
 * @param platformCreated   {@code createdByClientId} 为空的<b>名字</b>
 * @param createdAt         归属行写入时刻（墙上时钟 —— 归属不参与仿真回放）
 * @param updatedAt         最后一次改动（转让、暂停、恢复都会推它）
 * @param deletedAt         {@code null} = 她还活着
 * @param accounts          她的聊天账号绑定。可能为空 —— 空表示"她还没有任何账号",
 *                          而那不是错误状态: provisioning 与绑定是两步
 * @param context           现场投影的只读快照; {@code null} = 她不在这台机器上
 */
public record AgentProfileView(
        String humanId,
        String displayName,
        String ownerUserId,
        String lifecycleRaw,
        String lifecycle,
        boolean paused,
        boolean alive,
        boolean running,
        String createdByClientId,
        boolean platformCreated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        List<AccountRef> accounts,
        HumanContext context) {

    /**
     * 她在聊天平台上的一个账号, 以及那个账号背后的人。
     *
     * <h2>为什么 {@code chatAccountId} 与 {@code personId} 是两个字段而不是一个</h2>
     * 因为它们是两个不同的东西, 而这一点在本平台的领域里是有具体后果的:
     * <pre>
     *   chatAccountId   acc_xxx / agent_xxx   聊天平台上的一个"账号"
     *   personId        （无前缀）            聊天平台上的一个"人"
     * </pre>
     * 一个真人可以在聊天平台上有多个账号（换过号、工作号与私人号), 而<b>她认的是人</b>:
     * 关系网、记忆、称呼都挂在 {@code personId} 上。把两者合成一个字段,
     * 症状是"换了个号的熟人被她当成陌生人"—— 而库里的每一行看起来都是对的。
     *
     * @param bindReason 为什么绑的（§7.2 的 {@code bind_reason}）
     * @param boundAt    绑定发生的<b>仿真时刻</b> —— 这一个参与回放, 与归属行上的
     *                   墙上时钟不同（账号是她在世界里的一段关系, 归属是平台的账）
     * @param bootstrap  这一条是不是某次批量引导建出来的 —— 见
     *                   {@code ConversationAccountBindingRecord.bootstrap()}
     */
    public record AccountRef(String chatAccountId,
                             String personId,
                             String bindReason,
                             Instant boundAt,
                             boolean bootstrap) {
    }

    /**
     * 她此刻在不在这台机器上 —— 也就是"这份档案里那个 {@code context} 是不是空的"。
     *
     * <p>给它一个名字, 而不是让调用处写 {@code view.context() != null}:
     * 后者在一堆字段访问里几乎不会被注意到, 而它决定的是这一页能不能画她的身体。
     * 一个把"她不在这台机器上"渲染成"她的状态是空的"的界面, 是本类存在的理由里
     * 反复出现的那一条错误的又一个实例。
     */
    public boolean materialized() {
        return context != null;
    }

    /**
     * 那一列里写着一个运行时不认识的值吗。
     *
     * <p>{@code true} 的含义是具体的: <b>库里那一列有字, 而它不是 {@code active}
     * 也不是 {@code paused}</b>。此时 {@link #paused} 是 {@code false}（她会照常跑,
     * 这是 §3.6.5 刻意的兜底方向), 而 {@link #lifecycleRaw} 里就是那个认不出来的字。
     *
     * <p>{@code null} 与空白<b>不算</b>漂移: 那一列是 {@code nullable = false},
     * 而一行还没有运行档的记录含义是"刚建出来、还没被暂停过", 也就是 {@code ACTIVE}
     * —— 那是正常状态, 报成漂移只会制造噪音。
     */
    public boolean lifecycleDrifted() {
        if (lifecycleRaw == null || lifecycleRaw.isBlank()) {
            return false;
        }
        return !lifecycleRaw.trim().equalsIgnoreCase(lifecycle);
    }

    /**
     * 一行摘要 —— 日志与排查用。
     *
     * <p>它<b>刻意不包含</b>身体/生活/心里的任何数: 那三样要给人看就得画出来
     * （走 {@link #context}), 塞进一行字符串只会让人开始依赖这行字符串,
     * 而它无论如何都是一个会过期的副本。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("agent[").append(humanId).append("] ")
                .append(displayName == null ? "(无名)" : displayName)
                .append(" 属于 ").append(ownerUserId)
                .append(" (")
                .append(lifecycleRaw == null ? "未写运行档" : lifecycleRaw)
                .append(lifecycleDrifted() ? " → 兜底为 " + lifecycle : "")
                .append(")");
        if (!alive) {
            sb.append(", 已于 ").append(deletedAt).append(" 软删");
        }
        sb.append(platformCreated ? " [平台自建]" : " [由 " + createdByClientId + " 创建]");
        sb.append(", ").append(accounts.size()).append(" 个聊天账号");
        sb.append(materialized() ? ", 在这台机器的座位表上" : ", 不在这台机器上");
        return sb.toString();
    }
}
