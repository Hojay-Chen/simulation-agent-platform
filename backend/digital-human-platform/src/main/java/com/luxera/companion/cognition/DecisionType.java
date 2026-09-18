package com.luxera.companion.cognition;

/**
 * V11 §11.2 —— <b>她的行动空间</b>。认知的产出是这个, 不是一段文本。
 *
 * <h2>为什么要有这个枚举, 而不能继续用"回复/不回复"</h2>
 * 今天的代码里,"回不回"不是一个决策, 而是一条<b>流程的副作用</b>:
 * {@code AgentRuntime.process} 走了三百多行, 中间有七八个 {@code return},
 * 每一个都意味着"这次不回" —— 但它们不是同一个意思:
 * <pre>
 *   messages.isEmpty()          → 根本没东西可看
 *   pipelineResult.isIgnored()  → 看到了, 不打算理
 *   pipelineResult.isDeferred() → 看到了, 待会儿再说
 *   reply == null || blank      → 打算回, 但没写出来
 *   conflict != null            → 写出来了, 但与事实冲突
 *   !stateVersionGate.tryCommit → 写出来了, 但世界已经变了
 *   first 未通过输出验证          → 写出来了, 但不像人话
 * </pre>
 * 这七种在<b>现象上完全一样</b>(对方都没收到消息), 在<b>语义上完全不同</b>。
 * 真要问"她今天为什么不回我", 今天的系统答不上来 —— 只能去翻 {@code agent_traces}。
 *
 * <p>本枚举把那七种收成<b>一个显式的值</b>, 于是它可以被记录、被统计、被断言。
 *
 * <h2>与已有两套决策词表的关系</h2>
 * <pre>
 *   {@link com.luxera.companion.digitalhuman.decision.PersonDecision}  V10 sealed interface, 5 个值
 *   {@link com.luxera.companion.runtime.pipeline.MessagePipeline.PipelineResult.Outcome}  4 个值
 * </pre>
 * 两者都<b>不删、不改</b>(§25.1 禁止 Big Bang)。本枚举是它们的<b>共同上位</b>:
 * 在 {@link CognitiveDecision#from} 里有单向适配器, 老的 → 新的。
 * 反过来没有适配器, 而且不该有 —— 新的词表比老的宽(它有 READ_MESSAGES / THINK /
 * INITIATE_CONVERSATION 这些老链根本没有的动作), 硬要映射回去只会丢信息。
 *
 * <p>刻意<b>不</b>给出"这个类型要不要回消息"以外的任何语义。
 * 比如没有 {@code isBad()} 或 {@code isSuccess()} —— 不回消息在 V11 里不是失败,
 * 它是一个和"回消息"平权的选择。一旦有人给某个类型加上"不好"的含义,
 * 下一个读代码的人就会去"修"它。
 */
public enum DecisionType {

    /** 什么都不做: 世界里发生了事, 但它与她无关(别人家的消息、她已经知道的事) */
    DO_NOTHING,

    /** 等: 有事要做, 但不是现在(睡着 / 手上有放不下的事) —— 与 DEFER 的区别是它<em>还没读</em> */
    WAIT,

    /** 看着: 注意到了, 但选择不介入(看到了不想理 / 看个热闹) —— 与 DO_NOTHING 的区别是<em>她看到了</em> */
    OBSERVE,

    /** 去读: 决定把消息读进来(把"未读"变成"已读") —— 这是 V11 新增的一步, 老链与感知合在一起 */
    READ_MESSAGES,

    /** 想: 不产生任何外部动作, 只更新她自己的心智(工作台/关注点/情绪) */
    THINK,

    /** 回: 唯一一个会向会话里写入她自己消息的动作之一 */
    REPLY,

    /** 主动开口: 没人找她, 她自己发起(Phase 5 的落点; Phase 4 只把它放进词表) */
    INITIATE_CONVERSATION,

    /** 做事: 在聊天之外动手(改状态、记一笔、排一件事) */
    PERFORM_ACTION,

    /** 押后: 读过了, 现在不回, 之后可能想起来(会留下可复查的痕迹) */
    DEFER,

    /** 记下一件想做未做的事(意图) —— 它是 DEFER 的兄弟: DEFER 是"待会儿回", 它是"别忘了" */
    CREATE_INTENTION;

    /**
     * 这个决策会不会往会话里写一条她自己的消息。
     *
     * <p><b>整个 Phase 4 的行为变化就落在这一个方法上</b>: 在这个方法存在之前,
     * "会不会回"由三百行流程的走向决定; 之后, 由这个判断决定。
     * {@code INITIATE_CONVERSATION} 也算 —— 它也往会话里写消息, 只是写在一个
     * 没有新消息的会话里; 判断"要不要写"的逻辑是一样的。
     */
    public boolean producesOutboundMessage() {
        return this == REPLY || this == INITIATE_CONVERSATION;
    }

    /**
     * 这个决策是不是一个"她动了"的信号 —— 用来看她今天活跃不活跃。
     *
     * <p>注意 {@code OBSERVE} 与 {@code DO_NOTHING} <b>都算不活跃</b>:
     * 从外面看, 她什么都没做。它们在内部有区别(一个看到了、一个没看到),
     * 但"活跃度"这个指标问的是外部可见的行为, 混进内部状态只会让数字失去意义。
     */
    public boolean isActive() {
        return producesOutboundMessage() || this == PERFORM_ACTION || this == THINK;
    }

    /** 这个决策是不是"以后再说" —— 需要留一个可复查的痕迹, 否则它就变成了静默丢消息。 */
    public boolean needsFollowUp() {
        return this == DEFER || this == WAIT || this == CREATE_INTENTION;
    }
}
