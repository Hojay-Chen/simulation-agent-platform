package com.luxera.companion.mind;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * V11 §8 —— <b>把连着来的几句话并成一个认知回合</b>。
 *
 * <h2>它修的是什么</h2>
 * 老链的形状是"一次送达 = 一个回合": 对方连发三句
 * <pre>
 *   今天好累 / 老师讲得好快 / 我都没听懂
 * </pre>
 * 会变成三次完整的认知 —— 三次情绪评估、三次唤醒评估、三次回复。她的表现因此不是
 * "听人把话说完再回应", 而是"每句话都抢着答一句", 而后者不像人。
 *
 * <h2>为什么是状态机而不是 sleep</h2>
 * 一个"收到消息后 sleep 4 秒再看有没有新消息"的实现也能跑通, 但它有两个致命处:
 * <ol>
 *   <li>它占住投递线程 4 秒 —— 而投递是在 per-agent 的 mailbox 消费者线程上跑的,
 *       于是"她在等的这 4 秒里"整个 agent 的事件队列是停的。</li>
 *   <li>时间藏在 {@code sleep()} 里, 于是它的行为<b>只能靠真等来测</b>。
 *       一个必须靠 sleep 才能验证的时序逻辑, 在 CI 上要么很慢, 要么很不稳。</li>
 * </ol>
 * 所以这里的形状是: <b>所有判断都吃一个传进来的 {@code now}</b>, 一条 {@code @Scheduled}
 * 每秒来问一次"有谁到期了吗"({@code V11TurnSealJob})。整类没有一次 sleep、没有一次
 * 时钟读取, 于是 3 条消息并成 1 个回合这件事可以在毫秒内被断言, 而不是等 4 秒。
 *
 * <h2>线程安全</h2>
 * 所有公开方法 {@code synchronized}。调用方来自两条线程: mailbox 的消费者线程(送达)
 * 与调度线程(封口)。故意不加锁优化 —— 这里每分钟只有几十次操作, 而一个
 * "想清楚了的无锁结构"在这里唯一确定的收益是让人不敢改它。
 *
 * <h2>它为什么一个依赖都没有</h2>
 * 不注入 Service、不写库、不发事件、不认识 AgentRuntime。它是一个<b>纯粹的时序状态机</b>:
 * 进的是"谁在什么时候收到了什么", 出的是"哪个回合该封口了"。落库与认知都由调用方做
 * (见 {@code V11TurnPath}) —— 这样它的全部行为都能用 {@code new ConversationTurnAggregator(4000, 45000, 8)}
 * 加几个假时间戳测完, 不需要任何 mock。
 */
@Component
@Slf4j
public class ConversationTurnAggregator {

    /**
     * V11 §8.2 的五个状态。<b>本实现只能表达前三个</b>, 而且前两个的含义与文档
     * 有一处必须说清的偏差:
     *
     * <pre>
     *   OPEN        这个回合目前只有一批消息   ┐ 文档里这两级是"对方还在说"与
     *   QUIET_WAIT  又来了第二批, 静默窗口在走 ┘ "对方停下了"两种时间; 而"对方正在输入"
     *                                            是契约里没有的信号(ChatWorldPort 只有
     *                                            消息与状态), 所以本实现把它退化成
     *                                            "来过几批"—— 一个能被观察到的形状。
     *   SEALED      已封口, 交给调用方
     *   PROCESSING  不表达: 回合一封口就从本类消失, 谁在处理它是调用方的事。
     *   COMPLETED   不表达: 同上。这不是省略, 是边界 —— 一个聚合器不该知道
     *              有个叫 AgentRuntime 的东西存在。
     * </pre>
     */
    public enum State {
        /** 目前只有一批消息。 */
        OPEN,
        /** 又来了第二批 —— 静默窗口从此开始算。 */
        QUIET_WAIT,
        /** 已封口。 */
        SEALED,
        /** 正在被思考(本类不表达, 见上)。 */
        PROCESSING,
        /** 想完了(本类不表达, 见上)。 */
        COMPLETED
    }

    /** 一次送达。{@code messageIds} 是这次到的消息(通常 1 条, 也可能是一批)。 */
    public record Delivery(String agentId, String userId, String conversationId,
                           List<String> messageIds, LocalDateTime at) {}

    /**
     * 一个认知回合。
     *
     * @param forcedBySize 攒够了条数提前封口(说明静默窗口相对对方的语速太长)
     * @param forcedByAge  拖到硬上限必须封口(说明对方一直在说、或者窗口太长)
     */
    public record Turn(String turnId, String agentId, String userId, String conversationId,
                       List<String> messageIds, State state,
                       LocalDateTime openedAt, LocalDateTime lastMessageAt, LocalDateTime sealedAt,
                       boolean forcedBySize, boolean forcedByAge) {

        public int size() {
            return messageIds.size();
        }
    }

    /**
     * 收下一次送达的结果。
     *
     * @param openedNewTurn true = 这次送达开了一个<b>新</b>回合(而不是并进已有的那个)
     * @param turnSize      这个回合现在一共攒了几条 —— 调用方拿它去写心智的工作台
     * @param sealedNow     非空表示这个回合当场就攒满了, 需要调用方<b>立刻</b>处理它
     */
    public record Acceptance(String turnId, int turnSize, boolean openedNewTurn, Turn sealedNow) {

        public boolean forced() {
            return sealedNow != null;
        }
    }

    /**
     * 观测到的数字。<b>切流判据就是 {@code messagesPerTurn}</b>:
     * 它等于 1.0 说明合并<b>根本没发生</b>(窗口太短, 每句话还是各自成回合);
     * 等于 3.0 说明三句话被当成一次说完的。
     */
    public record Stats(long turnsOpened, long turnsSealed, long messagesAggregated,
                        long forcedBySize, long forcedByAge, long turnsCognized,
                        int openTurns, double messagesPerTurn) {}

    private final TurnWindow window;
    /** key = agentId + "|" + conversationId —— 一条线一个回合, 不同的人互不干扰。 */
    private final Map<String, Turn> open = new LinkedHashMap<>();

    private long turnsOpened;
    private long turnsSealed;
    private long messagesAggregated;
    private long forcedBySize;
    private long forcedByAge;
    private long turnsCognized;

    public ConversationTurnAggregator(
            @Value("${app.v11.turns.quiet-window-ms:4000}") int quietWindowMs,
            @Value("${app.v11.turns.max-window-ms:45000}") int maxWindowMs,
            @Value("${app.v11.turns.max-messages:8}") int maxMessages) {
        this.window = new TurnWindow(quietWindowMs, maxWindowMs, maxMessages);
    }

    public TurnWindow window() {
        return window;
    }

    // ─────────────────────────── 收 ───────────────────────────

    /**
     * 收下一次送达: 开一个新回合, 或者并进那条线上已有的回合。
     *
     * <p>并进已有回合时<b>不重置 openedAt</b> —— 硬上限是"从她第一次看到这条线算起",
     * 否则一个不断发消息的人可以把她的回合无限推迟下去, 而"她一直不回"正是要修的问题。
     */
    public synchronized Acceptance accept(Delivery d) {
        if (d == null || blank(d.agentId()) || blank(d.conversationId())) {
            return new Acceptance(null, 0, false, null);
        }
        List<String> ids = cleanIds(d.messageIds());
        if (ids.isEmpty()) {
            return new Acceptance(null, 0, false, null);
        }
        LocalDateTime at = d.at() == null ? LocalDateTime.now() : d.at();
        String key = key(d.agentId(), d.conversationId());
        Turn existing = open.get(key);

        if (existing == null) {
            String turnId = turnId(d.conversationId(), ids.get(0));
            Turn t = new Turn(turnId, d.agentId(), d.userId(), d.conversationId(),
                    ids, State.OPEN, at, at, null, false, false);
            turnsOpened++;
            messagesAggregated += ids.size();
            open.put(key, t);
            if (window.full(t.size())) {
                return new Acceptance(turnId, t.size(), true, seal(t, at, true, false));
            }
            log.debug("[Turn] {} 开了新回合 {} ({} 条)", d.agentId(), turnId, ids.size());
            return new Acceptance(turnId, t.size(), true, null);
        }

        // 并进已有回合。同一个 id 不重复计 —— 重投/重放会让同一批消息再来一次,
        // 而"她把同一条消息读了两遍"在认知上不存在。
        LinkedHashSet<String> merged = new LinkedHashSet<>(existing.messageIds());
        int before = merged.size();
        merged.addAll(ids);
        int added = merged.size() - before;
        if (added == 0) {
            log.debug("[Turn] {} 回合 {} 收到一批已见过的消息, 未扩展", d.agentId(), existing.turnId());
            return new Acceptance(existing.turnId(), existing.size(), false, null);
        }
        Turn extended = new Turn(existing.turnId(), existing.agentId(), existing.userId(),
                existing.conversationId(), List.copyOf(merged), State.QUIET_WAIT,
                existing.openedAt(), at, null, false, false);
        messagesAggregated += added;

        boolean byAge = dueByAge(extended, at);
        boolean bySize = window.full(extended.size());
        if (byAge || bySize) {
            return new Acceptance(extended.turnId(), extended.size(), false, seal(extended, at, bySize, byAge));
        }
        open.put(key, extended);
        log.debug("[Turn] {} 回合 {} 扩展到 {} 条", d.agentId(), extended.turnId(), extended.size());
        return new Acceptance(extended.turnId(), extended.size(), false, null);
    }

    // ─────────────────────────── 封 ───────────────────────────

    /**
     * 把所有到期的回合封口并交出来。由调度线程每秒调用一次。
     *
     * <p>返回的顺序是<b>回合开始的顺序</b> —— 先开口的那条线先被回应。顺序在这里不是细节:
     * 一个总是先回最新那条的实现, 会让一个刚被插话的人永远等不到回复。
     */
    public synchronized List<Turn> sealDue(LocalDateTime now) {
        if (now == null) return List.of();
        List<Turn> due = new ArrayList<>();
        for (Turn t : new ArrayList<>(open.values())) {
            if (dueByQuiet(t, now)) {
                due.add(seal(t, now, false, false));
            } else if (dueByAge(t, now)) {
                due.add(seal(t, now, false, true));
            }
        }
        due.sort(Comparator.comparing(Turn::openedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return due;
    }

    /**
     * 记下"这个回合真的进了认知"。
     *
     * <p>与 {@link #sealDue} 分开是因为<b>封口和认知是两件事</b>: 封口是时间到了,
     * 认知是消息真的被读到了。中间隔着"她有没有读到正文"(读不到、或者 agent 被暂停)。
     * 合并成一个方法的话, 一个被暂停丢掉的回合会被记成"她想过了"。
     */
    public synchronized void noteCognized() {
        turnsCognized++;
    }

    private Turn seal(Turn t, LocalDateTime at, boolean bySize, boolean byAge) {
        open.remove(key(t.agentId(), t.conversationId()));
        turnsSealed++;
        if (bySize) forcedBySize++;
        if (byAge) forcedByAge++;
        Turn sealed = new Turn(t.turnId(), t.agentId(), t.userId(), t.conversationId(),
                t.messageIds(), State.SEALED, t.openedAt(), t.lastMessageAt(), at, bySize, byAge);
        log.info("[Turn] {} 封口回合 {}: {} 条消息并成一个回合(等 {}ms{}{})",
                t.agentId(), t.turnId(), sealed.size(),
                t.lastMessageAt() == null || t.openedAt() == null ? 0
                        : java.time.Duration.between(t.openedAt(), t.lastMessageAt()).toMillis(),
                bySize ? ", 攒满" : "", byAge ? ", 到硬上限" : "");
        return sealed;
    }

    // ─────────────────────────── 看 ───────────────────────────

    public synchronized Stats stats() {
        return new Stats(turnsOpened, turnsSealed, messagesAggregated, forcedBySize, forcedByAge,
                turnsCognized, open.size(),
                turnsSealed == 0 ? 0 : (double) messagesAggregated / turnsSealed);
    }

    /** 正在等着的回合。诊断用它回答"她手上现在挂着什么"。 */
    public synchronized List<Turn> openTurns() {
        return List.copyOf(open.values());
    }

    public synchronized List<Turn> openTurnsOf(String agentId) {
        if (blank(agentId)) return List.of();
        return open.values().stream().filter(t -> t.agentId().equals(agentId)).toList();
    }

    /** 供测试与运维: 丢掉所有未封口的回合(不计数)。 */
    public synchronized void clear() {
        open.clear();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private boolean dueByQuiet(Turn t, LocalDateTime now) {
        LocalDateTime at = window.quietSealAt(t.lastMessageAt());
        return at != null && !now.isBefore(at);
    }

    private boolean dueByAge(Turn t, LocalDateTime now) {
        LocalDateTime at = window.hardSealAt(t.openedAt());
        return at != null && !now.isBefore(at);
    }

    /**
     * 回合 id。
     *
     * <p>确定性的({@code 会话#第一条消息}), 而不是随机 UUID: 日志、心智表、阶梯事件里
     * 会出现同一个回合, 而它们之间能对上号的前提是这个 id 可以被重新算出来。
     * 用第一条消息当锚也是安全的 —— 同一条消息重投会被幂等短路挡在更外面。
     */
    private static String turnId(String conversationId, String firstMessageId) {
        String id = conversationId + "#" + firstMessageId;
        return id.length() > 160 ? id.substring(0, 160) : id;
    }

    private static String key(String agentId, String conversationId) {
        return agentId + "|" + conversationId;
    }

    private static List<String> cleanIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (!blank(id)) out.add(id);
        }
        return List.copyOf(out);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
