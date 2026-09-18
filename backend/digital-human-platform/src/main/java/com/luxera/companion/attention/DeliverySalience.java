package com.luxera.companion.attention;

/**
 * V11 §2.2.2 —— <b>不读正文也能算出的消息显著性</b>。
 *
 * <h2>它替代了什么</h2>
 * 今天"她注意到没有"这个问题是这么回答的:
 *
 * <pre>
 *   decisionText = 全部消息正文拼起来
 *     → EmotionAgent.execute(..., decisionText, ...)   ← 读完并做情绪评估
 *     → salience = 0.3 + warmth*0.3 + (hurt+anger)*0.3 ← 显著性来自情绪
 *     → attentionService.compute(..., salience)
 *     → noticeProb >= 阈值 ? 注意到 : 没注意到
 * </pre>
 *
 * 也就是说: <b>"她有没有注意到"是在正文已经被读完、并被情绪评估之后才决定的</b>。
 * 那么"没注意到"就不可能是一个真实的门 —— 内容已经在她手里了, 后面所有的
 * "她在忙 / 没看见 / 已读不回"都只是已经知道内容之后找的说法。
 *
 * <p>设计文档指认的那件事(消息内容被过早读取)指的就是这里。
 *
 * <h2>它怎么做到不读正文</h2>
 * 显著性只用<b>送达那一刻就已经成立的事实</b>:
 *
 * <table>
 *   <tr><td>连发几条</td><td>三条连着发来, 比一条更值得抬头看一眼 —— 不需要读内容</td></tr>
 *   <tr><td>关系</td><td>亲密的人发来的, 她更敏感(既有代码里的 relWeight 已是这个意思)</td></tr>
 *   <tr><td>关系深浅</td><td>刚认识时会认真看, 处久了会懈怠 —— 真人如此</td></tr>
 * </table>
 *
 * <h2>边界: 这里<b>不算</b>"她的处境"</h2>
 * 睡着、勿扰、手机在包里 —— 这些<b>看起来</b>该让显著性打折, 但这里刻意不打:
 * {@link AttentionService#compute} 已经用 {@code taskAttention}(SLEEP 档 0.95)和
 * {@code phoneNotificationFactor}(dnd 档 0.0)表达了它们。两边都打折就是<b>双罚</b>:
 * 一个深夜的勿扰消息会被罚两次, 于是"她没看见"变得既无法解释也无法调参 ——
 * 你改任何一边都只能看到一半的效果。
 *
 * <p>所以分工是死的:
 * <ul>
 *   <li><b>本类 = 这条消息本身有多显眼</b>(消息与关系的属性)</li>
 *   <li><b>{@link AttentionService} = 她此刻的处境能不能接收到</b>(她的属性)</li>
 * </ul>
 *
 * <h2>它<b>丢掉了</b>什么 —— 必须说清楚</h2>
 * 今天有一件事是靠读正文做的, 这里做不到: <b>"追问词"检测</b>
 * ({@code beingUrged}: 正文里出现"你怎么不回""在吗在吗"之类)。失去它是有代价的 ——
 * 她会对一个明显在催她的人无动于衷。
 *
 * <p>这个代价是<b>故意接受</b>的, 不是没想到, 因为正确的修法不是"再读一点正文":
 * "这条消息在催我"是<b>聊天平台知道、而 agent 平台不该靠读正文去猜</b>的事实 ——
 * 它有发送频率、有会话上下文、有未回复时长。正确做法是让聊天平台在送达时给出一个
 * 显式的紧急度信号(契约变更), 而不是让 agent 把正文读进来自己判断。
 * 在那之前, {@link Signals#burstSize} 是一个诚实的近似: 连发本身就是"在催"最明显的
 * 外部特征, 而它恰好不需要读内容。
 *
 * <h2>两个今天恒为默认值的入参</h2>
 * {@link Signals#urgencyHint} 与 {@link Signals#minutesSinceReply} 今天由
 * {@code DeliverySignals} 恒填 {@code 0} / {@link #NEVER_REPLIED_MINUTES}, 因为
 * 聊天平台在送达事件里<b>还没有</b>提供它们。留着而不是删掉, 是因为模型形状本身是规格:
 * 契约补上 {@code urgency} 与 {@code lastAgentReplyAt} 两个字段之后, 这里只需改一行
 * 接线, 而不是重新设计显著性。<b>它们是有测试覆盖的活代码, 不是装饰</b> ——
 * 见 {@code DeliverySalienceTest}。
 *
 * <h2>它是纯函数</h2>
 * 没有依赖、没有副作用、没有时钟。这样它才能被逐条断言, 也才能在 shadow 模式下
 * 与旧链的判定并排跑而不影响任何东西。
 */
public final class DeliverySalience {

    private DeliverySalience() {
    }

    /**
     * 送达那一刻成立的、<b>与正文无关</b>的事实。
     *
     * <p>刻意<b>不含</b> sleep / 勿扰 / 手机位置 —— 那些是她的处境, 归 {@link AttentionService}。
     * 见类注释的"边界"一节。
     *
     * @param burstSize          这次送达包含几条消息(连发 → 更大)
     * @param minutesSinceReply  她上一次在这个会话里说话是几分钟前。今天恒为
     *                           {@link #NEVER_REPLIED_MINUTES}, 见类注释
     * @param messageCount       这段关系里累计说过多少句(刚认识 vs 处久了)
     * @param intimacy           关系亲密度 0..1
     * @param affection          关系好感度 0..1
     * @param urgencyHint        聊天平台给的显式紧急度 0..1;<b>不知道就传 0</b>, 不要猜
     */
    public record Signals(int burstSize,
                          long minutesSinceReply,
                          int messageCount,
                          double intimacy,
                          double affection,
                          double urgencyHint) {}

    /** 没说过话时的"很久以前"。用来让"她刚回过话"这个加成自然归零。 */
    public static final long NEVER_REPLIED_MINUTES = 24 * 60L;

    /**
     * @return 0..1 的显著性。它不是概率 —— 概率由 {@link AttentionService} 连同作息与
     *         精力一起算。这个数字只回答"这条消息本身有多显眼"。
     */
    public static double of(Signals s) {
        if (s == null) {
            return 0.3;
        }

        // 基线: 一条普通消息。取 0.3 与旧链的 `0.3 + ...` 同一个量级,
        // 这样切流时 noticeProb 不会整体跳档。
        double salience = 0.3;

        // 连发: 最明显的外部特征, 且完全不依赖内容。1 条 → 0, 3 条 → +0.18, 5 条以上 → +0.3
        salience += Math.min(0.3, Math.max(0, s.burstSize() - 1) * 0.09);

        // 关系: 亲密的人发来的更显眼。与旧链的 relWeight = intimacy*0.6 + affection*0.4 同源
        double relWeight = clamp01(s.intimacy()) * 0.6 + clamp01(s.affection()) * 0.4;
        salience += relWeight * 0.15;

        // 她刚回过话 → 她还在这个对话里, 更容易注意到
        if (s.minutesSinceReply() <= 5) {
            salience += 0.15;
        } else if (s.minutesSinceReply() <= 30) {
            salience += 0.07;
        }

        // 关系早期: 刚认识时真人会更认真地看消息(既有代码里 messageCount < 8 的保底注意同理)
        if (s.messageCount() < 8) {
            salience += 0.1;
        }

        // 聊天平台显式给出的紧急度 —— 有就用, 没有就是 0(不猜)
        salience += clamp01(s.urgencyHint()) * 0.25;

        return clamp01(salience);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }
}
