package com.luxera.companion.digitalhuman.life;

/**
 * V10 §7.4 LifeEventScheduler: 生活事件排程器。
 *
 * 不要"每秒钟调用 LLM 判断 Agent 在干嘛" —— 使用 Event Driven + Time Trigger:
 * 活动/计划开始时排程到点事件, 到点由 LifeScheduleJob 派发执行。
 */
public interface LifeEventScheduler {

    /** 排程一条生活事件(幂等: 同 scheduleId 不重复) */
    void schedule(ScheduledLifeEvent event);

    /** 取消一条已排程事件 */
    void cancel(String scheduleId);
}
