package com.luxera.companion.human.mind.decision;

/**
 * V2.2 §3.4.5 —— <b>"怎么说"</b>。LLM 的位置, 而且<b>只有这一个位置</b>。
 *
 * <h2>它为什么在 {@code decision} 包, 而不是 {@code cognition} 包</h2>
 * 因为它的入参是一个 {@link Decision} —— 而 "一个 Decision 已经存在" 这件事
 * 正是本接口存在的全部意义。放在 {@code cognition} 会让 {@code cognition} 与
 * {@code decision} 互相 import(前者要用本接口的类型, 后者要用前者的
 * {@code ReasoningResult}), 而两个包互相依赖之后, 它们就再也没法被单独理解、
 * 单独测试、单独替换。
 *
 * <p>从职责上说它确实是认知的一部分 —— 但<b>依赖方向是设计的一部分</b>,
 * 而把接口放在被它参数化的那一侧, 是消除环的标准做法。
 *
 * <h2>那条顺序纪律 —— 这是本接口存在的原因</h2>
 * §3.4.5 的分工表里写着, 而 V11 Phase 4 已经为它写过一次 javadoc:
 *
 * <blockquote>
 * "以前是先问模型要不要回，再解释它的答案；现在是<b>先有"她决定做什么"这个值，
 * 模型只在已经决定要说之后才被调用</b>。"
 * </blockquote>
 *
 * <p>这个先后顺序不是性能考虑, 而是<b>因果方向</b>的考虑:
 *
 * <pre>{@code
 * // ❌ 先问模型:
 * String reply = llm.ask("她要不要回这条消息?");     // 决定的成因是模型的输出
 * Decision d = Decision.of(...);                    // 决定只是对它的解释
 *
 * // ✅ 先决定, 再措辞:
 * Decision d = decisionEngine.decide(...);          // 决定的成因是可复现的规则
 * String text = language.render(d, situation);      // 模型只负责把已经定下的事说出来
 * }</pre>
 *
 * <p>两者的差别在第一次出故障时会变得非常清楚: 前者里"她为什么回了"的答案是
 * <b>一次不再可复现的模型调用</b>; 后者里答案在那条 {@code Decision} 上,
 * 而模型只影响这句话怎么措辞 —— 措辞错了不影响决定对不对, 重新渲染一次就行。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不判断要不要说话</b>。要不要说由 {@link Decision#needsWording()} 回答。
 *       实现者能在里面返回一个空字符串, 但<b>它无法让一个"不说话的"决定说话</b> ——
 *       因为调用方压根不会调它;</li>
 *   <li><b>不改决定</b>。返回的是字符串, 不是一个新的 {@code Decision}。
 *       让它有改决定的权力, 等于把决定权从确定性引擎手里拿回去 ——
 *       而那正是这一整节要防的事;</li>
 *   <li><b>不发动作</b>。它拿不到总线, 也拿不到输出通道。要说的话作为文本回去,
 *       由调用方装进 {@code ActionCommand}。</li>
 * </ul>
 */
public interface LanguageEngine {

    /**
     * 把"她已经决定要做的事"变成一句人话。
     *
     * <p><b>调用它的前提是: 一个 {@link Decision} 已经存在。</b>
     * 这个前提是类型层面的 —— 没有 Decision 就调不了它。
     */
    String render(Decision decision, String situation);

    /**
     * 这个引擎的名字 —— 进日志与诊断。
     *
     * <p>名字应当说清它的性质, 而不是它的品牌: {@code human/} 里不许出现
     * 具体第三方平台的名字, 而模型供应商也属于这一类 —— 见
     * {@code V22BoundaryArchitectureTest} 的那条守卫。
     */
    String engineId();

    /**
     * 一个<b>什么都不做</b>的语言引擎。
     *
     * <p>它存在有两个用处: 测试(数一数"她被调用了几次"是断言顺序纪律最直接的办法),
     * 以及"这次不回复"的场景 —— 一个 800 毫秒的模型往返换来一个空字符串,
     * 不如一开始就不去。
     *
     * <p>注意它<b>不返回空串</b>, 而是返回一句说明"这里本来该有一句话"的文本。
     * 返回空串会让调用方分不清"引擎说没什么要说的"与"引擎坏了"。
     */
    static LanguageEngine silent() {
        return new LanguageEngine() {
            @Override
            public String render(Decision decision, String situation) {
                return "(未生成措辞: " + decision.reason().narrative() + ")";
            }

            @Override
            public String engineId() {
                return "mind.language.none";
            }
        };
    }
}
