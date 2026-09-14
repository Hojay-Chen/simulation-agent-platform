package com.luxera.companion.digitalhuman.event;

import com.luxera.companion.proactive.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.Map;

/**
 * LAP v1 §12: 「应用里发生了一件用户该看见的事」→ 铃铛里的一条通知。
 *
 * <p>提醒到点是它的第一个用户, 但这个类里没有一个字提到提醒。应用在自己的事件载荷里放一个
 * {@code notify} 块, 说的就是"这件事值得出现在用户面前, 它长这样":
 *
 * <pre>
 *   "notify": { "userId": "...", "type": "birthday", "title": "今天是她的生日", "content": "..." }
 * </pre>
 *
 * <p>于是两件事同时成立: 应用可以把任何东西变成通知(不必是提醒), 而数字人始终不认识
 * <em>任何一种</em>应用的具体词汇 —— 它只认识"有个 notify 块"这一个形状。{@code type}
 * 是原样透传的不透明字符串, 数字人不去解释它; 谁想给用户看什么分类, 由应用自己决定。
 *
 * <p>与 {@link DhApplicationEventSink} 的分工: 那个类管"事件怎么进数字人世界"(信封翻译、
 * 幂等、两级闸门), 这个类管"进来之后要不要说给用户听"。两者都挂在
 * {@code APPLICATION_EVENT} 上, 而 {@link EventRouter#subscribe} 是追加语义 ——
 * 所以 {@code AgentApplicationFlow} 那条"数字人自己要不要行动"的路径不受影响:
 * 一条到点事件既可能被说出来, 也可能被做点什么, 也可能两者都不。
 *
 * <p><b>本类与 {@link DhApplicationEventSink} 一样永久留在 digital-human-platform</b>:
 * "通知"是数字人的概念, 应用没有通知表, 也不该有。
 */
@Slf4j
@Component
public class ApplicationNotificationBridge {

    private static final String NOTIFY_BLOCK = "notify";

    private final EventRouter eventRouter;
    private final NotificationService notificationService;

    public ApplicationNotificationBridge(EventRouter eventRouter, NotificationService notificationService) {
        this.eventRouter = eventRouter;
        this.notificationService = notificationService;
    }

    @PostConstruct
    void registerRoutes() {
        eventRouter.subscribe(ExternalEventType.APPLICATION_EVENT, this::onApplicationEvent);
        log.info("[AppNotificationBridge] 已订阅 APPLICATION_EVENT");
    }

    void onApplicationEvent(ExternalEvent event) {
        if (event == null || !ExternalEventType.APPLICATION_EVENT.equals(event.type())) {
            return;
        }
        Map<String, Object> notify = block(event);
        if (notify == null) {
            return;   // 大多数应用事件不是"要说给用户听的话" —— 创建、完成、取消都不必打扰他
        }
        String userId = text(notify.get("userId"));
        String companionId = event.personId();
        String title = text(notify.get("title"));
        if (userId == null || companionId == null || title == null) {
            log.warn("[AppNotificationBridge] notify 块不完整(userId={}, companionId={}, title={}), 已忽略: {}",
                    userId, companionId, title, event.eventId());
            return;
        }
        // type 缺失不该让用户丢掉这条通知 —— 它是分类标签, 不是内容。缺了就归到 system。
        String type = text(notify.get("type"));
        if (type == null) {
            type = "system";
        }
        try {
            notificationService.notify(userId, companionId, type, title,
                    text(notify.get("content")));
            log.info("[AppNotificationBridge] 通知已送达 {}: {}", companionId, title);
        } catch (Exception e) {
            // 通知发不出去不该影响事件链上的其他消费者(比如数字人要不要因此行动)
            log.warn("[AppNotificationBridge] 写通知失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> block(ExternalEvent event) {
        Object value = event.get(NOTIFY_BLOCK);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return StringUtils.hasText(s) ? s : null;
    }
}
