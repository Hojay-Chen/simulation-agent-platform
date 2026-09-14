package com.luxera.companion.digitalhuman.reality;

/**
 * V10 §8.2 Reality Event 类型: 数字人真实发生过的事实。
 *
 * 事实变化(如 PlanChanged / ActivityEnded)不修改旧事件, 而是写入新事件 ——
 * append-only 语义由 RealityLedger 保证。
 */
public enum RealityEventType {

    /** 数字人发送了一条消息 */
    MESSAGE_SENT,

    /** 数字人读到消息(看到了内容) */
    MESSAGE_READ,

    /** 数字人决定延迟回复 */
    MESSAGE_DEFERRED,

    /** 数字人决定忽略消息 */
    MESSAGE_IGNORED,

    /** 活动开始/结束 */
    ACTIVITY_STARTED,
    ACTIVITY_ENDED,

    /** 计划生命周期 */
    PLAN_CREATED,
    PLAN_CHANGED,
    PLAN_EXECUTED,
    PLAN_COMPLETED,
    PLAN_CANCELLED,
    PLAN_POSTPONED,

    /** 关系变化 */
    RELATIONSHIP_CHANGED,

    /** 应用内行动(如游戏落子) —— V10 §9.3 */
    APPLICATION_ACTION_EXECUTED,

    /**
     * 数字人接受了别人的邀请, 进了那一场 (LAP v2 R13)。
     *
     * <p>为什么"接受"要单独记一笔: 它是数字人<em>自己做的决定</em>, 而不是某条事件顺下来的后果。
     * 账本回答的问题是"这个数字人真实经历过什么", 而"他答应过谁"是这个问题里最要紧的那一类。
     */
    APPLICATION_INVITATION_ACCEPTED,

    /**
     * 数字人谢绝了别人的邀请 (LAP v2 R13)。
     *
     * <p>谢绝与忽略的分别就是这一笔账: "他说了不"是一个决定, "他没吭声"(LLM 不可用时)不是。
     * 只把前者写进账本, 才不会让"这个数字人拒绝过谁"被一次服务抖动污染。
     */
    APPLICATION_INVITATION_DECLINED,

    /** 通用生活事件 */
    LIFE_EVENT
}
