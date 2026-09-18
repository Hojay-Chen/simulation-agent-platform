package com.luxera.companion.world;

/**
 * V11 §5.2 —— 事件<b>从哪来</b>。与 {@link AgentEventType}(发生了什么)正交。
 *
 * <p>分成两维是必要的, 因为同一种事可以来自不同地方: 一条 {@code USER_MESSAGE_RECEIVED}
 * 可能来自聊天平台实时推送, 也可能来自模拟器设备的上报; 一个
 * {@code ENVIRONMENT_CHANGED} 可能来自真实传感器, 也可能来自生活模拟。
 * 旧链把来源和类型揉成一个字符串(比如 {@code chat-platform}), 于是
 * "这条消息是实时来的还是补课来的"只能塞在 payload 的一个 {@code phase} 键里。
 *
 * <p>它承接了 {@code world/WorldEvent} 里那组 {@code SRC_*} 常量的语义
 * ({@code SRC_BODY/SRC_LIFE/SRC_SOCIAL/SRC_MEMORY/SRC_INTENTION/SRC_ENV}) ——
 * 那组常量说的是来源不是类型, 这里给它们一个正规的归处。
 */
public enum EventSource {

    /** 聊天平台 —— 消息、会话、应用运行的世界(仓 1)。 */
    CHAT_PLATFORM("CHAT_PLATFORM"),
    /** 应用平台 —— 外部程序通过开放面进来。 */
    APPLICATION_PLATFORM("APPLICATION_PLATFORM"),
    /** 手机/模拟器设备自己的上报。 */
    DEVICE("DEVICE"),
    /** 生活模拟: 日程、活动推进。 */
    LIFE_SIMULATION("LIFE_SIMULATION"),
    /** 时钟/调度器。 */
    SCHEDULE("SCHEDULE"),
    /** 她自己的身体与情绪状态。 */
    BODY("BODY"),
    /** 记忆与反思。 */
    MEMORY("MEMORY"),
    /** 她与人之间的关系。 */
    RELATIONSHIP("RELATIONSHIP"),
    /** 悬而未决的事({@code open_loops})与念头({@code intentions})。 */
    INTENTION("INTENTION"),
    /** 环境(噪音、天气、在场的人)。 */
    ENVIRONMENT("ENVIRONMENT"),
    /** 她自己 —— 主动行为产生的自事件。 */
    SELF("SELF"),
    /** 系统自身(启动、恢复、迁移)。 */
    SYSTEM("SYSTEM");

    private final String wire;

    EventSource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 认不出来返回 {@link #SYSTEM} —— 来源读不懂不该丢事件, 但也不该假装知道它从哪来。 */
    public static EventSource fromWire(String wire) {
        if (wire != null) {
            for (EventSource s : values()) {
                if (s.wire.equalsIgnoreCase(wire.trim())) {
                    return s;
                }
            }
        }
        return SYSTEM;
    }

    /**
     * 这次事件是不是<b>外部世界推进的</b>(而非她自己的生命节律或系统动作)。
     *
     * <p>用在"要不要因为这条事件唤醒她"的判断上: 外部事件倾向于只在被注意到时唤醒,
     * 而 {@link #SCHEDULE} 与 {@link #SELF} 的事件本身就是唤醒。
     */
    public boolean isExternal() {
        return this == CHAT_PLATFORM || this == APPLICATION_PLATFORM
                || this == DEVICE || this == ENVIRONMENT;
    }
}
