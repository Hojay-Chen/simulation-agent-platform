package com.luxera.companion.digitalhuman.life;

/**
 * V10 §7.4 LifeEventType: 可排程的生活事件类型。
 *
 * 生活不是"每 tick 猜她在干嘛", 而是事件驱动 + 时间触发:
 * 活动/计划有明确的起止时间 → 排程到点事件 → 到点执行。
 */
public enum LifeEventType {

    /** 活动结束(排程于活动 plannedEnd; 到点幂等收尾当前活动) */
    ACTIVITY_END,

    /** 计划提醒(排程于计划执行时间; 到点激活计划, 后续轮次接入) */
    PLAN_REMINDER
}
