package com.luxera.companion.world.device;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * V2.2 §4.3.4 / §6.1 —— <b>一条通知请求</b>: 某个应用说"我有新消息", 手机决定怎么响。
 *
 * <h2>⚠️ 这里<b>禁止</b>出现 {@code messageContent} / {@code messagePreview}</h2>
 * 这不是代码规范, 是类型层面的事实, 也是 §2.2 的四条断言之一:
 * <blockquote>
 *   第 ④ 步的信号不含正文。<b>类型层面就不含</b> —— 不是"我们不读", 是"装不下"。
 * </blockquote>
 *
 * <p>整条链的因果顺序是:
 * <pre>
 *   ① 聊天平台落库, 判断免打扰(平台自己的设置)
 *   ② 产生 NotificationSignal —— <b>不含正文</b>
 *   ③ WebSocket 推给 ChatApplication
 *   ④ ChatApplication 造出本对象, 交给 Phone
 *   ⑤ Phone 按本机策略决定响不响 → 投一条"手机响了"的世界事件
 *   ⑥ <b>她的 Mind 到目前为止只知道"手机响了一下, 是聊天软件的"</b>
 *   ⑦ 她决定去看 → chat.read-messages → 正文<b>到此才第一次进入认知</b>
 * </pre>
 *
 * <p>第 ⑥ 步与第 ⑦ 步之间不能有任何捷径。为什么这条如此重要, 文档 §1.3 P3 说得很直接:
 * 一旦正文已经在她手里, "她在忙 / 没注意到 / 已读不回"就都只是知道内容之后找的说法 ——
 * 只有让"她没注意到"在<b>结构上</b>成立, 它才可能是一个为真的状态。
 *
 * <h2>{@link #conversationRef} 为什么允许存在</h2>
 * 它是一个<b>坐标</b>, 不是内容: "响的这一下是关于哪个会话的"。手机需要它来做两件事,
 * 而两件事都不涉及正文 —— 按会话记未读数、向聊天平台确认收到信号。
 *
 * <p>但它<b>绝不进事件载荷</b>: {@code device.phone.notification-raised.v1} 的字段里
 * 没有它(见 {@code CoreEventCatalog} 与 {@link NotificationSystem.NotificationRaised}),
 * 而那条事件才是 Mind 看见的东西。所以 Mind 拿到的是"手机响了", 不是"user_8f3a 发消息了" ——
 * <b>"是谁发的"要等她去看</b>。
 *
 * <h2>{@link #reason()} 为什么是字符串而不是枚举</h2>
 * 因为"因为什么而通知"是<b>应用的</b>概念: 聊天应用有"新消息/被@/来电", 日历应用有
 * "日程提醒", 第三方喂食器可能有"粮仓空了"。用枚举表达它, 等于要求平台作者替所有
 * 未来的应用预先想清楚通知的理由 —— 那正是 P4 要消灭的东西。
 * {@link Reasons} 里的常量是<b>平台认识的</b>取值, 不是白名单。
 */
public record NotificationRequest(String requestId,
                                  String applicationKey,
                                  String reason,
                                  String conversationRef,
                                  String soundProfile,
                                  int unreadCount,
                                  Instant occurredAt) {

    /**
     * 平台认识的通知理由 —— <b>常量, 不是白名单</b>。
     *
     * <p>写成一个类而不是枚举的理由与 {@code CoreEventCatalog.Channels} 相同:
     * 三方应用可以带自己的理由进来, 平台不该在类型层面拦住它。
     */
    public static final class Reasons {

        private Reasons() {
        }

        /** 新消息 —— 最常见的那个, 也是"逐条通知, 不聚合"那条规则针对的对象。 */
        public static final String NEW_MESSAGE = "new-message";

        /** 被 @ 了 —— 在群里与你有关。 */
        public static final String MENTION = "mention";

        /** 来电。 */
        public static final String CALL = "call";

        /** 日程/闹钟类提醒 —— 由日历应用发出。 */
        public static final String SCHEDULE = "schedule";

        /** 系统/应用自身的通知 —— 更新、同步完成、连接断开。 */
        public static final String SYSTEM = "system";

        /** 平台认识的全部理由 —— 给文档与诊断用。 */
        public static final java.util.List<String> KNOWN =
                java.util.List.of(NEW_MESSAGE, MENTION, CALL, SCHEDULE, SYSTEM);
    }

    public NotificationRequest {
        requestId = requestId == null || requestId.isBlank()
                ? "notif-" + UUID.randomUUID() : requestId;
        if (applicationKey == null || applicationKey.isBlank()) {
            throw new IllegalArgumentException(
                    "通知请求必须说明是哪个应用要通知 —— 手机的策略是按应用区分的"
                            + "(她允许聊天软件响, 但把购物应用的通知全关了)");
        }
        reason = reason == null || reason.isBlank() ? Reasons.SYSTEM : reason;
        // 通知音是应用自带的音色(见 DeviceApplication.Descriptor.notificationSound)。
        // 把它放进请求里而不是让手机去查应用注册表, 是为了让手机只需要回答
        // "用多大声放", 而不需要认识任何应用 —— 这正是"第三方自己实现接口"的前提。
        soundProfile = soundProfile == null || soundProfile.isBlank() ? "default" : soundProfile;
        if (unreadCount < 0) {
            throw new IllegalArgumentException(
                    "未读数不能为负, 收到 " + unreadCount + " —— 它是「几条还没看」的计数, 不是增量");
        }
        Objects.requireNonNull(occurredAt, "通知请求必须带发生时刻 —— 仿真时钟下不许读墙上时钟");
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 一条新消息的通知 —— 最常用的一条, 刻意给一个不带会话坐标的重载。 */
    public static NotificationRequest newMessage(String applicationKey, String soundProfile,
                                                 String conversationRef, Instant at) {
        return new NotificationRequest(null, applicationKey, Reasons.NEW_MESSAGE,
                conversationRef, soundProfile, 0, at);
    }

    public static NotificationRequest of(String applicationKey, String reason, String conversationRef,
                                         String soundProfile, int unreadCount, Instant at) {
        return new NotificationRequest(null, applicationKey, reason, conversationRef,
                soundProfile, unreadCount, at);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 这条通知有没有会话坐标。
     *
     * <p>没有坐标是合法情形: 系统通知、应用更新提示都不属于任何会话。
     * 调用方<b>必须</b>处理这种情况, 而不是假设坐标永远存在 ——
     * 一个"通知一定来自某个会话"的假设会让第一条系统通知炸出一个 NPE。
     */
    public boolean hasConversation() {
        return conversationRef != null && !conversationRef.isBlank();
    }

    public NotificationRequest withUnreadCount(int value) {
        return new NotificationRequest(requestId, applicationKey, reason, conversationRef,
                soundProfile, value, occurredAt);
    }

    /**
     * 一行摘要 —— <b>可以安全地打进日志</b>。
     *
     * <p>它刻意只打应用与理由: 本类型里根本没有正文, 所以这个方法<b>不可能</b>
     * 泄露正文。这是"装不下"带来的一个额外好处 —— 不需要有人记得别打日志。
     */
    public String describe() {
        return "通知[" + applicationKey + ":" + reason
                + (hasConversation() ? " 会话=" + conversationRef : "")
                + " 未读=" + unreadCount + "]";
    }
}
