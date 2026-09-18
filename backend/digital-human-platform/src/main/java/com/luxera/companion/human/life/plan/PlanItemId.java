package com.luxera.companion.human.life.plan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.5.2 —— 一个计划项的稳定身份。
 *
 * <h2>为什么需要一个独立的 id 类型, 而不是直接用 {@code String}</h2>
 * 因为计划项的身份会被<b>跨版本引用</b>: {@link PlanMutation.Move} 说的是
 * "把 <b>那一项</b> 挪到新时间", 而这一项在 Revision 18 里的对象和在 Revision 17 里的
 * 对象并不是同一个 Java 实例（Revision 是不可变快照, 见 {@link TimeWindow} 的说明）。
 *
 * <p>如果没有这个 id, 重排器就只能靠"描述相同"或"时间窗口相同"来认人 ——
 * 而这两种认法都会在下面这些真实情形下认错:
 * <ul>
 *   <li>她计划里有两项都叫"写作业"（上午一节、下午一节）;</li>
 *   <li>她先 MOVE 了时间, 于是"时间窗口相同"这个条件失效;</li>
 *   <li>两项时长恰好一样。</li>
 * </ul>
 *
 * <p>用 {@code String} 也能工作, 但那样"哪个字符串是 id、哪个字符串是描述"就只能靠
 * 命名和注释约定 —— 而编译期不检查的约定, 在传参顺序写反时不会报错。
 *
 * <h2>为什么不用数据库自增主键</h2>
 * 因为计划项在<b>成为 Revision 的一部分之前</b>就已经需要有 id 了:
 * LLM 提出的候选计划里带着项, {@link PlanValidator} 要引用它们,
 * {@link PlanMutation} 要指向它们。而那时候还没有数据库行。
 * 本类因此生成进程内唯一的 id, 落库时原样带上 ——
 * <b>让 id 的来源与持久化无关, 是"重排可以在事务之外先算出来"的前提。</b>
 */
public record PlanItemId(String value) implements Comparable<PlanItemId> {

    /**
     * 序号。{@code AtomicLong} 而不是 {@code UUID} 的理由:
     * <ul>
     *   <li>行为分析报告里会大量出现这个 id, {@code item-42} 比
     *       {@code a3f1c0e2-...} 好读得多;</li>
     *   <li>它会被用来排序（"她是先安排了穿衣还是先安排了运动"）, 而序号保序;</li>
     *   <li>单进程内生成, 不存在跨节点冲突问题 —— 每个 agent 一个进程。</li>
     * </ul>
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public PlanItemId {
        Objects.requireNonNull(value, "计划项 id 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "计划项 id 不能是空白 —— 一个空白 id 会让'把那一项挪走'变成"
                            + "'把某个未知的东西挪走', 而后者会静默地什么也不做");
        }
    }

    public static PlanItemId of(String value) {
        return new PlanItemId(value);
    }

    /** 生成一个新的 id。 */
    public static PlanItemId generate() {
        return new PlanItemId("item-" + SEQUENCE.incrementAndGet());
    }

    /**
     * 从外部数据里读回一个 id。
     *
     * <p>存在的意义: 从数据库/JSON 反序列化时, id 必须<b>原样保留</b> ——
     * 如果那时重新 {@link #generate()}, 历史 Revision 里对它的引用就会全部悬空。
     */
    public static PlanItemId parse(String text) {
        return new PlanItemId(text);
    }

    @Override
    public int compareTo(PlanItemId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
