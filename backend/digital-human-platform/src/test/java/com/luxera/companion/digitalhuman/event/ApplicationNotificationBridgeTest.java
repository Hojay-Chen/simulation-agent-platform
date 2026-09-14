package com.luxera.companion.digitalhuman.event;

import com.luxera.companion.proactive.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 「应用里发生的事, 什么时候该出现在用户的铃铛里」—— 这个类的全部内容就是这一条判断。
 *
 * <p>它挂在 {@code APPLICATION_EVENT} 上, 但和 {@code AgentApplicationFlow} 看的是同一批事件
 * 的两个不同侧面: 那个问"我该做点什么", 这个问"该不该说给他听"。一条到点事件两个答案都可能
 * 是"是", 也都可能是"否"。
 *
 * <p>用例里没有一处提到提醒 —— 这正是要钉住的性质: {@link ApplicationNotificationBridge}
 * 认识的是 {@code notify} 这个<em>形状</em>, 不是任何一种应用的词汇。谁想给用户看什么,
 * 由应用自己在载荷里说。
 */
class ApplicationNotificationBridgeTest {

    private static final String COMPANION = "companion-1";

    private EventRouter eventRouter;
    private NotificationService notificationService;
    private ApplicationNotificationBridge bridge;

    @BeforeEach
    void setUp() {
        eventRouter = new EventRouter();
        notificationService = mock(NotificationService.class);
        bridge = new ApplicationNotificationBridge(eventRouter, notificationService);
        bridge.registerRoutes();
    }

    @Test
    void aNotifyBlockBecomesANotification() {
        bridge.onApplicationEvent(event("reminder.due", companionId(notify(
                "user-1", "birthday", "今天是她的生日", "祝她生日快乐"))));

        verify(notificationService).notify("user-1", COMPANION, "birthday",
                "今天是她的生日", "祝她生日快乐");
    }

    /** 大多数应用事件不是"要说给用户听的话" —— 创建、完成、取消都不必打扰他。 */
    @Test
    void anEventWithoutANotifyBlockSaysNothing() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", "com.luxera.reminder");
        payload.put("eventType", "reminder.created");
        payload.put("reminderId", "r1");

        bridge.onApplicationEvent(event("reminder.created", payload));

        verifyNoInteractions(notificationService);
    }

    /**
     * {@code type} 是不透明字符串 —— 数字人不去解释它。
     *
     * <p>这一条用一个与提醒毫无关系的分类来证明: 应用想怎么分类是应用的自由, 数字人只负责把它
     * 原样存下来。若哪天有人在桥里加一个 {@code switch (type)} 认识"提醒", 这条用例仍然会绿 ——
     * 但它会挡不住下一个 switch; 真正挡住它的是这个类里根本没有应用词汇。
     */
    @Test
    void theTypeIsCopiedThroughVerbatim() {
        bridge.onApplicationEvent(event("stock.alert", companionId(notify(
                "user-1", "price_alert", "你关注的股票涨了", "已到 120"))));

        verify(notificationService).notify("user-1", COMPANION, "price_alert",
                "你关注的股票涨了", "已到 120");
    }

    /** 分类缺席不该让用户丢掉这条通知 —— 它是标签, 不是内容。 */
    @Test
    void aMissingTypeFallsBackInsteadOfLosingTheNotification() {
        bridge.onApplicationEvent(event("x.y", companionId(notify("user-1", null, "标题", null))));

        verify(notificationService).notify("user-1", COMPANION, "system", "标题", null);
    }

    /** 少了收件人或内容, 这不是"一条通知", 是一段不完整的载荷 —— 说给谁听都不知道, 就只能不说。 */
    @Test
    void anIncompleteNotifyBlockIsIgnored() {
        bridge.onApplicationEvent(event("x.y", companionId(notify(null, "system", "标题", "内容"))));
        bridge.onApplicationEvent(event("x.y", companionId(notify("user-1", "system", null, "内容"))));

        verifyNoInteractions(notificationService);
    }

    /**
     * 订阅是追加语义, 只对 {@code APPLICATION_EVENT} 生效。
     *
     * <p>这里顺带证明的是"链路上真的接上了": 事件不是直接喂给方法的, 而是经
     * {@link EventRouter#route} 走订阅进来的。
     */
    @Test
    void theBridgePicksUpEventsFromTheRouter() {
        eventRouter.route(event("reminder.due", companionId(notify("user-1", "system", "标题", "内容"))));

        verify(notificationService).notify("user-1", COMPANION, "system", "标题", "内容");
    }

    @Test
    void otherEventTypesAreNotOurs() {
        eventRouter.route(new ExternalEvent("e1", COMPANION, ExternalEventType.CHAT_MESSAGE_DELIVERED,
                Instant.now(), companionId(notify("user-1", "system", "标题", "内容")), null));

        verifyNoInteractions(notificationService);
    }

    /** 写通知失败不该把事件链上的其他消费者一起带下水(比如数字人要不要因此行动)。 */
    @Test
    void aFailingNotificationDoesNotBreakTheChain() {
        doThrow(new IllegalStateException("库挂了"))
                .when(notificationService).notify(anyString(), anyString(), anyString(), anyString(), any());

        bridge.onApplicationEvent(event("reminder.due", companionId(notify(
                "user-1", "system", "标题", "内容"))));

        verify(notificationService).notify(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void anEventWithoutACompanionIsNotAddressedToAnyone() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notify", notify("user-1", "system", "标题", "内容"));

        bridge.onApplicationEvent(new ExternalEvent("e1", null, ExternalEventType.APPLICATION_EVENT,
                Instant.now(), payload, null));

        verify(notificationService, never()).notify(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static ExternalEvent event(String eventType, Map<String, Object> payload) {
        return new ExternalEvent("evt-" + eventType, COMPANION, ExternalEventType.APPLICATION_EVENT,
                Instant.now(), payload, null);
    }

    /** 事件载荷 —— 与 {@code DhApplicationEventSink} 交给路由的那个形状一致。 */
    private static Map<String, Object> companionId(Map<String, Object> payload) {
        payload.put("companionId", COMPANION);
        payload.put("agentTrigger", true);
        payload.put("resourceUri", "reminder://owner/user-1");
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> notify(String userId, String type, String title, String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("userId", userId);
        if (type != null) block.put("type", type);
        block.put("title", title);
        if (content != null) block.put("content", content);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notify", block);
        return payload;
    }
}
