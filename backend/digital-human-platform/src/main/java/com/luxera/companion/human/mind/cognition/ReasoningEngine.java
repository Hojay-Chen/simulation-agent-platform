package com.luxera.companion.human.mind.cognition;

/**
 * V2.2 §3.4.5 —— <b>认知引擎: 理解、推理、提出候选</b>。
 *
 * <h2>LLM 就是它的一个实现 —— 但只是"一个"</h2>
 * §3.4.5 给了这张分工表, 它是本接口存在的全部理由:
 *
 * <table border="1">
 *   <tr><th>环节</th><th>谁做</th></tr>
 *   <tr><td><b>决定做什么</b></td><td>确定性引擎({@code MindDecisionPlanner})</td></tr>
 *   <tr><td><b>怎么说</b></td><td>LLM —— <b>只在"已经决定要说"之后才被调用</b></td></tr>
 *   <tr><td><b>提出候选计划</b></td><td>LLM</td></tr>
 *   <tr><td><b>校验计划排不排得下</b></td><td>确定性引擎({@code PlanValidator})</td></tr>
 * </table>
 *
 * <p>所以本接口是<b>开放</b>的: 规则引擎、LLM、将来的别的什么东西都可以实现它。
 * 而它<b>不是</b>自由的 —— 它只能产出候选({@link ReasoningResult}),
 * 不能产出动作。这条限制写在这里, 而不是靠约定:
 *
 * <pre>{@code
 * // ❌ 如果 ReasoningEngine 能直接发动作, 就会出现这段代码:
 * public ReasoningResult reason(ReasoningContext ctx) {
 *     actionFabric.send(command);     // 「她想了一下就发出去了」
 *     return ReasoningResult.nothing(engineId(), "顺手发了");
 * }
 * // ✅ 现在它做不到 —— 参数里没有总线, 返回值里没有动作。
 * }</pre>
 *
 * <p>第二种写法的问题不是"不好看", 而是<b>她把一件事做了, 但行为分析里没有任何
 * 一条"她决定这么做"的记录</b> —— 那正是这个平台存在的意义所在。
 *
 * <h2>实现者必须遵守的三条</h2>
 * <ol>
 *   <li><b>不许读系统时钟</b>。要时刻就从 {@link ReasoningContext#now()} 拿。
 *       一个自己看表实现的引擎会让回放出来的"她当时的想法"与当初不同;</li>
 *   <li><b>不许有副作用</b>。同一个 context 问两次必须得到同一个答案(对确定性实现而言),
 *       而"顺手改了一下她的记忆"会让这条性质失效, 且失效得很难发现;</li>
 *   <li><b>不许抛异常当作"她想不出来"</b>。想不出来是<b>正常结果</b>, 返回
 *       {@link ReasoningResult#nothing}。抛异常会让一个正常的空白时刻变成一次故障,
 *       而运维会开始忽略这个异常 —— 那时真正的故障也一起被忽略了。</li>
 * </ol>
 */
public interface ReasoningEngine {

    /**
     * 想一下。
     *
     * <p>返回值里装的是<b>候选</b>, 不是决定 —— 见 {@link ReasoningResult} 的说明。
     */
    ReasoningResult reason(ReasoningContext context);

    /**
     * 这个引擎的名字 —— 进日志、进诊断面板, 也是排查"她今天怎么这么怪"的第一个线索。
     *
     * <p>名字应当说清它的性质(是规则还是模型), 而不是它的品牌。
     * 这与 {@code human/} 的"不许出现具体第三方平台名字"是同一条纪律。
     */
    String engineId();

    /**
     * 这个引擎是不是纯规则的 —— 没有抽签、没有模型、没有网络。
     *
     * <p>它有一个具体用途: 回放。一次确定性引擎产出的结果可以被断言成
     * "她当时<b>一定</b>会这么想"; 而一个非确定性引擎的产出只能被记录,
     * 不能被断言。测试与验收标准必须能区分这两种断言 ——
     * 把它们混起来, 会让一套只在当时跑得通的测试看起来是稳定的。
     */
    default boolean deterministic() {
        return false;
    }
}
