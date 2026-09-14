package com.luxera.companion.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表(§52/§53): 按名称注册认知 Agent。
 * Runtime 按 Agent 类型固定注入技能, 不让 Agent 自己决定加载什么。
 */
@Component
public class AgentRegistry {

    private final Map<String, Agent<?, ?>> agents = new ConcurrentHashMap<>();

    public void register(String name, Agent<?, ?> agent) {
        agents.put(name, agent);
    }

    @SuppressWarnings("unchecked")
    public <I, O> Agent<I, O> get(String name) {
        return (Agent<I, O>) agents.get(name);
    }

    public boolean contains(String name) {
        return agents.containsKey(name);
    }

    public Map<String, Agent<?, ?>> all() {
        return agents;
    }
}
