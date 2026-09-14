package com.luxera.companion.digitalhuman.actor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §20 Person Actor 并发模型测试:
 * - 同一 Person 的任务严格串行(无并发交错);
 * - 不同 Person 并行执行(互不阻塞)。
 */
class PersonActorTest {

    /** 同一 Person: 多个线程同时提交任务, 执行必须串行无交错 */
    @Test
    void samePersonTasksAreSerialized() throws Exception {
        PersonActor actor = new PersonActor("p1", null);
        int tasks = 200;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            actor.tell(() -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                done.countDown();
            });
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "任务未在时限内完成");
        assertEquals(1, maxConcurrent.get(), "同一 Person 任务出现并发交错");
        assertEquals(tasks, actor.processedCount());
        actor.shutdown();
    }

    /** 不同 Person: 各自独立 actor, 可以并行 */
    @Test
    void differentPersonsRunInParallel() throws Exception {
        PersonActor a1 = new PersonActor("a", null);
        PersonActor a2 = new PersonActor("b", null);
        List<Long> overlap = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        a1.tell(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        a2.tell(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS), "两个 actor 未同时开始");
        // 两个 actor 都已在各自线程中运行(阻塞在 release) → 说明并行
        assertTrue(a1.alive() && a2.alive());
        release.countDown();
        Thread.sleep(100);
        a1.shutdown();
        a2.shutdown();
    }

    /** Registry: tell 提交到同一 Person 串行 */
    @Test
    void registryRoutesToPerPersonActor() throws Exception {
        PersonActorRegistry registry = new PersonActorRegistry();
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        int tasksPerPerson = 50;
        CountDownLatch done = new CountDownLatch(tasksPerPerson * 2);

        for (int p = 0; p < 2; p++) {
            String personId = "person-" + p;
            for (int i = 0; i < tasksPerPerson; i++) {
                registry.tell(personId, () -> {
                    counts.merge(personId, 1, Integer::sum);
                    done.countDown();
                });
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "任务未完成");
        assertEquals(tasksPerPerson, counts.get("person-0"));
        assertEquals(tasksPerPerson, counts.get("person-1"));
        assertEquals(2, registry.size());
        registry.shutdownAll();
    }
}
