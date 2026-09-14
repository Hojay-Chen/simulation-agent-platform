package com.luxera.companion.state;

/**
 * 伴侣可用状态(P1 §四十一~四十二): 她不是永远在线。
 * Busy ≠ 不回复 —— 影响的是延迟/长度/追问/注意力, 而不是 canReply=false。
 */
public enum CompanionAvailability {
    /** 空闲, 可以好好聊 */
    AVAILABLE,
    /** 在忙(工作等), 回复更慢更短 */
    BUSY,
    /** 有点走神/累, 回复短但不会不回 */
    DISTRACTED,
    /** 在休息/低电量 */
    RESTING,
    /** 在睡觉(不主动打扰) */
    SLEEPING,
    /** 在和朋友/家人待着 */
    SOCIALIZING,
    /** 在路上/旅行 */
    TRAVELING
}
