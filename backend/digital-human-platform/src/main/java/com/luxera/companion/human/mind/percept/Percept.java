package com.luxera.companion.human.mind.percept;

import java.time.Instant;

/**
 * V2.2 §3.4.3 —— <b>"我感知到了什么"</b>。Perception 的产物, Attention 的输入。
 *
 * <pre>
 *   SoundStimulus(frequency=880Hz, volume=0.6, pattern="notification-ding", duration=800ms)
 *       ↓ Perception
 *   Percept(modality=AUDITORY, content="手机发出了通知声", source=她的手机,
 *           salience=0.68, urgency=0.55)
 * </pre>
 *
 * <h2>关键: 这里<b>没有</b>"是谁发的"、"发了什么"、"重不重要"</h2>
 * 这三个问题分别属于 {@code Relationship}(是谁)、{@code ChatApplication}(发了什么,
 * 需要主动去读)、{@code Attention}(重不重要)。Perception 只做<b>解释</b>, 不做<b>判断</b>。
 *
 * <p>把这三样东西挡在外面, 不是为了洁癖。它们是三个<b>不同的时刻</b>:
 * <table border="1">
 *   <tr><th>问题</th><th>答案出现的时刻</th><th>答案的持有者</th></tr>
 *   <tr><td>是谁发的</td><td>她决定要看之后, 拿账号去查通讯录时</td><td>RelationshipGraph</td></tr>
 *   <tr><td>发了什么</td><td>一次成功的读取动作回来之后(§2.2 第 ⑰ 步)</td><td>那个应用</td></tr>
 *   <tr><td>重不重要</td><td>拿到感知、结合她此刻的处境之后</td><td>AttentionService</td></tr>
 * </table>
 * 一旦某个字段把答案提前了(比如 source 里带上账号, 或 content 里带上正文),
 * 这三个时刻就被压成了一个 —— 而"她先注意到响声、再决定去看、才第一次知道内容"
 * 这条链子正是整个平台要证明的东西。它断了以后, 仿真出来的"她"就没有注意力这回事了。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不做取舍</b>。"这条值不值得进工作认知"是 {@code AttentionService} 的事。
 *       一个 Percept 被造出来了不代表她注意到了它 —— 事实上绝大多数刺激都停在这一步。</li>
 *   <li><b>不落库</b>。它是值, 存活在一个 tick 之内。要不要留下痕迹由 Memory 决定。</li>
 *   <li><b>不认识世界对象</b>。它只持有 {@link SourceRef} 这个<b>投影</b>,
 *       而不是 {@code Phone} 那样一个活对象 —— 否则 {@code human/} 就伸手进了 {@code world/},
 *       而那被 ArchUnit 直接拦住。</li>
 * </ul>
 *
 * <h2>{@code salience} 与 {@code urgency} 的区别 —— 别把它们当一回事</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code urgency}</th><th>{@code salience}</th></tr>
 *   <tr>
 *     <td>谁给</td><td>世界(照抄事件上的值)</td><td>她(Mind 算出来的)</td>
 *   </tr>
 *   <tr>
 *     <td>答什么</td><td>这条刺激<b>能不能等</b></td><td>这条刺激<b>有多突出</b></td>
 *   </tr>
 *   <tr>
 *     <td>会不会因人而异</td><td>不会 —— 火警对谁都急</td><td>会 —— 同样一声提示音,
 *         睡着的人和等消息的人听到的不是一回事</td>
 *   </tr>
 * </table>
 * <b>但 salience 是"刺激的属性", 不是"她的处境的属性"</b> —— 处境那一半在
 * {@code AttentionContext} 里, 且只乘一次。理由写在
 * {@code AttentionService} 的 javadoc 里("边界铁律"), 那里还写了这条规则被违反时会出什么事。
 */
public interface Percept {

    PerceptId id();

    /** 走的是哪条感官通道。见 {@link Modality}(那里论证了它为什么可以是 enum)。 */
    Modality modality();

    /**
     * 一句人类可读的"我感知到了什么"。会进入 LLM context。
     *
     * <p>写法示例: {@code "手机发出了一声提示音"}、{@code "有点冷"}。
     *
     * <p><b>不许写聊天正文。</b>这不是一条靠自觉的约定 —— 它是<b>装不下</b>的:
     * 造出 Percept 的那条链路上(世界 → 事件 → 感官通道)根本没有正文可拿,
     * 而 §2.2 第 ⑰ 步之前 {@code Mind} 的工作记忆里也不得出现正文(§9 验收标准 E)。
     */
    String content();

    /**
     * 显著度 0–1: 这个刺激本身有多突出(响度、亮度、强度)。
     *
     * <p>由 {@link Perception} 从刺激算出, <b>不看她的处境</b>。这条约束是可证的:
     * {@code Perception} 拿不到 {@code AttentionContext}。
     */
    double salience();

    /**
     * 紧迫度 0–1: 需不需要立刻处理(疼痛、危险 = 高)。
     *
     * <p>照抄世界给的值。世界必须给它的理由写在
     * {@link com.luxera.companion.boundary.event.SensoryEvent#urgency()} 的 javadoc 里:
     * 队列是<b>世界侧</b>的结构, 它在 Mind 拿到之前就要决定先递哪一条。
     */
    double urgency();

    SourceRef source();

    Instant occurredAt();

    /** 便于日志与调试的一行摘要。 */
    default String describe() {
        return "感知[" + id() + "] " + modality().label() + " 显著度"
                + Math.round(salience() * 100) / 100.0 + " 紧迫度"
                + Math.round(urgency() * 100) / 100.0 + " 来自 " + source().describe()
                + " —— " + content();
    }
}
