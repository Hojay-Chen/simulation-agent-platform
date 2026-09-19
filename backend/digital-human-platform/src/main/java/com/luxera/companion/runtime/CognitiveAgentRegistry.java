package com.luxera.companion.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认知处理器注册表(§52/§53): 按名称登记 V11 认知链上的处理器(情感/大脑/记忆/表达/事件模拟)。
 * Runtime 按处理器类型固定注入技能, 不让处理器自己决定加载什么。
 *
 * <h2>为什么叫 Cognitive..., 而不是就叫 AgentRegistry</h2>
 *
 * 因为 V2.2 的 {@code AgentRegistry} 是<b>另一个东西</b>, 而且它更需要那个名字:
 * 这里登记的是"链路上装了哪几个处理器", 那里登记的是"平台上有哪几个数字人"
 * —— 后者才是产品语义上的 Agent, 前者是它的零件。
 *
 * <p>名字的归属不是审美问题: 两个类在同一个包里, 同名就编译不过。所以留在这里的这一份
 * 必须换一个诚实的名字 —— 而不是给新来的那个让路时把自己叫成 {@code LegacyAgentRegistry}。
 * "Cognitive" 说的是它装的是什么, 这个描述与 V11 活不活着无关。
 *
 * <p>V11 整代被删除时(§8.6.3 之后的清理), 这个类跟着它一起走 —— 它不是 V2.2 的兼容层,
 * 也没有任何 V2.2 的代码引用它。
 */
@Component
public class CognitiveAgentRegistry {

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
