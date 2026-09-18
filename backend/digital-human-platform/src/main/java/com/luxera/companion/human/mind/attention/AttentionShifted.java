package com.luxera.companion.human.mind.attention;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §5.4 —— <b>{@code mind.attention-shifted.v1}</b>, 注意力转移。
 *
 * <p>这条事件的类型已经在 {@link CoreEventCatalog} 里登记过了(它的 producer 写的就是
 * {@link AttentionService}), 这里提供的是它的<b>实现记录</b> —— 目录登记了类型,
 * 但目录不是任何一条事件的实例。
 *
 * <h2>它为什么是"持续影响"而不是"实时刺激"</h2>
 * 因为"她开始专注某件事"不是一瞬间的事, 它<b>持续</b>存在, 且会改变别的刺激的处境:
 * 注意力被占住以后, 别的感知更难进入认知(见 {@link AttentionContext#taskAttention()}）。
 * 这正是用户那句话在架构里的落点 —— "她在忙所以没回", 而不是"那条消息被拒绝了"。
 *
 * <h2>为什么用 {@code cancellationKey} 而不是每一 tick 记一笔</h2>
 * {@code ContinuousEffectLedger} 的替换语义(同 channel + 同 key → 旧的标记失效)
 * 在这里正好是要的行为: 她此刻的注意力占用是一个<b>状态</b>, 不是一段累积的历史。
 * 不带 key 的话, 十分钟里会有几百条"她注意力占用 0.7"叠在账本上,
 * 于是"她此刻有多忙"变成"要先把最近一百条加起来", 而那是错的。
 *
 * <p>而"她的注意力<b>什么时候</b>被什么占住的"仍然可查 —— 被替换的旧账目不删除,
 * 只是标记为已失效(见 {@code ContinuousEffectLedger} 的类注释"替换与撤销")。
 *
 * <h2>它不负责什么</h2>
 * 它<b>不</b>带感知的内容, 也<b>不</b>带是谁把她叫走的。那两件事分别在
 * {@code Percept} 与 {@code AttendedPercept} 的 reason 里, 由各自的路径记录 ——
 * 一条持续影响账目上塞进一句正文, 就等于把正文写进了稳态通道的历史里,
 * 而那个历史是会被 LLM 拿去当上下文的。
 */
public record AttentionShifted(
        EventTypeId typeId,
        Instant occurredAt,
        String from,
        String to,
        double load,
        String reason) implements StateEffectEvent {

    /** 在目录里登记的类型标识。 */
    public static final EventTypeId TYPE = EventTypeId.parse("mind.attention-shifted.v1");

    /**
     * 账本上的替换键。
     *
     * <p>常量而不是每条自造 —— 见类注释"为什么用 cancellationKey"。
     * 它同时也是"她的注意力是一个状态"这句话在数据上的形状。
     */
    public static final String CANCELLATION_KEY = "mind.attention-current";

    public AttentionShifted {
        Objects.requireNonNull(occurredAt, "注意力事件必须带时刻 —— 不许读系统时钟");
        from = from == null ? "" : from;
        to = to == null ? "" : to;
        Objects.requireNonNull(reason, "注意力转移必须说明理由 —— 没有理由的转移无法归因");
        if (load < 0.0 || load > 1.0) {
            throw new IllegalArgumentException(
                    "注意力占用必须归一化到 [0, 1], 收到 " + load
                            + " —— 未归一化的占用无法与 salience 相乘, 而它们正是要相乘的两项");
        }
        typeId = TYPE;
    }

    public static AttentionShifted of(Instant at, String from, String to, double load, String reason) {
        return new AttentionShifted(TYPE, at, from, to, load, reason);
    }

    @Override
    public double magnitude() {
        return load;
    }

    @Override
    public String effectChannel() {
        return CoreEventCatalog.Channels.ATTENTION_LOAD;
    }

    @Override
    public String cancellationKey() {
        return CANCELLATION_KEY;
    }

    @Override
    public String sourceObjectId() {
        return null;
    }

    @Override
    public String describe() {
        return "注意力 " + (from.isEmpty() ? "空闲" : from) + " → " + to
                + " 占用 " + Math.round(load * 100) / 100.0 + " —— " + reason;
    }
}
