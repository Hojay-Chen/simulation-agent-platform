package com.luxera.companion.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 排程动作分发器(§66/§100): 到期动作按类型分发给处理器。
 * 处理器由各模块注册(消息复查/主动消息/事件模拟), Runtime 不关心具体实现。
 */
@Slf4j
@Component
public class ScheduledActionDispatcher {

    /** 处理器: 处理一个到期动作, 返回是否成功(成功则标记 DONE) */
    public interface ActionHandler {
        boolean handle(ScheduledAction action);
    }

    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();

    public void register(String actionType, ActionHandler handler) {
        handlers.put(actionType, handler);
    }

    public boolean dispatch(ScheduledAction action) {
        ActionHandler handler = handlers.get(action.getActionType());
        if (handler == null) {
            log.debug("无处理器: actionType={}", action.getActionType());
            return false;
        }
        try {
            return handler.handle(action);
        } catch (Exception e) {
            log.warn("排程动作处理失败: type={} id={}: {}", action.getActionType(), action.getId(), e.getMessage());
            return false;
        }
    }
}
