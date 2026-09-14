package com.luxera.companion.runtime;

/**
 * 世界事件类型(§62): 进入世界状态的事件。
 * 由 Runtime 产生并广播, 认知层按需响应(不是每个事件都唤醒所有 Agent)。
 */
public final class WorldEventType {

    public static final String USER_MESSAGE_RECEIVED = "USER_MESSAGE_RECEIVED";
    public static final String USER_MESSAGE_NOTIFIED = "USER_MESSAGE_NOTIFIED";
    public static final String USER_MESSAGE_NOTICED = "USER_MESSAGE_NOTICED";
    public static final String USER_MESSAGE_READ = "USER_MESSAGE_READ";
    public static final String USER_MESSAGE_DEFERRED = "USER_MESSAGE_DEFERRED";
    public static final String ACTIVITY_STARTED = "ACTIVITY_STARTED";
    public static final String ACTIVITY_ENDED = "ACTIVITY_ENDED";
    public static final String ACTIVITY_PROGRESS = "ACTIVITY_PROGRESS";
    public static final String ENVIRONMENT_CHANGED = "ENVIRONMENT_CHANGED";
    public static final String EMOTION_CHANGED = "EMOTION_CHANGED";
    public static final String SCHEDULED_WAKEUP = "SCHEDULED_WAKEUP";
    public static final String WORLD_EVENT_OCCURRED = "WORLD_EVENT_OCCURRED";
    public static final String THOUGHT_FORMED = "THOUGHT_FORMED";
    public static final String RELATIONSHIP_CHANGED = "RELATIONSHIP_CHANGED";

    private WorldEventType() {
    }
}
