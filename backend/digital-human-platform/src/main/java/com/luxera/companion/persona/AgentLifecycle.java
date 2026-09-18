package com.luxera.companion.persona;

/**
 * 一个 agent 的**运转状态** —— 它现在要不要消耗算力。
 *
 * <h2>它为什么必须存在</h2>
 *
 * 这个平台的 agent 是"持续存在"的: 18 个定时任务在推进它的生活、想法、反省、行为,
 * 消息一来就进认知链。**每一步都可能调 LLM**。于是"让它停下来"是一件运维上必须能做的事
 * —— 而这套代码此前**根本没有这个概念**: 唯一能停下一个 agent 的办法是
 * {@code DELETE /api/companions/{id}}, 而那会把它的历史一起销毁(见
 * {@code AgentRetirementService} 的类注释: "绝不为一个可能还活着的东西销毁历史")。
 *
 * <p>所以运维今天只有两档: **全速跑** 或 **彻底删除**。中间那档 ——
 * "先停一停, 我回头看" —— 是缺失的, 而它恰恰是最常用的那档。
 *
 * <h2>为什么是两档, 不是四档</h2>
 *
 * V11 方案里 agent 是"持续存在的数字人", 它的状态词表里有 ACTIVE/PAUSED/RETIRED 三档。
 * 这里只落两档, 因为**第三档已经有了**: {@code RETIRED} 就是
 * {@code companions.deleted_at is not null}, 它有自己的写入路径
 * ({@code CompanionService.softDelete}) 和清理逻辑, 再包一层枚举只会让两处状态
 * 有互相矛盾的机会。
 *
 * <p>所以本枚举只回答"活着、但要不要跑", 而"还活着吗"由 {@code deletedAt} 回答。
 * 两个问题, 两个列, 不合并。
 *
 * <h2>存哪, 以及为什么是它</h2>
 *
 * {@code companions.status}。这一列**从建表起就在**({@code @Column(length = 32)
 * private String status = "active"}), 默认值一直是 {@code "active"}, 而全代码库
 * **没有一处读过或写过它** —— 它是一根死列。用一个已经存在的死列, 而不是加一根新列,
 * 有两个实在的好处:
 *
 * <ul>
 *   <li>{@code ddl-auto: update} 加不了 {@code NOT NULL} 列, 而存量 110 行全都已经有
 *       {@code 'active'} 这个正确的值 —— 新列则要么可空(于是每个读点都要处理 null),
 *       要么要写一次回填。</li>
 *   <li>它在 {@code companions} 行上, 于是**凡是将领到 Companion 实体的地方都能免费
 *       看到状态** —— 那 15 个"遍历全部 agent"的循环不需要为了过滤再查一次库。</li>
 * </ul>
 */
public enum AgentLifecycle {

    /** 正常运转: 认知链、定时任务、主动行为都照常。 */
    ACTIVE("active"),

    /**
     * 已暂停: 不认知、不主动、不烧 token, 但**一切都还在**。
     *
     * <p>"暂停"这个词必须按字面理解 —— 它**不**删任何东西: 记忆、关系、未闭环的
     * 念头、待处理的消息队列、手机通知全部原样保留。恢复之后是从暂停的那一刻接着走,
     * 而不是从零开始。这正是它与删除的区别, 也是它存在的理由。
     */
    PAUSED("paused");

    private final String wire;

    AgentLifecycle(String wire) {
        this.wire = wire;
    }

    /** 落库的那个字符串。 */
    public String wire() {
        return wire;
    }

    /**
     * 把库里的字符串读成枚举。
     *
     * <p><b>认不出来的值一律当成 {@link #ACTIVE}</b>, 这是刻意的、也是这一整个开关里
     * 唯一一处"往危险的方向兜底"的地方。理由: 本方法在每个 LLM 调用前都会跑一次,
     * 而它的两种错法代价悬殊 ——
     *
     * <ul>
     *   <li>把"暂停"误判成"运行": 多烧一点 token。可察觉、可纠正、有上限。</li>
     *   <li>把"运行"误判成"暂停": **这个 agent 变成哑巴**, 而症状是"她不回我了" ——
     *       没有报错、没有日志、没有任何指向这个枚举的线索。</li>
     * </ul>
     *
     * <p>所以只有**字面写着 {@code "paused"}** 才停。将来若有人往这一列写别的值
     * (比如 {@code "sleeping"}), 默认行为是"照常运行", 而不是静默停机。
     */
    public static AgentLifecycle of(String raw) {
        if (raw == null) return ACTIVE;
        return PAUSED.wire.equalsIgnoreCase(raw.trim()) ? PAUSED : ACTIVE;
    }

    public boolean isPaused() {
        return this == PAUSED;
    }
}
