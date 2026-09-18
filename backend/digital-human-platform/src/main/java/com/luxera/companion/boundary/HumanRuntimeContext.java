package com.luxera.companion.boundary;

import java.time.Instant;

/**
 * V2.2 §5.1 —— handler 在处理一条事件时能看到的<b>上下文</b>。
 *
 * <h2>它刻意很小</h2>
 * 只有"这是谁"和"现在几点"。这不是懒, 是边界纪律:
 * {@link com.luxera.companion.boundary.event.EventHandler} 住在 {@code boundary/} 包里,
 * 而 {@code boundary/} <b>不许</b>依赖 {@code human/} 或 {@code world/} —— 否则两边
 * 都会经由边界间接依赖对方, ArchUnit 的 §8.2.7 两条规则会红。
 *
 * <p>所以本类不能持有 {@code Human} 引用。需要 Human 内部状态(她的保暖值、她正在做什么)
 * 的 handler, 应当住在 {@code human/} 包里并自己注入那些服务 —— <b>是 handler 去拿
 * Human, 不是边界把 Human 递给 handler</b>。依赖方向差一步, 得到的架构完全不同。
 *
 * <h2>为什么时钟在这里</h2>
 * 仿真时钟可以跑得比真实时间快或慢。一个 handler 若自己调 {@code Instant.now()},
 * 就把"把仿真加速 60 倍"变成了一个会改变她行为结果的开关 —— 而那是仿真系统里
 * 最不该存在的耦合。所有时间都从上下文里拿。
 */
public record HumanRuntimeContext(String humanId, Instant now) {

    public HumanRuntimeContext {
        if (humanId == null || humanId.isBlank()) {
            throw new IllegalArgumentException("运行时上下文必须知道这是谁的 —— 否则 handler 无法区分对象");
        }
        if (now == null) {
            throw new IllegalArgumentException(
                    "运行时上下文必须带仿真时刻 —— handler 不许读墙上时钟, "
                            + "否则加速仿真会改变行为结果");
        }
    }

    public static HumanRuntimeContext at(String humanId, Instant now) {
        return new HumanRuntimeContext(humanId, now);
    }

    /** 推进一步之后的上下文 —— 给 tick 循环用。 */
    public HumanRuntimeContext advancedTo(Instant later) {
        return new HumanRuntimeContext(humanId, later);
    }
}
