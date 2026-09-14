package com.luxera.companion.digitalhuman.actor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §20 Person Actor 空闲回收测试:
 * - 空闲超时后 worker 自动退出(不累积线程);
 * - Registry 在回收后自动重建 actor(下次 tell 可用);
 * - mailbox 严格 FIFO(提交顺序 = 执行顺序)。
 */
class PersonActorIdleRecycleTest {

    @Test
    void idleActorAutoExits() throws Exception {
        PersonActor actor = new PersonActor("idle-p", null);
        assertTrue(actor.alive());
        // 空闲超过回收时限 → 自动退出
        Thread.sleep(PersonActor.IDLE_TIMEOUT_MILLIS + 1500);
        assertFalse(actor.alive(), "空闲超时后 worker 应自动回收");
    }

    @Test
    void registryRebuildsRecycledActor() throws Exception {
        PersonActorRegistry registry = new PersonActorRegistry();
        CountDownLatch done = new CountDownLatch(1);
        registry.tell("rebuild-p", done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS), "首次提交应被处理");

        // 等空闲回收
        Thread.sleep(PersonActor.IDLE_TIMEOUT_MILLIS + 1500);

        // 再次提交 → 自动重建并处理
        CountDownLatch done2 = new CountDownLatch(1);
        registry.tell("rebuild-p", done2::countDown);
        assertTrue(done2.await(5, TimeUnit.SECONDS), "回收后重建的 actor 应能处理新任务");
        registry.shutdownAll();
    }

    @Test
    void mailboxPreservesSubmissionOrder() throws Exception {
        PersonActorRegistry registry = new PersonActorRegistry();
        StringBuilder order = new StringBuilder();
        CountDownLatch done = new CountDownLatch(3);

        registry.tell("fifo-p", () -> { order.append("1"); done.countDown(); });
        registry.tell("fifo-p", () -> { order.append("2"); done.countDown(); });
        registry.tell("fifo-p", () -> { order.append("3"); done.countDown(); });

        assertTrue(done.await(5, TimeUnit.SECONDS), "任务应全部完成");
        assertEquals("123", order.toString(), "严格 FIFO: 提交顺序 = 执行顺序");
        registry.shutdownAll();
    }

    @Test
    void errorInTaskDoesNotKillActor() throws Exception {
        PersonActor actor = new PersonActor("err-p", null);
        CountDownLatch done = new CountDownLatch(1);
        actor.tell(() -> { throw new RuntimeException("任务炸了"); });
        actor.tell(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS), "异常任务后 actor 仍能处理后续任务");
        assertTrue(actor.alive());
        actor.shutdown();
    }
}
