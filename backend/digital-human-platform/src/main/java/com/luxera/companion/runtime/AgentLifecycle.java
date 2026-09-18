package com.luxera.companion.runtime;

/**
 * 一个 agent 的<b>运行档</b> —— 它现在要不要消耗算力。V2.2 §3.6.4 / §3.6.5。
 *
 * <h2>为什么这个类型在 {@code runtime} 而不是 {@code human} 包</h2>
 *
 * 运行档是<b>平台</b>概念，不是仿真概念。放进 {@code com.luxera.companion.human}
 * 就等于让 {@code Human} 聚合"知道自己是归谁的、现在是第几档" ——
 * 而 §3.6.3 刚刚论证过它不该知道（归属与生命周期被刻意从仿真身份里拆了出去，
 * 落成独立的一张 {@code agent_ownership} 表）。
 *
 * <p><b>包的位置在这里是一次事实声明</b>，所以哪怕它只有一个枚举的体量，
 * 也没有图省事塞进 {@code human} 里。同理，{@code Human} 这个聚合根上
 * <b>不会</b>有一个 {@code lifecycle} 字段 —— 它读不到自己的运行档，
 * 也不该读。谁要暂停她，走的是 {@code AgentRegistry}，不是 {@code Human} 的方法。
 *
 * <h2>为什么它必须存在</h2>
 *
 * 本平台的 agent 是"持续存在"的：定时任务在推进她的生活、想法、反省、行为，
 * 消息一来就进认知链，而<b>每一步都可能调 LLM</b>。于是"让它停下来"是一件
 * 运维上必须能做的事 —— 而在引入本枚举之前那套实现里<b>根本没有这个概念</b>：
 * 唯一能停下一个 agent 的办法是删掉它，而那是把它的历史一起销毁。
 *
 * <p>也就是说运维当时只有两档：<b>全速跑</b>，或<b>彻底删除</b>。
 * 中间那档 —— "先停一停，我回头看" —— 是缺失的，而它恰恰是最常用的那档。
 *
 * <h2>为什么是两档，不是三档也不是四档</h2>
 *
 * V11 方案里 agent 的状态词表有三档（{@code ACTIVE} / {@code PAUSED} / {@code RETIRED}）。
 * 这里只落两档，因为<b>"还活着吗"是另一个问题</b>：{@code RETIRED} 就是
 * {@code agent_ownership.deleted_at is not null}，它有自己的写入路径和清理逻辑，
 * 再包一层枚举只会让两处状态有互相矛盾的机会。
 *
 * <p>所以本枚举只回答<b>"活着、但要不要跑"</b>，而"还活着吗"由
 * {@code deletedAt} 回答。<b>两个问题，两个列，不合并。</b>
 *
 * <h2>为什么不合并：四值枚举会允许一个自相矛盾的行</h2>
 *
 * 把删除并进来（{@code ACTIVE / PAUSED / ARCHIVED / DELETED}）之后，
 * 表上就允许出现这样一行：
 *
 * <pre>
 *   lifecycle = 'ACTIVE'  且  deleted_at = '2026-09-19T10:00:00'
 * </pre>
 *
 * 它<b>类型合法、约束合法、能写进去、能读出来</b> —— 而读到它的人只能猜哪一半是真的。
 * 两列两问的结构里<b>没有</b>这种组合：软删就是软删，运行档就是运行档。
 * 一个状态机不该有能力表达"我既是活的又是删掉的"。
 *
 * <p>同理本版<b>没有</b> {@code ARCHIVED}：它没有任何一条写入路径、
 * 没有任何一个需求指向它。加一个"看起来合理"的枚举值，与 V2.1 那种
 * "定义了字段却没定义取值"是同一类毛病，只是方向相反。需要的时候再加 ——
 * 加的时候要写清楚它与 {@code PAUSED} 的区别。
 *
 * <h2>存哪</h2>
 *
 * {@code agent_ownership.lifecycle}，存 {@link #wire()} 的字符串而不是
 * {@code ordinal()}。理由与全平台一致：枚举常量的顺序是代码里的排版，
 * 不是数据里的语义，把排版落进数据库意味着"在中间插一个常量"会静默改写历史数据。
 */
public enum AgentLifecycle {

    /** 正常运转：认知链、定时任务、主动行为都照常。 */
    ACTIVE("active"),

    /**
     * 已暂停：不认知、不主动、不烧 token，但<b>一切都还在</b>。
     *
     * <h2>"暂停"必须按字面理解 —— 它不删任何东西</h2>
     *
     * 记忆、关系、未闭环的念头、待处理的消息队列、手机通知全部原样保留。
     * 恢复之后是从暂停的那一刻接着走，而不是从零开始。
     * <b>这正是它与删除的区别，也是它存在的理由。</b>
     *
     * <h2>停的是认知，不是世界（V2.2 §3.6.6）</h2>
     *
     * {@code PAUSED} <b>不是</b>"停止仿真"。世界照旧：时钟在走、气温在升、
     * 聊天平台上的消息在累积、她的计划表上的时刻会到点 —— <b>只是她不看</b>。
     *
     * <p>恢复之后 {@code RecoveryRuntime} 要处理的第一件事是
     * "这段时间里世界发生了什么"，而这件事之所以是可处理的，正是因为
     * <b>世界从未因为她被暂停而停下来</b>。
     *
     * <p>这条语义决定了运行档<b>不能</b>由 Human 内部的一个布尔字段表达。
     * 那样的话"暂停"会变成"她拒绝处理事件"，于是事件堆在队列里等她醒。
     * 两者的区别在恢复的那一刻显形：<b>前者要读的是一段世界历史，
     * 后者要读的是一个积压的队列。</b>前者可以分辨"这三天里下了两场雨"，
     * 后者只能看到"有两千条事件"。
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
     * <h2>认不出来的值一律当成 {@link #ACTIVE} —— 这是刻意的</h2>
     *
     * 本方法是整个运行档机制里<b>唯一一处"往危险的方向兜底"</b>的地方，
     * 而它之所以敢这么兜，是因为两种错法的代价悬殊：
     *
     * <ul>
     *   <li>把"暂停"误判成"运行"：多烧一点 token。<b>可察觉、可纠正、有上限</b>。</li>
     *   <li>把"运行"误判成"暂停"：<b>这个 agent 变成哑巴</b> —— 症状是"她不回我了"，
     *       没有报错、没有日志、没有任何指向这个枚举的线索。而且它发生在
     *       <b>每次认知 tick 之前</b>，所以一次误判会让这个 agent 一直哑下去。</li>
     * </ul>
     *
     * <p>所以只有<b>字面写着 {@code "paused"}</b> 才停。将来若有人往这一列写别的值
     * （比如 {@code "sleeping"}），默认行为是"照常运行"，而不是静默停机。
     *
     * <p>注意 {@code null} 也走这一条 —— 一行还没有运行档的记录，含义是"刚建出来、
     * 还没被暂停过"，也就是 {@code ACTIVE}。
     */
    public static AgentLifecycle of(String raw) {
        if (raw == null) {
            return ACTIVE;
        }
        return PAUSED.wire.equalsIgnoreCase(raw.trim()) ? PAUSED : ACTIVE;
    }

    /** 这个档位要不要跑认知。语义上等价于 {@code this == PAUSED}，但读起来是意图。 */
    public boolean isPaused() {
        return this == PAUSED;
    }

    /**
     * 还活着吗 —— 由另一个列回答，<b>不在本枚举里</b>。
     *
     * <p>这个方法存在只为把上面那句话写进代码：调用方拿着
     * {@code deletedAt} 来问，而不是试图从枚举里读出"是不是被删了"。
     */
    public static boolean isAlive(java.time.Instant deletedAt) {
        return deletedAt == null;
    }

    @Override
    public String toString() {
        return wire;
    }
}
