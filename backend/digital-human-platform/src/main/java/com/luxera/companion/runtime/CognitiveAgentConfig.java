package com.luxera.companion.runtime;

import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.runtime.agent.emotion.EmotionAgent;
import com.luxera.companion.runtime.agent.event.EventSimulationAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.memory.MemoryAgent;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 认知处理器注册(§52/§53): 启动时把这五个处理器登记进 {@link CognitiveAgentRegistry}。
 * 技能注入按处理器类型固定(见 skills/), 不让处理器自己决定加载什么。
 *
 * <p>这是一张<b>编译期写死的清单</b> —— 字面就是"V11 认知链有哪五个环节"。
 * 运维面上那块"认知链上真正在跑的处理器"读的就是它登记后的结果, 所以少写一行
 * 的表现是"某个环节整条不工作", 而别处只会看到一条事件没有反应。改这个类之前
 * 先看 {@code /v5/agents} 的实际返回。
 */
@Component
public class CognitiveAgentConfig {

    private final CognitiveAgentRegistry registry;
    private final EmotionAgent emotionAgent;
    private final BrainAgent brainAgent;
    private final MemoryAgent memoryAgent;
    private final ExpressionAgent expressionAgent;
    private final EventSimulationAgent eventSimulationAgent;

    public CognitiveAgentConfig(CognitiveAgentRegistry registry, EmotionAgent emotionAgent, BrainAgent brainAgent,
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
