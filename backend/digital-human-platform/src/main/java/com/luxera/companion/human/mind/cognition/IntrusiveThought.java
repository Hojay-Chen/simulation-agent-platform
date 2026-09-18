package com.luxera.companion.human.mind.cognition;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.5 / §5.4.4 —— <b>一个念头自己冒出来</b>。
 *
 * <p>对应目录里已经登记的 {@code mind.intrusive-thought.v1}:
 *
 * <blockquote>
 * "一个念头自己冒出来(想起某件没做完的事)。它<b>没有外部来源</b> ——
 * {@code sourceObjectId} 为空, 而这是它与其他所有刺激最本质的区别"
 * </blockquote>
 *
 * <h2>它为什么是一个<span>感官</span>事件 —— 这一条是本类的重点</h2>
 * 直觉上"念头"与"感官"毫无关系。但就<b>架构上的接法</b>而言, 它与一声响没有任何区别:
 * 它突然出现、有强度、会把她的注意力从当前的事上拽走一点点、而且<b>她没法决定不想</b>。
 * 这正是 {@link SensoryEvent} 描述的东西。
 *
 * <p>把它做成别的东西(比如一条普通的 {@code WorldEvent})会有一个具体后果:
 * 它进不了实时队列、不会经过注意力、于是"她写着作业忽然想起没关灯"这件事
 * 不会与她当前在忙什么发生任何关系 —— 那它就不再是"冒出来的", 而是"被记录的"。
 *
 * <p>而它与外部刺激最本质的差别, 目录那句话已经写明了: <b>{@code sourceObjectId} 为空</b>。
 * 这一点在打分上立刻有用 —— 注意力里那个"处境折扣"是按<b>来源</b>算的,
 * 没有来源的念头拿不到关系折扣, 也就不存在"因为这个人跟我亲近, 所以我想起这件事"
 * 这种错误的因果。
 *
 * <h2>为什么 {@code modality} 是触觉</h2>
 * 五感里没有"内感受"这一档({@code Modalities} 是封闭的, 见 {@link com.luxera.companion.human.mind.percept.Modality}
 * 关于"生理事实"的论证)。念头在体验上最接近"忽然心里一紧" —— 那是触觉性的。
 * 这个选择是<b>记录性的, 不是解释性的</b>: 它只影响统计里这一条落在哪一栏,
 * 不影响任何判断。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不带正文</b>。{@code contentRef} 是一个<b>引用</b>("那件没做完的事"),
 *       不是那句话本身。带正文会让念头成为绕过"读消息动作"的一条通道 ——
 *       而那正是 §9 验收标准 E 要堵的洞;</li>
 *   <li><b>不解释它从哪来</b>。没有"因为想到了某人"这个字段。产生它的推理过程
 *       在 {@code ReasoningEngine} 的观察里, 不在这条事件上 ——
 *       事件是"发生了什么", 不是"为什么"。</li>
 * </ul>
 *
 * @param contentRef 那个念头的引用 —— 一条能让人想起"是哪件事"的短句
 * @param intensity  它有多强。会与当前注意力负载一起决定她多难把它赶走
 */
public record IntrusiveThought(
        EventTypeId typeId,
        Instant occurredAt,
        String contentRef,
        double intensity) implements SensoryEvent {

    /** 目录里登记的类型 —— 见 {@code CoreEventCatalog} 的 {@code mind.intrusive-thought.v1}。 */
    public static final EventTypeId TYPE = EventTypeId.parse("mind.intrusive-thought.v1");

    public IntrusiveThought {
        Objects.requireNonNull(occurredAt, "念头必须带时刻 —— 不许读系统时钟");
        Objects.requireNonNull(contentRef, "念头必须有引用 —— 否则它进不了日志也进不了复盘");
        if (contentRef.isBlank()) {
            throw new IllegalArgumentException("念头的引用不能是空白");
        }
        if (intensity < 0.0 || intensity > 1.0) {
            throw new IllegalArgumentException(
                    "念头的强度必须归一化到 [0, 1], 收到 " + intensity
                            + " —— 越界的强度会让注意力折扣算出无法解释的分数");
        }
        typeId = typeId == null ? TYPE : typeId;
    }

    public static IntrusiveThought of(String contentRef, double intensity, Instant at) {
        return new IntrusiveThought(TYPE, at, contentRef, intensity);
    }

    /**
     * 它<b>没有外部来源</b> —— 这正是它与其他所有刺激的区别, 见类注释。
     *
     * <p>返回 {@code null} 而不是空字符串: 目录里那句话说的是"为空",
     * 而 {@code ActionCommand.actorId} 那些地方的空字符串表示"有一个人但名字是空的"。
     * 两者含义不同, 不混用。
     */
    @Override
    public String sourceObjectId() {
        return null;
    }

    @Override
    public String modality() {
        return CoreEventCatalog.Modalities.TACTILE;
    }

    /**
     * 念头打断当前事情的能力 —— 与强度挂钩。
     *
     * <p>它<b>不</b>随她的注意力负载变化: "这个念头有多强"是念头的属性,
     * "她能不能被打断"是她的处境。两边都打折就是双罚 —— 与 §3.4.4 的边界铁律同一条,
     * 见 {@code AttentionService}。
     */
    @Override
    public double urgency() {
        return intensity;
    }

    @Override
    public String stimulusDescribe() {
        return "她忽然想起:" + contentRef;
    }

    @Override
    public String describe() {
        return stimulusDescribe() + "(强度 " + Math.round(intensity * 100) + "%)";
    }

    @Override
    public String toString() {
        return describe();
    }
}
