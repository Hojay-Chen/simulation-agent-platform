package com.luxera.companion.digitalhuman.event;

/**
 * V10 §9.1 外部事件类型。
 *
 * 所有外部刺激统一转换成 ExternalEvent 后进入 Agent Event Bus。
 * 事件示例: DEVICE_NOTIFICATION / CHAT_MESSAGE_DELIVERED / APPLICATION_EVENT /
 * LIFE_EVENT / TIME_EVENT / ENVIRONMENT_EVENT。
 */
public enum ExternalEventType {

    /** 消息已送达(聊天平台投递完成) */
    CHAT_MESSAGE_DELIVERED,

    /** 设备通知(手机通知弹出) */
    DEVICE_NOTIFICATION,

    /** 应用事件(外部应用/游戏/小程序) */
    APPLICATION_EVENT,

    /** 生活事件(日程/活动/计划) */
    LIFE_EVENT,

    /** 时间事件(定时器/节律) */
    TIME_EVENT,

    /** 环境事件(环境状态变化) */
    ENVIRONMENT_EVENT
}
