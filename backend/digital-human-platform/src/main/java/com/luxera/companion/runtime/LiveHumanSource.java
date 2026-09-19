package com.luxera.companion.runtime;

import com.luxera.companion.human.HumanContext;

/**
 * V2.2 §8.5.7 —— <b>"她此刻在不在这台机器上"的那个来源</b>。
 *
 * <h2>为什么需要这个接口, 而不是让投影器自己去找 {@code Human}</h2>
 *
 * 因为找法<b>只有装配层知道</b>。{@code HumanActor} 刻意不暴露它持有的 {@code Human}
 * —— 它的类注释写着:
 *
 * <blockquote>
 * 本类因此<b>不提供</b> {@code human()}、{@code context()} 这类"看一眼她"的方法
 * —— 要看她走 {@code Human.context()}, 那是 §3.1.4 给所有外部读者开的门。
 * 想读到 {@code Human} 本体的话, <b>装配代码自己留着引用</b>（它本来就要用它来构造这个 actor）。
 * </blockquote>
 *
 * <p>所以"活的那些 {@code Human} 在哪"这个问题的答案是"在装配层手里, 不在运行时里"。
 * 这个接口就是把那句话变成一个可注入的缝: {@code AgentRegistry} 与投影器
 * <b>不认识</b> {@code WorldRuntime}, 也不认识任何一张座位表 —— 它们只问一句
 * "她有快照吗"。
 *
 * <h2>为什么返回 {@code HumanContext} 而不是 {@code Human}</h2>
 *
 * 两个理由, 一条比一条硬:
 * <ol>
 *   <li><b>方向。</b>{@code HumanContext} 是只读快照(§3.1.4), 所以"投影器改了她的状态"
 *       在类型上不可能。把 {@code Human} 交出去, 某一天就会有人为了
 *       "让控制台能顺手修一下她的保暖值"而调一个方法 —— 而那条写入<b>绕过账本</b>,
 *       于是"她为什么冷"再也答不出来;</li>
 *   <li><b>一条真的会红的规则。</b>{@code V22BoundaryArchitectureTest} 里有一条
 *       ArchUnit 规则: 除了 {@code human/} 自己与 {@code runtime.HumanActor},
 *       任何类调 {@code Human.body()} / {@code life()} / {@code mind()} 都算违规。
 *       投影器住在 {@code runtime}, 所以它<b>只能</b>走 {@code context()} ——
 *       这不是约定, 是编译产物上的检查。这个接口的返回类型与那条规则是同一件事的
 *       两种写法。</li>
 * </ol>
 *
 * <h2>为什么"不在这台机器上"用 {@code null} 表达</h2>
 *
 * 这是本接口唯一一处需要写清楚的地方, 因为另一种做法看起来很自然:
 * 返回一个默认的 / 空的 {@code HumanContext}。那样做的后果是界面上她"活着",
 * 而身体各项是 0 —— 而 <b>0 与"她真的冷到零度"在屏幕上长得一模一样</b>。
 *
 * <p>{@code null} 在这里是一个<b>有名字的</b>答案("她不在这台机器上"),
 * 而不是一个缺失的值, 所以 {@link AgentProfileView#materialized()} 专门给了它一个名字。
 * 这与 {@code SimulationConfiguration} 打印"0 个 Human"是同一条纪律 ——
 * 它必须说出来, 而不是渲染成一个看起来正常的零。
 *
 * <h2>今天的实现是什么</h2>
 *
 * {@link #NONE} —— 因为 {@code SimulationConfiguration} 的第 5/6 步
 * (把每个 agent 物化成 {@code Human} 聚合、装进座位表)还没落地。它<b>不是</b>一个
 * "暂时这样、以后再补"的空实现: 它就此刻的事实回答"她不在这台机器上",
 * 而那是真的。第 5/6 步落地时, 装配层提供自己的实现盖过它,
 * 本接口与投影器<b>一行都不用改</b>。
 *
 * <p>这也正是这个接口为什么现在就要存在: 装配层补上那一步时, 要改的只有装配层。
 */
@FunctionalInterface
public interface LiveHumanSource {

    /**
     * 拿她此刻的只读快照 —— 或者 {@code null}, 表示她不在这台机器上。
     *
     * @param humanId {@code hum_xxx}。与 {@code HumanId.value()} 同形, 但这里刻意收
     *                {@code String} 而不是 {@link com.luxera.companion.human.HumanId}:
     *                本接口的实现要拿它去查一张 {@code String} 主键的表
     *                （{@code agent_ownership.human_id}）, 而 {@code HumanId} 是仿真侧的
     *                值对象 —— 让装配层在这里做一次转换, 好过让本接口认识它
     * @return 快照, 或 {@code null}
     */
    HumanContext contextOf(String humanId);

    /**
     * "这台机器上一个人都没有" —— 今天的事实。
     *
     * <p>它不是一个占位符, 而是一个<b>正确的</b>实现: 在装配层物化出第一批
     * {@code Human} 之前, 对每一个 {@code humanId} 它都诚实地回答"不在这台机器上"。
     */
    LiveHumanSource NONE = humanId -> null;
}
