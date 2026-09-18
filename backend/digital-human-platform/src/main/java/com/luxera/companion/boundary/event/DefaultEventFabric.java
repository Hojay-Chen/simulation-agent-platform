package com.luxera.companion.boundary.event;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.registry.EventHandlerRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V2.2 §5.1 —— {@link EventFabric} 的默认实现。
 *
 * <h2>它做四件事, 顺序是有讲究的</h2>
 * <pre>
 *   publish(event):
 *     ① 按能力接口分类, 送进对应结构(账本 / 队列 / 计划表)
 *     ② 记进事件历史(供重放与诊断)
 *     ③ 分派给 ON_ARRIVAL 的 handler
 *     ④ 记因果深度, 超阈值时 WARN
 *
 *   tick(now):
 *     ① 结算账本(清过期 + 求和)
 *     ② 分派给 ON_TICK 的 handler
 * </pre>
 *
 * <h3>为什么 ① 在 ③ 之前</h3>
 * 因为 handler 里有很大一类是"看到事件就补充一条入账"。如果先分派再入账, 那条补充
 * 入账的账目会错过这一 tick 的结算 —— 表现是"她穿上衣服之后, 保暖值要等下一次 tick
 * 才变", 一个只在时序上出现、单测里几乎撞不到的症状。
 *
 * <h3>为什么不是"先分派再入账"</h3>
 * 反过来(先给 handler 看, 再入账)的吸引力在于"handler 可以看到还没有被账本动过的
 * 原始事件"。但那个需求其实是伪需求: handler 拿到的就是 {@link WorldEvent} 本身,
 * 账本<b>从不修改事件</b>(它是不可变的), 只是记了一笔账。所以两个顺序在
 * "handler 看到什么"上完全一样, 差别只在"账目什么时候生效" —— 而后者有明显正确的答案。
 *
 * <h2>因果深度: 为什么是 WARN 而不是异常</h2>
 * 一条 handler 投递 → 触发另一条 handler → 又投递, 是一个很容易写出来的循环
 * (阈值检测产生冷刺激 → 某个 handler 看到冷刺激就调低环境温度 → 又触发阈值检测)。
 *
 * <p>抛异常看起来更"安全", 但它的实际效果是: 一个写错的第三方 handler 让整个 agent
 * 彻底停摆。而 WARN 的效果是: 她的行为有点怪, 日志里明明白白写着"因果链深达 47 层,
 * 从 X 开始", 十分钟就能定位。<b>在仿真系统里, "行为有点怪但还在跑"几乎总是优于
 * "什么都不做"</b> —— 因为后者会让"她为什么不理我"变成无法回答的问题。
 */
@Slf4j
public class DefaultEventFabric implements EventFabric {

    /** 因果链深度超过这个值就打 WARN。 */
    private static final int CAUSAL_DEPTH_WARN = 16;

    /**
     * 事件历史保留条数。
     *
     * <p>256 是一个刻意的折中: 够覆盖"她最近经历了什么"这个问题的合理范围
     * (一次醒来、一次对话、一段被打断的工作), 又不至于让每个 agent 实例常驻
     * 几百 KB 的无用对象。<b>真正的历史在 {@code EventStore} 里落库</b> ——
     * 这里只是内存里的近况窗口。
     */
    private static final int HISTORY_LIMIT = 256;

    private final String humanId;

    private final ContinuousEffectLedger effects;
    private final RealtimeEventQueue stimuli;
    private final EventHandlerRegistry handlers;

    /** 每个通道一个 sink —— C 类事件的落点。默认没有, 因为计划表由 Human 侧装配。 */
    private final List<ScheduledEventSink> scheduledSinks = new ArrayList<>();

    /** 近期事件, 新的在前。 */
    private final Deque<WorldEvent> history = new LinkedList<>();

    /** 投递中的因果链 —— 用栈是因为投递是嵌套的(handler 在 publish 里再 publish)。 */
    private final ThreadLocal<Deque<EventTypeId>> causalStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final AtomicInteger causalDepthWarnings = new AtomicInteger();

    /**
     * 距上一次 tick 之间到达了几条事件。
     *
     * <p>刻意用"自上次 tick 以来"而不是"本次 tick 分派了几条": 前者回答的是
     * "这一段时间她经历了多少", 后者回答的是"这个 tick 的 handler 有几个" ——
     * 而后者是个纯实现量, 写进 {@link TickReport} 毫无信息量。
     */
    private final AtomicInteger arrivalsSinceTick = new AtomicInteger();

    /** 事件 id → 事件。给 {@link #findEvent} 用, 与 history 同步维护。 */
    private final Map<String, WorldEvent> byId = new LinkedHashMap<>();

    public DefaultEventFabric(String humanId, EventHandlerRegistry handlers) {
        this(humanId, handlers, RealtimeEventQueue.DEFAULT_CAPACITY);
    }

    public DefaultEventFabric(String humanId, EventHandlerRegistry handlers, int queueCapacity) {
        if (humanId == null || humanId.isBlank()) {
            throw new IllegalArgumentException(
                    "EventFabric 必须知道它是谁的 —— 账本和队列都是 per-human 的状态, "
                            + "一个不知道归属的 fabric 会让两个 agent 共用体温");
        }
        this.humanId = humanId;
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.effects = ContinuousEffectLedger.empty();
        this.stimuli = new RealtimeEventQueue(queueCapacity);
    }

    @Override
    public String humanId() {
        return humanId;
    }

    // ─────────────────────────── 投递 ───────────────────────────

    @Override
    public void publish(WorldEvent event) {
        Objects.requireNonNull(event, "要投递的事件不能为空");

        // ① 按能力接口分类
        route(event);

        // ② 记历史
        remember(event);

        // ③ 分派
        dispatchArrival(event);

        // ④ 因果深度
        checkCausalDepth(event);
    }

    /**
     * 分类 —— <b>这里是"一个事件可以是多种类别"这件事发生的地方</b>。
     *
     * <p>用三个独立的 {@code if} 而不是 {@code if/else if}: 一条"她穿着羽绒服"的事件
     * 应当<b>同时</b>进账本(保暖 +0.85)和计划表(14:00 去干洗)。用 {@code else if}
     * 会让它只进第一个匹配的结构, 而"哪一个是第一个"取决于代码里 if 的书写顺序 ——
     * 一个纯粹的、灾难性的实现细节泄漏。
     */
    private void route(WorldEvent event) {
        if (event instanceof StateEffectEvent effect) {
            effects.book(effect, event.occurredAt());
        }
        if (event instanceof SensoryEvent stimulus) {
            RealtimeEventQueue.OfferResult result = stimuli.offer(stimulus);
            if (!result.reachedMind()) {
                // 丢弃必须可见 —— 见 RealtimeEventQueue 类注释"容量与丢弃"
                log.debug("[EventFabric/{}] 刺激 {} 未进入队列: {}", humanId, event.typeId(),
                        result instanceof RealtimeEventQueue.OfferResult.Dropped d ? d.reason() : "?");
            }
        }
        if (event instanceof ScheduledEvent schedule) {
            schedule.validateWindow();
            if (scheduledSinks.isEmpty()) {
                // 没有计划表 → C 类事件无处可去。这不是异常: 一个只需要"体感"的测试
                // 完全可能不装配计划表。但必须留下痕迹, 否则"安排被静默吞掉"会成为
                // 一个查不出根因的故障。
                log.debug("[EventFabric/{}] 收到安排 {} 但当前没有 PlanBoard 注册 —— 已忽略",
                        humanId, schedule.scheduleDescribe());
            }
            for (ScheduledEventSink sink : scheduledSinks) {
                if (sink.accepts(schedule.intentType())) {
                    sink.onScheduled(schedule);
                } else {
                    log.debug("[EventFabric/{}] {} 拒绝接受 {}", humanId, sink.getClass().getSimpleName(),
                            schedule.intentType());
                }
            }
        }
    }

    private void remember(WorldEvent event) {
        history.addFirst(event);
        while (history.size() > HISTORY_LIMIT) {
            WorldEvent evicted = history.removeLast();
            // 只有在别处没有同 id 的事件时才从索引里摘掉 —— 同 id 是可能的(重复投递),
            // 而 byId 存的是"最近一次", 摘错了会让 findEvent 找不到还在窗口里的那条
            if (!history.contains(evicted)) {
                byId.remove(eventKey(evicted));
            }
        }
        byId.put(eventKey(event), event);
    }

    /**
     * 事件的键。
     *
     * <p>{@link WorldEvent} 刻意没有 {@code eventId()} 方法 —— 因为"事件有没有 id"
     * 是<b>持久化层</b>的问题, 不是"世界里发生了什么"的问题。一个纯内存跑的仿真
     * 不需要给每条事件编号。所以这里用"类型 + 时刻 + 来源"合成一个键,
     * 它<b>不保证唯一</b>(同一纳秒同一来源的两条会被认成一条), 但足够支撑诊断与
     * 近况查询 —— 而这两件事正是本类存在的理由。
     */
    private static String eventKey(WorldEvent event) {
        return event.typeId() + "@" + event.occurredAt() + "#" + event.sourceObjectId();
    }

    private void dispatchArrival(WorldEvent event) {
        Deque<EventTypeId> stack = causalStack.get();
        stack.push(event.typeId());
        try {
            for (EventHandler<?> handler : handlers.arrivalHandlersFor(event.typeId())) {
                invoke(handler, event);
            }
            arrivalsSinceTick.incrementAndGet();
        } finally {
            stack.pop();
        }
    }

    /** 调用一个 handler, <b>吞掉它的异常</b> —— 一个坏 handler 不该让整条链断掉。 */
    @SuppressWarnings("unchecked")
    private void invoke(EventHandler<?> handler, WorldEvent event) {
        try {
            ((EventHandler<WorldEvent>) handler).handle(event, this);
        } catch (RuntimeException e) {
            // 记 ERROR 而不是让异常往上冒: 这条事件的其他消费者仍然应该收到它。
            // 一个 handler 抛异常就吞掉整条投递, 会让"她没反应"的原因完全不可见。
            log.error("[EventFabric/{}] handler {} 处理 {} 时抛异常, 已跳过该 handler 并继续分派",
                    humanId, handler.name(), event.typeId(), e);
        }
    }

    private void checkCausalDepth(WorldEvent event) {
        int depth = causalStack.get().size();
        if (depth > CAUSAL_DEPTH_WARN) {
            int n = causalDepthWarnings.incrementAndGet();
            // 只打前几条, 否则一个真循环会在一分钟内把日志刷爆 ——
            // 而刷爆日志的后果是真正有用的那条被淹没
            if (n <= 5) {
                log.warn("[EventFabric/{}] 因果链深达 {} 层, 当前 {} —— 栈: {}", humanId, depth,
                        event.typeId(), causalStack.get());
            }
        }
    }

    // ─────────────────────────── 注册 ───────────────────────────

    @Override
    public void subscribe(EventHandler<?> handler) {
        handlers.register(handler);
    }

    @Override
    public void replay(EventHandler<?> handler, Instant from, Instant to) {
        Objects.requireNonNull(handler, "要重放的 handler 不能为空");
        Objects.requireNonNull(from, "重放必须指定起始时刻");
        Objects.requireNonNull(to, "重放必须指定结束时刻");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("重放的结束时刻早于起始时刻: " + from + " → " + to);
        }
        // 历史是"新的在前", 重放要按时间正序喂 —— 否则 handler 会先看到结果再看到原因
        List<WorldEvent> ordered = history.stream()
                .filter(e -> !e.occurredAt().isBefore(from) && !e.occurredAt().isAfter(to))
                .sorted(java.util.Comparator.comparing(WorldEvent::occurredAt))
                .toList();
        log.debug("[EventFabric/{}] 重放 {} 条事件给 {}", humanId, ordered.size(), handler.name());
        for (WorldEvent event : ordered) {
            if (handler.accepts(event.typeId())) {
                invoke(handler, event);
            }
        }
    }

    @Override
    public void registerScheduledSink(ScheduledEventSink sink) {
        Objects.requireNonNull(sink, "要注册的计划表不能为空");
        if (!scheduledSinks.contains(sink)) {
            scheduledSinks.add(sink);
        }
    }

    // ─────────────────────────── tick ───────────────────────────

    @Override
    public TickReport tick(Instant now, HumanRuntimeContext context) {
        Objects.requireNonNull(now, "tick 必须带仿真时刻");
        Objects.requireNonNull(context, "tick 必须带运行时上下文");
        if (!humanId.equals(context.humanId())) {
            throw new IllegalArgumentException(
                    "上下文属于 " + context.humanId() + " 但本 fabric 属于 " + humanId
                            + " —— 串了对象的 tick 会让她用别人的体温做判断");
        }

        // ① 先结算 —— 让 ON_TICK 的 handler 看到一个自洽的世界
        ContinuousEffectLedger.Settlement settlement = effects.settle(now);

        // ② 再分派
        List<EventHandler<?>> tickers = handlers.tickHandlers();
        for (EventHandler<?> handler : tickers) {
            invokeTick(handler, settlement, context);
        }

        return new TickReport(arrivalsSinceTick.getAndSet(0), tickers.size(), settlement,
                stimuli.size(), now);
    }

    /**
     * tick handler 的调用形状。
     *
     * <p>它<b>不</b>接一条具体事件 —— tick 不是"某件事发生了", 而是"时间往前走了"。
     * 所以递过去的是结算快照: "在你上一次看之后, 账本变成了这样"。
     * 这正是用户描述的那条链的落点:
     * <pre>
     *   账本结算 → 保暖值 -0.02 → 低于适中值 → 阈值检测产生冷刺激
     *                                    ↑
     *                          这个 handler 就在这一步
     * </pre>
     */
    private void invokeTick(EventHandler<?> handler, ContinuousEffectLedger.Settlement settlement,
                            HumanRuntimeContext context) {
        try {
            if (handler instanceof TickAware aware) {
                aware.onTick(settlement, context, this);
            }
        } catch (RuntimeException e) {
            log.error("[EventFabric/{}] tick handler {} 抛异常, 已跳过", humanId, handler.name(), e);
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Override
    public ContinuousEffectLedger effects() {
        return effects;
    }

    @Override
    public RealtimeEventQueue stimuli() {
        return stimuli;
    }

    @Override
    public List<WorldEvent> recentEvents(int limit) {
        return history.stream().limit(Math.max(0, limit)).toList();
    }

    @Override
    public Optional<WorldEvent> findEvent(String eventId) {
        return Optional.ofNullable(byId.get(eventId));
    }

    /** 因因果链过深而打过的 WARN 次数。测试与诊断用。 */
    public int causalDepthWarnings() {
        return causalDepthWarnings.get();
    }

    @Override
    public String describe(Instant at) {
        return "EventFabric[" + humanId + "] " + effects.describe(at)
                + " | " + stimuli.describe();
    }
}
