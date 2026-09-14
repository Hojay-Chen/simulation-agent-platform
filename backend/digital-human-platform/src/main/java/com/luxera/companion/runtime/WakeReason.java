package com.luxera.companion.runtime;

/**
 * Agent 唤醒原因(§74): Runtime 代码路由, 决定某个事件唤醒哪些认知模块。
 * 不允许把 Agent 调度也交给 LLM。
 */
public enum WakeReason {
    /** 用户发来新消息 */
    USER_MESSAGE,
    /** 手机通知出现 */
    NOTIFICATION,
    /** 活动开始 */
    ACTIVITY_STARTED,
    /** 活动结束 */
    ACTIVITY_END,
    /** 环境变化 */
    ENVIRONMENT_CHANGED,
    /** 重要情绪变化 */
    EMOTION_CHANGED,
    /** 排程唤醒(主动消息/复查) */
    SCHEDULED_THOUGHT,
    /** 关系后续跟进 */
    RELATIONSHIP_FOLLOWUP
}
