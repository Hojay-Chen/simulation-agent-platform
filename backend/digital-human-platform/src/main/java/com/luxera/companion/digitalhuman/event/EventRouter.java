package com.luxera.companion.digitalhuman.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * V10 §9.2 Event Router: 事件类型 → 处理回调 的路由注册表(Registry Pattern)。
 *
 * 各 Runtime(Perception/Life/Conversation...) 在启动时注册自己关心的事件类型;
 * 链的 RoutingHandler 通过本注册表把事件转交到正确的 Runtime。
 * 回调在 Person Actor 的串行上下文中执行(由调用方保证), 保证单 Person 状态串行。
 *
 * <h2>两种注册语义, 刻意不同(LAP v1 R2 引入)</h2>
 * <ul>
 *   <li>{@link #register} —— <b>覆盖</b>语义: 一个类型只有一个"主处理者", 重复注册即替换。
 *       这是本类原有的语义, 必须保持: {@code EventProcessingChainTest} 与
 *       {@code OutboxRelayTest} 都用它注册自己的计数器并断言"恰好一次"; 若改成追加,
 *       它们会连带触发生产订阅者, 计数翻倍。</li>
 *   <li>{@link #subscribe} —— <b>追加</b>语义: 主处理者之外再挂一个观察者, 返回句柄可取消。
 *       {@code AgentApplicationFlow} 用它挂到 {@code APPLICATION_EVENT} 上, 而不去抢
 *       {@code AgentRuntime} 的主路由。</li>
 * </ul>
 *
 * <p>{@link #unregister} 只撤销 {@link #register} 的主处理者, <b>不动订阅者</b> ——
 * 否则测试用例的 {@code @AfterEach} 清理会把生产订阅者一起摘掉, 且悄无声息。
 */
@Slf4j
@Component
public class EventRouter {

    /** 每类型唯一的主处理者(覆盖语义) */
    private final Map<ExternalEventType, Consumer<ExternalEvent>> primaryRoutes = new ConcurrentHashMap<>();

    /** 主处理者之外追加的订阅者(fan-out, 追加语义) */
    private final Map<ExternalEventType, CopyOnWriteArrayList<Consumer<ExternalEvent>>> subscribers =
            new ConcurrentHashMap<>();

    /** 订阅句柄 */
    @FunctionalInterface
    public interface EventSubscription {
        void cancel();
    }

    /** 注册某类型事件的主处理回调(重复注册覆盖, 不影响订阅者) */
    public void register(ExternalEventType type, Consumer<ExternalEvent> handler) {
        primaryRoutes.put(type, handler);
        log.debug("[EventRouter] 注册路由: {}", type);
    }

    /**
     * 追加一个订阅者(与主处理者并存, 主处理者先执行)。
     * 返回的句柄用于取消订阅, 而不是 {@link #unregister}(后者只管主处理者)。
     */
    public EventSubscription subscribe(ExternalEventType type, Consumer<ExternalEvent> handler) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("[EventRouter] 追加订阅: {}", type);
        return () -> subscribers.computeIfPresent(type, (k, list) -> {
            list.remove(handler);
            return list.isEmpty() ? null : list;
        });
    }

    /** 撤销主处理者(订阅者保留) */
    public void unregister(ExternalEventType type) {
        primaryRoutes.remove(type);
    }

    /** 是否存在该类型的路由(主处理者或任一订阅者) */
    public boolean hasRoute(ExternalEventType type) {
        if (primaryRoutes.containsKey(type)) return true;
        List<Consumer<ExternalEvent>> list = subscribers.get(type);
        return list != null && !list.isEmpty();
    }

    /** 路由并消费事件; 返回 false 表示无路由 */
    public boolean route(ExternalEvent event) {
        List<Consumer<ExternalEvent>> handlers = handlersFor(event);
        if (handlers.isEmpty()) {
            return false;
        }
        for (Consumer<ExternalEvent> handler : handlers) {
            handler.accept(event);
        }
        return true;
    }

    /** 主处理者在前, 订阅者按注册顺序在后 */
    private List<Consumer<ExternalEvent>> handlersFor(ExternalEvent event) {
        List<Consumer<ExternalEvent>> handlers = new ArrayList<>(2);
        Consumer<ExternalEvent> primary = primaryRoutes.get(event.type());
        if (primary != null) handlers.add(primary);
        List<Consumer<ExternalEvent>> extra = subscribers.get(event.type());
        if (extra != null) handlers.addAll(extra);
        return handlers;
    }
}
