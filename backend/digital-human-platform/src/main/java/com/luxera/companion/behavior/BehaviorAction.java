package com.luxera.companion.behavior;

/**
 * §三十五~§三十八 BehaviorAction: 数字人的全部行为候选。
 *
 * 主动联系用户只是其中一个候选, 不是"主动消息模块"。
 * 睡觉/看手机/继续生活/联系其他人物/想起某事 都是同一级的行为候选,
 * 由 BehaviorEngine 统一打分 + 随机选择。
 */
public enum BehaviorAction {

    /** 继续当前生活活动(默认) */
    CONTINUE_ACTIVITY,

    /** 换一件生活上的事做(内部, 影响生活上下文) */
    CHANGE_ACTIVITY,

    /** 看手机(可能看到通知) */
    CHECK_PHONE,

    /** 主动联系用户 */
    SEND_PROACTIVE_MESSAGE,

    /** 与其他人互动(数字人的社会网络, 内部生活) */
    CONTACT_OTHER_PERSON,

    /** 睡觉 */
    SLEEP,

    /** 保持清醒(深夜聊天/有重要事硬撑) */
    STAY_AWAKE,

    /** 休息 */
    REST,

    /** 想起某件未完成的事(内部) */
    RECALL_MEMORY,

    /** 什么都不做, 只是活着 */
    DO_NOTHING
}
