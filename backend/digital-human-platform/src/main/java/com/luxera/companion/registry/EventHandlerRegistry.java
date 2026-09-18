package com.luxera.companion.registry;

import com.luxera.companion.boundary.event.EventHandler;
import com.luxera.companion.boundary.event.EventTypeId;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * V2.2 §5.4.6 —— <b>事件类型的消费者索引</b>。
 *
 * <h2>它取代的是什么</h2>
 * 取代一个 {@code switch (eventType)} 分派器。差别不在性能(两者都是查表级),
 * 在于<b>加一个事件的代价</b>:
 * <pre>
 *   switch 分派器:  加事件 → 改分派器 → 重新编译整个平台 → 重新部署
 *                  加消费者 → 改分派器 → 同上
 *   本注册表:       加事件 → 定义类型 + 写 handler + 注册 —— 宿主一行不改
 * </pre>
 *
 * <p>这正是 P4 与验收标准 B("不用修改 EventType 目录的分派逻辑、不用修改 EventFabric、
 * 不用修改 HumanRuntime, 即可投递、路由、被处理")的技术落点。
 *
 * <h2>索引键是订阅键, 不是具体类型</h2>
 * 见 {@link EventTypeId#subscriptionKey()}。一个 handler 订阅
 * {@code environment.temperature-changed} 时, 它同时收到 v1 和 v2 —— 因为
 * "温度变了"这件事不因载荷结构升级而变成另一件事。需要严格版本的 handler
 * 自己在 {@link EventHandler#accepts} 里判。
 *
 * <h2>为什么用 {@link CopyOnWriteArrayList}</h2>
 * 读远多于写: 注册发生在装配期(几十次), 查询发生在每条事件上(每秒可能的几十次)。
 * 而且读路径上<b>不能加锁</b> —— 一条事件的投递路径会横跨 world 与 human 两侧,
 * 在中间拿一把锁会制造出难以复现的死锁(handler A 持锁等待 handler B, 而 B 正在
 * 等 A 释放)。
 *
 * <p>代价是每次注册都复制整个数组。在"注册几十次"这个量级上, 那个代价是零。
 */
@Slf4j
public class EventHandlerRegistry {

    /** 订阅键 → 该键上的消费者, 已按 order 排好。 */
    private final Map<String, List<EventHandler<?>>> bySubscription = new LinkedHashMap<>();

    /** 全部消费者, 供 tick 分派与诊断用。 */
    private final List<EventHandler<?>> all = new CopyOnWriteArrayList<>();

    /**
     * 注册一个消费者。
     *
     * <h3>重复注册</h3>
     * 幂等: 同一个 handler 实例注册两次只有一次生效。理由与
     * {@link com.luxera.companion.boundary.event.EventFabric#registerScheduledSink} 相同 ——
     * 装配代码常常既在构造函数里注册、又在 {@code @PostConstruct} 里注册一遍,
     * 而"于是每条事件被处理两次"是一个会让账本数值翻倍、且完全看不出原因的 bug。
     *
     * <p>判重用<b>引用相等</b>而不是 {@code equals}: handler 一般是有状态的服务对象,
     * 覆盖 {@code equals} 的情况很少; 而两个内容相同但独立的 handler(比如两个不同 agent
     * 的同类检测器)是<b>应该</b>都注册的。
     */
    public void register(EventHandler<?> handler) {
        Objects.requireNonNull(handler, "要注册的 handler 不能为空");
        if (containsIdentity(all, handler)) {
            log.debug("[EventHandlerRegistry] {} 已注册过, 跳过", handler.name());
            return;
        }
        all.add(handler);

        Set<String> keys = handler.subscriptions();
        if (keys == null) {
            throw new IllegalStateException(
                    "handler " + handler.name() + " 的 subscriptions() 返回了 null —— "
                            + "ON_TICK 的 handler 应当返回空集合, 不是 null");
        }
        for (String key : keys) {
            List<EventHandler<?>> list = bySubscription.computeIfAbsent(key,
                    k -> new CopyOnWriteArrayList<>());
            list.add(handler);
            // 保持 order 有序 —— 但只在注册期排一次, 读路径上直接用
            list.sort(Comparator.comparingInt(EventHandler::order));
        }
        log.debug("[EventHandlerRegistry] 注册 {} ({} 个订阅, timing={})",
                handler.name(), keys.size(), handler.timing());
    }

    /** 批量注册, 装配期用。 */
    public void registerAll(Iterable<? extends EventHandler<?>> handlers) {
        handlers.forEach(this::register);
    }

    /**
     * 某类事件的<b>入账型</b>消费者, 已按 order 排序。
     *
     * <p>返回不可变列表: 调用方会遍历它并在遍历中投递新事件, 而投递可能触发注册
     * (一个 handler 在 handle 里注册另一个 handler)。返回活视图会让那个遍历
     * 抛 {@code ConcurrentModificationException} —— 一个只在"有人动态注册"时出现的故障。
     */
    public List<EventHandler<?>> arrivalHandlersFor(EventTypeId typeId) {
        List<EventHandler<?>> candidates = bySubscription.get(typeId.subscriptionKey());
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<EventHandler<?>> out = new ArrayList<>(candidates.size());
        for (EventHandler<?> h : candidates) {
            if (h.timing() == EventHandler.Timing.ON_ARRIVAL && h.accepts(typeId)) {
                out.add(h);
            }
        }
        return List.copyOf(out);
    }

    /** 全部<b>结算型</b>消费者, 已按 order 排序。没有事件时也会被调用。 */
    public List<EventHandler<?>> tickHandlers() {
        List<EventHandler<?>> out = new ArrayList<>();
        for (EventHandler<?> h : all) {
            if (h.timing() == EventHandler.Timing.ON_TICK) {
                out.add(h);
            }
        }
        out.sort(Comparator.comparingInt(EventHandler::order));
        return List.copyOf(out);
    }

    /** 注册进来的全部消费者。诊断用。 */
    public List<EventHandler<?>> all() {
        return List.copyOf(all);
    }

    /** 被订阅的全部类型键。诊断面板用它回答"这个 agent 对什么有反应"。 */
    public Set<String> subscribedKeys() {
        return Set.copyOf(bySubscription.keySet());
    }

    /**
     * 一条事件有没有消费者。
     *
     * <p>给审计用: 一条<b>谁都不关心</b>的事件通常意味着两件事之一 ——
     * 要么它是留给将来的({@code system.clock-tick.v1} 就属于这种), 要么
     * 有个 handler 的订阅键写错了。这个方法让那个区别可以被查出来。
     */
    public boolean hasConsumer(EventTypeId typeId) {
        return !arrivalHandlersFor(typeId).isEmpty();
    }

    /** 清空 —— 只给测试用。 */
    public void clear() {
        bySubscription.clear();
        all.clear();
    }

    private static boolean containsIdentity(List<EventHandler<?>> list, EventHandler<?> handler) {
        for (EventHandler<?> h : list) {
            if (h == handler) {
                return true;
            }
        }
        return false;
    }

    /** 一行摘要, 给诊断面板用。 */
    public String describe() {
        return "EventHandlerRegistry(" + all.size() + " 个 handler, "
                + bySubscription.size() + " 个订阅键, " + tickHandlers().size() + " 个 tick 型)";
    }
}
