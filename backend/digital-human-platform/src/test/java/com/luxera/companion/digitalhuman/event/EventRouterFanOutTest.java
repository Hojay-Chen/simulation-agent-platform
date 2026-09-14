package com.luxera.companion.digitalhuman.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAP v1 R2: {@link EventRouter} 的两种注册语义。
 *
 * 这不是"顺手补的覆盖" —— 它守的是一条会让 294 个既有测试悄悄失真的性质:
 * {@code register} 是覆盖语义, {@code subscribe} 是追加语义; 若把 register 改成追加,
 * {@code EventProcessingChainTest} 与 {@code OutboxRelayTest} 的计数器会翻倍。
 */
class EventRouterFanOutTest {

    private static ExternalEvent event(ExternalEventType type) {
        return ExternalEvent.of("person-1", type, Map.of("source", "test"));
    }

    @Test
    void primaryAndSubscriberBothFire_primaryFirst() {
        EventRouter router = new EventRouter();
        List<String> order = new ArrayList<>();
        router.register(ExternalEventType.APPLICATION_EVENT, e -> order.add("primary"));
        router.subscribe(ExternalEventType.APPLICATION_EVENT, e -> order.add("subscriber"));

        assertTrue(router.route(event(ExternalEventType.APPLICATION_EVENT)));
        assertEquals(List.of("primary", "subscriber"), order);
    }

    @Test
    void registerStaysOverwrite_soExistingTestsKeepCountingOnce() {
        EventRouter router = new EventRouter();
        AtomicInteger count = new AtomicInteger();
        router.register(ExternalEventType.APPLICATION_EVENT, e -> count.incrementAndGet());
        router.register(ExternalEventType.APPLICATION_EVENT, e -> count.incrementAndGet());

        router.route(event(ExternalEventType.APPLICATION_EVENT));
        assertEquals(1, count.get(), "register 必须保持覆盖语义, 否则既有计数断言全部翻倍");
    }

    @Test
    void unregisterRemovesOnlyThePrimary() {
        EventRouter router = new EventRouter();
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger subscriber = new AtomicInteger();
        router.register(ExternalEventType.APPLICATION_EVENT, e -> primary.incrementAndGet());
        router.subscribe(ExternalEventType.APPLICATION_EVENT, e -> subscriber.incrementAndGet());

        // 测试用例的 @AfterEach 就是这么清理的 —— 它不能顺手摘掉生产订阅者
        router.unregister(ExternalEventType.APPLICATION_EVENT);

        assertTrue(router.route(event(ExternalEventType.APPLICATION_EVENT)),
                "订阅者还在, 路由仍然成立");
        assertEquals(0, primary.get());
        assertEquals(1, subscriber.get());
    }

    @Test
    void cancellingASubscriptionLeavesThePrimaryAlone() {
        EventRouter router = new EventRouter();
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger subscriber = new AtomicInteger();
        router.register(ExternalEventType.APPLICATION_EVENT, e -> primary.incrementAndGet());
        EventRouter.EventSubscription subscription =
                router.subscribe(ExternalEventType.APPLICATION_EVENT, e -> subscriber.incrementAndGet());

        subscription.cancel();
        router.route(event(ExternalEventType.APPLICATION_EVENT));

        assertEquals(1, primary.get());
        assertEquals(0, subscriber.get());
        assertTrue(router.hasRoute(ExternalEventType.APPLICATION_EVENT), "主处理者仍在");
    }

    @Test
    void noRouteAtAllReturnsFalse() {
        EventRouter router = new EventRouter();
        assertFalse(router.route(event(ExternalEventType.ENVIRONMENT_EVENT)));
        assertFalse(router.hasRoute(ExternalEventType.ENVIRONMENT_EVENT));
    }
}
