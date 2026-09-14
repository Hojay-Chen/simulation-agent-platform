package com.luxera.companion.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 运行时事件总线(§2/§5): 世界事件在 Runtime 内部的分发总线。
 * 与 {@code CompanionEventBus}(SSE 推送给前端)不同 —— 这里是认知模块之间的内部通信。
 * 简单订阅/发布; 监听器按事件类型注册。
 */
@Slf4j
@Component
public class RuntimeEventBus {

    private final Map<String, CopyOnWriteArrayList<Consumer<WorldEvent>>> listeners = new ConcurrentHashMap<>();

    public void subscribe(String type, Consumer<WorldEvent> listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void publish(WorldEvent event) {
        if (event == null || event.type() == null) return;
        List<Consumer<WorldEvent>> typeListeners = listeners.get(event.type());
        if (typeListeners != null) {
            for (Consumer<WorldEvent> l : typeListeners) {
                try {
                    l.accept(event);
                } catch (Exception e) {
                    log.warn("RuntimeEventBus 处理 {} 失败: {}", event.type(), e.getMessage());
                }
            }
        }
        // 通用监听(关注所有事件)
        List<Consumer<WorldEvent>> all = listeners.get("*");
        if (all != null) {
            for (Consumer<WorldEvent> l : all) {
                try {
                    l.accept(event);
                } catch (Exception e) {
                    log.warn("RuntimeEventBus(*) 处理 {} 失败: {}", event.type(), e.getMessage());
                }
            }
        }
    }
}
