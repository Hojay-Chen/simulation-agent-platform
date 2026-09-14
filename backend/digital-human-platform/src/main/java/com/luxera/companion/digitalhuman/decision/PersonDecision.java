package com.luxera.companion.digitalhuman.decision;

/**
 * V10 §13.1 Decision(sealed interface): 数字人的行动决策。
 *
 * 决策不是"回/不回"二元, 而是包含"查看设备/延迟回复/改变活动"等
 * 与真人一致的行动空间(State Pattern 心智: 决策驱动状态迁移)。
 */
public sealed interface PersonDecision permits
        PersonDecision.IgnoreDecision,
        PersonDecision.InspectDeviceDecision,
        PersonDecision.ReplyDecision,
        PersonDecision.DelayReplyDecision,
        PersonDecision.ChangeActivityDecision {

    /** 决策理由(追踪/解释) */
    String reason();

    /** 忽略: 不感知/不值得处理 */
    record IgnoreDecision(String reason) implements PersonDecision {}

    /** 查看设备: 值得看一眼(打开会话读取消息) */
    record InspectDeviceDecision(String reason) implements PersonDecision {}

    /** 立即回复 */
    record ReplyDecision(String reason) implements PersonDecision {}

    /** 延迟回复: 看到了但暂时不回(附延迟分钟) */
    record DelayReplyDecision(String reason, int delayMinutes) implements PersonDecision {}

    /** 改变活动: 当前活动被中断/计划变化 */
    record ChangeActivityDecision(String reason, String targetActivity) implements PersonDecision {}
}
