package com.luxera.companion.boundary.action;

import java.util.Map;
import java.util.Optional;

/**
 * V2.2 §4.2 / §5.7 —— <b>世界对象对外提供的一项能力</b>。
 *
 * <h2>它是用户那句"让第三方自己实现接口"的落点</h2>
 * 用户的原话:
 * <blockquote>
 *   手机对象还可以有"手机应用"这个类对象, 实现一个表示"手机应用"的接口,
 *   然后把要让 agent 能够使用的三方平台软件, 让他们自己来实现这个"手机应用"接口的
 *   实现类, 去对接他们的软件 api, 包括我们自己的聊天平台也是同样道理。
 * </blockquote>
 *
 * <p>把这句话里的"手机应用"换成"能力", 就是本接口。<b>宿主不认识实现类</b> ——
 * 它只认识 {@link CapabilityDescriptor}(用来给 LLM 看)和 {@link #invoke}
 * (用来真的做)。一个第三方要接入, 需要提供的恰好是这两样, 一行宿主代码都不用改。
 *
 * <h2>为什么 {@code invoke} 不抛异常</h2>
 * 见 {@link ActionResult} 类注释。手机没电、没信号、对方注销了账号 ——
 * 这些都不是异常, 是她生活的一部分。一个抛异常的接口会逼着每个调用点写
 * try/catch, 而写出来的 catch 几乎总是"记一条日志然后吞掉" ——
 * 那等于把"她错过了什么"变成不可见的。
 *
 * <h2>{@link #invoke} 必须是<b>幂等友好</b>的</h2>
 * 若 {@link ActionCommand#idempotent()} 为真, 实现<b>必须</b>保证同一个
 * {@code idempotencyKey} 的重复调用只产生一次副作用 —— 或者明确拒绝
 * ({@link ActionResult#rejected})。假装支持而实际上每次都执行, 比不支持更糟:
 * 它会让重试逻辑在测试环境看起来完全正常, 在生产里静默地发两遍消息。
 *
 * <h2>线程模型</h2>
 * 同一个 agent 的能力调用是串行的(单线程 tick)。<b>不要在这里加锁</b> ——
 * 一个需要锁的能力说明它的状态被两个 agent 共享了, 而那本身就是设计错误。
 * 真正跨 agent 共享的资源(SQL 连接、HTTP 客户端)应当在更下层处理。
 */
public interface Capability {

    /**
     * 这份能力的自描述。
     *
     * <p>会被<b>频繁</b>调用(每次给 LLM 拼工具清单、每次前端渲染能力面板)。
     * 实现应当返回一个缓存的不可变对象, 而不是每次新建 —— 一个在热路径上
     * 构造 Map 的 descriptor 会让"她有 200 个能力"变成一次明显的卡顿。
     */
    CapabilityDescriptor descriptor();

    /**
     * 执行。
     *
     * @param command 命令。实现<b>应当</b>校验参数, 而不是假设调用方传对了 ——
     *                调用方可能是 LLM, 而 LLM 会传错参数, 这是它的正常工作方式
     * @param context 执行上下文: 谁、几点、能用哪些别的东西
     */
    ActionResult invoke(ActionCommand command, CapabilityContext context);

    /**
     * 这个能力是否可用于给定的 actor。
     *
     * <p>默认全部可用。覆盖它的典型场景是"这台设备只属于某个 agent" ——
     * 但那种判断更适合放在 {@link ActionFabric} 的授权层, 因为把授权散在每个
     * 能力实现里, 会得到 N 份各自漂移的授权逻辑。
     *
     * <p>保留这个方法的位置是给"这个能力只对某些类型的 actor 有意义"用的
     * (比如"签字"对没有手的对象没有意义), 而不是给权限用的。
     */
    default boolean availableTo(String actorId) {
        return true;
    }

    /** 这个能力当前是否可用(设备在线、服务没挂)。默认可用。 */
    default boolean currentlyAvailable(CapabilityContext context) {
        return true;
    }

    /**
     * 能力 key 的便捷读取 —— 它就是 {@code descriptor().key()}。
     *
     * <p>定义在这里而不是让调用方写 {@code cap.descriptor().key()}, 是因为
     * 这个调用出现在日志、授权、查找等很多地方。少一层解引用不会让代码更难懂,
     * 但会让"能力"这个概念在代码里的出现频率更接近它在设计里的地位。
     */
    default String key() {
        return descriptor().key();
    }

    /**
     * 执行上下文。
     *
     * @param actorId       谁在调用
     * @param now           仿真时刻 —— 与 {@code HumanRuntimeContext} 同一条纪律,
     *                      能力实现不许读墙上时钟
     * @param environment   只读的旁路数据: 设备状态、会话信息。刻意是
     *                      <b>不可变 Map</b> 而不是一个强类型对象, 因为第三方能力
     *                      不该依赖宿主内部类型 —— 那正是"第三方自己实现接口"的前提
     */
    record CapabilityContext(String actorId,
                             java.time.Instant now,
                             Map<String, Object> environment) {

        public CapabilityContext {
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("能力执行上下文必须知道是谁在调用");
            }
            if (now == null) {
                throw new IllegalArgumentException("能力执行上下文必须带仿真时刻");
            }
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }

        public static CapabilityContext of(String actorId, java.time.Instant now) {
            return new CapabilityContext(actorId, now, Map.of());
        }

        public Optional<Object> lookup(String key) {
            return Optional.ofNullable(environment.get(key));
        }

        public CapabilityContext with(String key, Object value) {
            Map<String, Object> merged = new java.util.LinkedHashMap<>(environment);
            merged.put(key, value);
            return new CapabilityContext(actorId, now, merged);
        }
    }
}
