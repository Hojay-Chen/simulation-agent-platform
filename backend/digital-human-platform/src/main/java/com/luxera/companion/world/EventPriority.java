package com.luxera.companion.world;

/**
 * V11 §5.2 —— 事件有多<b>值得打断她</b>。
 *
 * <p>这不是"重要性打分"(那是认知层算出来的, 见 {@code DecisionContext.importance}),
 * 而是<b>产生事件的一方对它的先验判断</b>: 一条来自陌生人的群消息和一个来自她的
 * 重要的人的电话, 在还没经过任何感知之前就该有不同的待遇。旧链没有这一维,
 * 所有事件平等地挤进同一个 FIFO, 于是"她正在开会"这个事实只能在后面补救。
 *
 * <p>与意识阶梯的关系: 优先级决定<b>要不要送进感知</b>, 感知决定<b>她注意到没有</b>。
 * 两级都不能省 —— 把优先级当成"一定会注意到"正是旧链的错。
 */
public enum EventPriority {

    /**
     * 背景噪音。多数世界事实是这个级别 —— 它们进入信箱, 参与世界状态,
     * 但<b>不该为它们唤醒任何人</b>。
     */
    AMBIENT(0),
    /** 普通事件。默认值。 */
    NORMAL(1),
    /** 重要: 值得在她忙完时被想起来。 */
    IMPORTANT(2),
    /**
     * 紧急: 有资格打断她正在做的事。
     *
     * <p>即便如此也不是"一定回复" —— 她的生命不由事件驱动, 这里给的只是
     * "允许打扰"的许可, 不是"必须照办"的命令(设计文档 §2.1)。
     */
    URGENT(3);

    private final int weight;

    EventPriority(int weight) {
        this.weight = weight;
    }

    /** 数值化, 供信箱排序与阈值比较。 */
    public int weight() {
        return weight;
    }

    /** 是否够格打断当前活动。 */
    public boolean mayInterrupt() {
        return this == URGENT;
    }

    public static EventPriority fromWire(String wire) {
        if (wire != null) {
            for (EventPriority p : values()) {
                if (p.name().equalsIgnoreCase(wire.trim())) {
                    return p;
                }
            }
        }
        return NORMAL;
    }
}
