package com.luxera.companion.world;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.runtime.WorldEventType;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * V11 §5.2 —— <b>世界事件</b>的统一词汇。
 *
 * <h2>这个枚举为什么存在</h2>
 * 底盘勘察发现系统里同时活着<b>三套</b>事件词汇, 它们各自都只说了真相的一部分:
 *
 * <ol>
 *   <li>{@code digitalhuman/event/ExternalEventType} —— 6 个值, <b>实际在跑的那条链</b>
 *       ({@code EventProcessingChain} → {@code EventRouter}), 但它只有"东西来了",
 *       没有"她注意到了吗 / 她读了吗"。</li>
 *   <li>{@code runtime/WorldEventType} —— 14 个字符串常量, 把意识阶梯
 *       ({@code RECEIVED → NOTIFIED → NOTICED → READ → DEFERRED}) 定义得清清楚楚,
 *       但<b>只有 {@code WORLD_EVENT_OCCURRED} 一个被生产过</b>, 另外 13 个没有任何生产者。</li>
 *   <li>{@code world/WorldEvent} 的 {@code SRC_*} 常量 —— 那是<b>来源</b>, 不是类型,
 *       已经由 {@link EventSource} 承接。</li>
 * </ol>
 *
 * 所以本枚举不是"再造第四套", 而是前三套的<b>并集与归一</b>: 每个值都写明它从哪来,
 * 且 {@link #fromExternal} / {@link #fromLegacyWire} 两张映射表是可执行的证据 ——
 * 三套词汇在这里第一次能互相翻译。旧常量一个都没删(V11 §25 不允许 Big Bang),
 * 迁移是 Adapter → Shadow → Cutover, 不是替换。
 *
 * <h2>哪些是"阶梯", 哪些是"事实"</h2>
 * 设计文档最核心的一条区分是: <b>事件发生 ≠ 她感知到 ≠ 她注意到 ≠ 她读了 ≠ 她回复</b>。
 * 前五个值 ({@link #USER_MESSAGE_RECEIVED} 到 {@link #USER_MESSAGE_DEFERRED}) 是
 * 同一条消息在同一条意识阶梯上的不同台阶 —— 它们描述的是<b>她</b>, 不是世界。
 * 其余值是世界里发生过的事实, 与她注没注意到无关。{@link #isPerceptionLadder()} 把这条区分
 * 变成可查询的, 因为把两者混在一张表里排序正是旧链最根子的错误。
 *
 * <p>回复({@code REPLY})故意<b>不在</b>这里 —— 回复是一个动作, 不是事件。
 * 动作在 {@code action/} 那边, 由她自己发出(设计文档 §2.3)。
 */
public enum AgentEventType {

    // ── 意识阶梯: 同一条消息在她身上走过的五级台阶 ──────────────────────
    /** 消息已经存在于世界里(对方的手机上显示"已送达")。她还什么都不知道。 */
    USER_MESSAGE_RECEIVED("USER_MESSAGE_RECEIVED", true),
    /** 手机响了/震了。通知已经产生, 但她可能戴着耳机在开会。 */
    USER_MESSAGE_NOTIFIED("USER_MESSAGE_NOTIFIED", true),
    /** 她意识到了有这么个东西。还不构成"要去看"。 */
    USER_MESSAGE_NOTICED("USER_MESSAGE_NOTICED", true),
    /** 她真的看了。正文到这一刻才允许进入认知 —— 这是 V11 §2.2.2 的全部要点。 */
    USER_MESSAGE_READ("USER_MESSAGE_READ", true),
    /** 看了, 但现在不回(忙/累/不知道怎么回)。<b>已读不回是一个决定, 不是一个 bug</b>。 */
    USER_MESSAGE_DEFERRED("USER_MESSAGE_DEFERRED", true),

    // ── 世界里发生过的事实 ──────────────────────────────────────────────
    /** 手机把某个东西摆到了她面前(不限于聊天消息)。 */
    DEVICE_NOTIFICATION("DEVICE_NOTIFICATION", false),
    /** 外部程序(应用平台)在她身上触发了什么。 */
    APPLICATION_EVENT("APPLICATION_EVENT", false),
    /** 她的生活推进了: 一件事开始。 */
    ACTIVITY_STARTED("ACTIVITY_STARTED", false),
    /** 一件事结束。 */
    ACTIVITY_ENDED("ACTIVITY_ENDED", false),
    /** 一件事推进中(进度变了, 还没结束)。 */
    ACTIVITY_PROGRESS("ACTIVITY_PROGRESS", false),
    /** 环境变了(噪音/天气/在场的人)。 */
    ENVIRONMENT_CHANGED("ENVIRONMENT_CHANGED", false),
    /** 她自己的情绪状态变了。 */
    EMOTION_CHANGED("EMOTION_CHANGED", false),
    /** 到点了, 把她叫醒 —— 唯一由她自己(或调度器替她)产生的事件。 */
    SCHEDULED_WAKEUP("SCHEDULED_WAKEUP", false),
    /** 世界里发生了一件被事件模拟器判定为"值得记一笔"的事。 */
    WORLD_EVENT_OCCURRED("WORLD_EVENT_OCCURRED", false),
    /** 她心里形成了一个念头。 */
    THOUGHT_FORMED("THOUGHT_FORMED", false),
    /** 她与某个人的关系状态变了。 */
    RELATIONSHIP_CHANGED("RELATIONSHIP_CHANGED", false),
    /** 一条悬着的事到了该有结果的时候({@code open_loops} 驱动)。 */
    OPEN_LOOP_DUE("OPEN_LOOP_DUE", false),
    /** 一个念头到了该被激活的时候({@code intentions} 驱动)。 */
    INTENTION_ACTIVATED("INTENTION_ACTIVATED", false),
    /** 时间到了(与 {@link #SCHEDULED_WAKEUP} 的区别: 这个是时钟, 那个是"她给自己定的闹钟")。 */
    TIME_EVENT("TIME_EVENT", false),
    /** 生活里发生了一件事(与 ACTIVITY_* 的区别: 那是日程, 这是意外)。 */
    LIFE_EVENT("LIFE_EVENT", false);

    private final String wire;
    private final boolean perceptionLadder;

    AgentEventType(String wire, boolean perceptionLadder) {
        this.wire = wire;
        this.perceptionLadder = perceptionLadder;
    }

    /** 落库/跨进程传输用的稳定字符串。**不要用 {@link #name()}** —— 改名不该改变数据。 */
    public String wire() {
        return wire;
    }

    /**
     * 是否属于意识阶梯({@code RECEIVED → NOTIFIED → NOTICED → READ → DEFERRED})。
     *
     * <p>阶梯上的值是"同一条消息的五个状态", 因此它们共享同一个 {@code reference}
     * ({@code messageId}); 其余值是独立的事实, 各有各的指涉对象。
     */
    public boolean isPerceptionLadder() {
        return perceptionLadder;
    }

    /** 是否是"世界发生了事"而非"她身上发生了事"。 */
    public boolean isWorldFact() {
        return !perceptionLadder;
    }

    // ─────────────────────── 映射表: 三套词汇 → 一套 ───────────────────────

    /**
     * {@code digitalhuman/event/ExternalEventType}(那条实际在跑的链) → 本枚举。
     *
     * <p>{@code CHAT_MESSAGE_DELIVERED} 映射到 {@link #USER_MESSAGE_RECEIVED} 而不是
     * {@code NOTIFIED}: 送达是<b>世界</b>的事实(消息落库了), 通知是<b>手机</b>的事实。
     * 旧链把这两件事合成一个事件名, 于是"她还没被通知"这个状态根本无法表达 ——
     * 这正是 V11 要拆开的第一件事。
     */
    public static Optional<AgentEventType> fromExternal(ExternalEventType type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case CHAT_MESSAGE_DELIVERED -> Optional.of(USER_MESSAGE_RECEIVED);
            case DEVICE_NOTIFICATION -> Optional.of(DEVICE_NOTIFICATION);
            case APPLICATION_EVENT -> Optional.of(APPLICATION_EVENT);
            case LIFE_EVENT -> Optional.of(LIFE_EVENT);
            case TIME_EVENT -> Optional.of(TIME_EVENT);
            case ENVIRONMENT_EVENT -> Optional.of(ENVIRONMENT_CHANGED);
        };
    }

    /**
     * {@code runtime/WorldEventType} 的每一个常量 → 本枚举。
     *
     * <p><b>写死成一张表, 而不是按名字现查</b>: 名字现查在"两边刚好同名"时永远为真,
     * 于是一旦 {@code WorldEventType} 加了一个新常量、这边忘了跟, 查表会静默返回空 ——
     * 事件凭空消失, 没有任何地方报错。写成表之后, 漏掉的新常量会被
     * {@code AgentEventTypeTest} 的覆盖断言当场抓住。
     *
     * <p>这张表也是设计文档 §25 "不许 Big Bang" 的具体形态: 旧常量一个没删,
     * 它们只是在这里有了一个确定的翻译。
     */
    private static final Map<String, AgentEventType> LEGACY_WIRE = Map.ofEntries(
            Map.entry(WorldEventType.USER_MESSAGE_RECEIVED, USER_MESSAGE_RECEIVED),
            Map.entry(WorldEventType.USER_MESSAGE_NOTIFIED, USER_MESSAGE_NOTIFIED),
            Map.entry(WorldEventType.USER_MESSAGE_NOTICED, USER_MESSAGE_NOTICED),
            Map.entry(WorldEventType.USER_MESSAGE_READ, USER_MESSAGE_READ),
            Map.entry(WorldEventType.USER_MESSAGE_DEFERRED, USER_MESSAGE_DEFERRED),
            Map.entry(WorldEventType.ACTIVITY_STARTED, ACTIVITY_STARTED),
            Map.entry(WorldEventType.ACTIVITY_ENDED, ACTIVITY_ENDED),
            Map.entry(WorldEventType.ACTIVITY_PROGRESS, ACTIVITY_PROGRESS),
            Map.entry(WorldEventType.ENVIRONMENT_CHANGED, ENVIRONMENT_CHANGED),
            Map.entry(WorldEventType.EMOTION_CHANGED, EMOTION_CHANGED),
            Map.entry(WorldEventType.SCHEDULED_WAKEUP, SCHEDULED_WAKEUP),
            Map.entry(WorldEventType.WORLD_EVENT_OCCURRED, WORLD_EVENT_OCCURRED),
            Map.entry(WorldEventType.THOUGHT_FORMED, THOUGHT_FORMED),
            Map.entry(WorldEventType.RELATIONSHIP_CHANGED, RELATIONSHIP_CHANGED));

    /** 给覆盖测试用的只读视图 —— 它存在是为了让"这张表是不是全的"可被断言。 */
    static Map<String, AgentEventType> legacyWireTable() {
        return LEGACY_WIRE;
    }

    /**
     * {@code runtime/WorldEventType} 的字符串常量 → 本枚举。
     *
     * <p>认不出来时返回 {@link Optional#empty()} 而<b>不是抛异常</b>: 这张表是给迁移期用的,
     * 旧日志里可能有已经被淘汰的事件名, 一条读不懂的历史记录不该让整个重放崩掉。
     * 反过来, 我们自己刚写下去的值请用 {@link #requireWire} —— 那里读不懂就是数据坏了。
     */
    public static Optional<AgentEventType> fromLegacyWire(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return Optional.empty();
        }
        AgentEventType mapped = LEGACY_WIRE.get(legacy.trim().toUpperCase(Locale.ROOT));
        if (mapped != null) {
            return Optional.of(mapped);
        }
        // 本枚举自己产出的值走这条(含 V11 新增、WorldEventType 里还没有的那些)
        for (AgentEventType t : values()) {
            if (t.wire.equalsIgnoreCase(legacy.trim())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * 严格版: 认不出来就抛。
     *
     * <p>用在"我们自己刚写下去的值"上 —— 那里读不懂意味着数据坏了, 静默吞掉会变成
     * 事件凭空消失。迁移期的历史数据请用 {@link #fromLegacyWire}。
     */
    public static AgentEventType requireWire(String wire) {
        return fromLegacyWire(wire).orElseThrow(
                () -> new IllegalArgumentException("不是已知的世界事件类型: " + wire));
    }
}
