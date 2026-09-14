package com.luxera.companion.runtime;

import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.runtime.agent.emotion.EmotionAgent;
import com.luxera.companion.runtime.agent.event.EventSimulationAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.memory.MemoryAgent;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Agent 注册(§52/§53): 启动时把认知 Agent 注册进 AgentRegistry。
 * 技能注入按 Agent 类型固定(见 skills/), 不让 Agent 自己决定加载什么。
 */
@Component
public class AgentConfig {

    private final AgentRegistry registry;
    private final EmotionAgent emotionAgent;
    private final BrainAgent brainAgent;
    private final MemoryAgent memoryAgent;
    private final ExpressionAgent expressionAgent;
    private final EventSimulationAgent eventSimulationAgent;

    public AgentConfig(AgentRegistry registry, EmotionAgent emotionAgent, BrainAgent brainAgent,
                         MemoryAgent memoryAgent, ExpressionAgent expressionAgent,
                         EventSimulationAgent eventSimulationAgent) {
        this.registry = registry;
        this.emotionAgent = emotionAgent;
        this.brainAgent = brainAgent;
        this.memoryAgent = memoryAgent;
        this.expressionAgent = expressionAgent;
        this.eventSimulationAgent = eventSimulationAgent;
    }

    @PostConstruct
    public void register() {
        registry.register(EmotionAgent.NAME, emotionAgent);
        registry.register(BrainAgent.NAME, brainAgent);
        registry.register(MemoryAgent.NAME, memoryAgent);
        registry.register(ExpressionAgent.NAME, expressionAgent);
        registry.register(EventSimulationAgent.NAME, eventSimulationAgent);
    }
}
