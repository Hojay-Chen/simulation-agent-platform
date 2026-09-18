package com.luxera.companion.mailbox;

import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V11 §4.1 —— 信箱的四条验收标准(计划 §Phase 1 逐字):
 *
 * <ol>
 *   <li>同 eventId 投两次只处理一次; </li>
 *   <li>重启后未消费事件被重放; </li>
 *   <li>同一 agent 串行、不同 agent 并行; </li>
 *   <li>暂停中的 agent 一条都不处理。</li>
 * </ol>
 *
 * <p>每一条都对应一个真实的故障: 第 1 条对应用户连点两次发送导致她回两遍;
 * 第 2 条对应部署重启时正在排队的消息无声消失; 第 3 条对应两条消息同时到达时
 * 她的状态被两个线程同时改坏; 第 4 条对应"我明明关掉了它还在烧 token"。
 *
 * <p>这个测试之所以必须打真库(而不是 mock 仓储), 是因为<b>幂等与认领的正确性
 * 完全落在数据库的唯一约束与条件 UPDATE 上</b>。用 mock 测这两件事, 测的是
 * mock 自己的行为 —— 一个能通过但什么都没证明的测试, 比没有测试更糟。
 *
 * <h2>为什么到处是 await 而不是直接断言</h2>
 * 消费是异步的(信交给 agent 自己的线程去拆), 所以"投递成功"与"拆完了"之间隔着一段时间。
 * 断言若不等, 它会时红时绿, 而一个时红时绿的测试最后一定被人加 {@code @Disabled} 掉 ——
 * 那时它连"时红时绿"这点价值都没了。等待的边界是 10 秒: 正常在毫秒级完成,
 * 真出问题时也只在失败用例上等一次。
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(AgentMailboxTest.ConsumerConfig.class)
class AgentMailboxTest {

    @Autowired
    AgentMailbox mailbox;
    @Autowired
    AgentInboxRepository inbox;
    @Autowired
    AgentSwitchService agentSwitch;
    @Autowired
    CompanionRepository companions;
    @Autowired
    RecordingConsumer consumer;

    @PersistenceContext
    EntityManager em;

    /**
     * 每个测试方法都会新建一个测试实例(JUnit 5 默认行为), 于是这两个 id 每个方法
     * 都是新的 —— 上一个方法留下的行不会以任何形式影响下一个方法的断言。
     */
    private final String agentA = "inbox-a-" + shortId();
    private final String agentB = "inbox-b-" + shortId();

    @BeforeEach
    void reset() {
        consumer.reset();
    }

    @AfterEach
    void cleanUp() {
        // 测试库里的邮箱要清干净: 同一个 schema 会在多个测试类之间复用,
        // 而启动恢复与重放都会去捞 PENDING 行 —— 留着它们会让别的测试看到别人的信。
        inbox.deleteByAgentId(agentA);
        inbox.deleteByAgentId(agentB);
        companions.findById(agentA).ifPresent(companions::delete);
    }

    // ── 1. 幂等 ──────────────────────────────────────────────

    @Test
    void theSameEventIsOnlyEverConsumedOnce() {
        EventEnvelope e = envelope(agentA, "dup-1");
        assertTrue(mailbox.accept(e), "第一次应当收下");
        assertTrue(!mailbox.accept(e), "同 eventId 再来一次应当幂等短路");

        consumer.awaitSuccesses(agentA, 1);
        assertEquals(1, consumer.successesOf(agentA), "她只该看到一条");
        assertEquals(1, inbox.findByEventId(e.eventId()).stream().count(),
                "数据库里只该有一行 —— 幂等靠的是唯一约束, 不是内存判断");
    }

    @Test
    void theSameEventFromFourThreadsStillLandsOnce() throws Exception {
        EventEnvelope e = envelope(agentA, "race-1");
        int threads = 4;
        CyclicBarrier gate = new CyclicBarrier(threads);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            pool[i] = new Thread(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 到不齐也照常往下走, 让断言去失败
                }
                if (mailbox.accept(e)) {
                    accepted.incrementAndGet();
                }
            });
            pool[i].start();
        }
        for (Thread t : pool) {
            t.join(10_000);
        }

        consumer.awaitSuccesses(agentA, 1);
        // 上面那次 await 已经把"有人消费了"等到了, 但四线程抢插的胜负可能在它之后才落定,
        // 所以 accepted 要等线程全部 join 完再读(上面已经 join)。
        assertEquals(1, accepted.get(),
                "四个线程同时投同一件事, 只该有一个认为自己是收下它的人");
        assertEquals(1, consumer.successesOf(agentA), "她仍然只该看到一条");
    }

    // ── 2. 重放 ──────────────────────────────────────────────

    @Test
    void mailTheCrashLeftBehindIsDeliveredOnRecovery() {
        // 模拟"进程在收下信之后、拆开之前崩了": 信箱里有 PENDING 行, 但没有消费者跑过。
        AgentInboxEntry stranded = persistPending(agentA, "crash-1");
        assertEquals(0, consumer.successesOf(agentA));

        int replayed = mailbox.replayPending();

        assertTrue(replayed >= 1, "重启后应当至少重放这一条");
        awaitRow(stranded.getEventId(), AgentInboxEntry.S_CONSUMED);
        assertEquals(AgentInboxEntry.S_CONSUMED, rowOf(stranded.getEventId()).getStatus(),
                "重放之后它必须是已消费, 否则下次重启会再拆一遍");
    }

    @Test
    void replayingTwiceDoesNotConsumeTwice() {
        persistPending(agentA, "crash-2");
        mailbox.replayPending();
        consumer.awaitSuccesses(agentA, 1);
        mailbox.replayPending();
        awaitRow(pendingEventId("crash-2"), AgentInboxEntry.S_CONSUMED);
        assertEquals(1, consumer.successesOf(agentA),
                "重放本身必须是幂等的 —— 否则部署重启两次她就回两遍");
    }

    @Test
    void aFailedConsumptionGoesBackToTheInboxAndIsRetried() {
        consumer.failNext.set(1);
        mailbox.accept(envelope(agentA, "flaky-1"));

        // 第一次必定失败 —— 等"尝试过"而不是等"成功过", 否则这里会干等满 10 秒
        consumer.awaitAttempts(agentA, 1);
        AgentInboxEntry row = awaitRow(pendingEventId("flaky-1"), AgentInboxEntry.S_PENDING);

        assertEquals(AgentInboxEntry.S_PENDING, row.getStatus(),
                "失败后应当退回 PENDING 而不是丢在 CONSUMING");
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getLastError(), "失败原因要留下来, 否则没人知道她为什么没回");
        assertEquals(0, consumer.successesOf(agentA));

        mailbox.replayPending();
        consumer.awaitSuccesses(agentA, 1);
        awaitRow(pendingEventId("flaky-1"), AgentInboxEntry.S_CONSUMED);
        assertEquals(AgentInboxEntry.S_CONSUMED, rowOf(pendingEventId("flaky-1")).getStatus(),
                "第二次成功之后必须转 CONSUMED, 不能无限重试");
        assertEquals(1, consumer.successesOf(agentA), "她只该看到一条 —— 第一次是失败的");
    }

    // ── 3. 串行与并行 ────────────────────────────────────────

    @Test
    void oneAgentsMailIsProcessedOnExactlyOneThread() {
        for (int i = 0; i < 5; i++) {
            mailbox.accept(envelope(agentA, "serial-" + i));
        }
        consumer.awaitSuccesses(agentA, 5);

        assertEquals(1, consumer.maxInFlight,
                "同一个 agent 同时只该有一条信在被拆 —— 她的状态不能被两个线程同时改");
        assertEquals(1, consumer.threadsOf(agentA).size(),
                "五条信应当全部落在她自己的那一条线程上, 实际: " + consumer.threadsOf(agentA));
    }

    @Test
    void twoAgentsAreNotBlockedByEachOther() {
        // 用一个两人栅栏证明并行: 若两条链被同一个线程串起来, 栅栏永远等不齐。
        consumer.barrier = new CyclicBarrier(2);
        assertTrue(mailbox.accept(envelope(agentA, "par-a")), "A 的信应当被收下");
        assertTrue(mailbox.accept(envelope(agentB, "par-b")), "B 的信应当被收下");

        consumer.awaitSuccesses(agentA, 1);
        consumer.awaitSuccesses(agentB, 1);
        String diag = " [A: attempts=" + consumer.attemptsOf(agentA) + " threads=" + consumer.threadsOf(agentA)
                + " | B: attempts=" + consumer.attemptsOf(agentB) + " threads=" + consumer.threadsOf(agentB)
                + " | maxInFlight=" + consumer.maxInFlight + "]";
        assertEquals(0, consumer.barrierBroken.get(),
                "两个不同 agent 的信必须能同时被处理 —— 否则她的慢会变成所有人的慢" + diag);
        assertNotEquals(consumer.threadsOf(agentA), consumer.threadsOf(agentB),
                "不同 agent 该跑在不同的线程上" + diag);
    }

    // ── 4. 暂停 ──────────────────────────────────────────────

    @Test
    void aPausedAgentTakesNothingOutOfItsInbox() {
        pause(agentA);

        assertTrue(mailbox.accept(envelope(agentA, "paused-1")),
                "信还是要收下的 —— 暂停是\"先不看\", 不是\"当没发生过\"");

        mailbox.replayPending();

        assertEquals(0, consumer.attemptsOf(agentA), "暂停中的 agent 一条都不该被拆");
        AgentInboxEntry row = rowOf(pendingEventId("paused-1"));
        assertEquals(AgentInboxEntry.S_PENDING, row.getStatus(), "信应当原地等着");
        assertEquals(0, row.getAttempts(),
                "暂停不该消耗重试次数 —— 否则暂停三天的 agent 恢复时所有信都 FAILED 了, "
                        + "而那正是用户用来省钱的开关");
    }

    @Test
    void resumingLetsTheWaitingMailThrough() {
        pause(agentA);
        mailbox.accept(envelope(agentA, "resume-1"));
        assertEquals(0, consumer.attemptsOf(agentA));

        agentSwitch.resume(agentA);
        mailbox.replayPending();

        consumer.awaitSuccesses(agentA, 1);
        assertEquals(1, consumer.successesOf(agentA), "恢复之后积压的信应当能被拆开");
    }

    // ── 租约与保留期 ─────────────────────────────────────────

    @Test
    void aDeadConsumersLeaseIsEventuallyReclaimed() {
        AgentInboxEntry stuck = persistPending(agentA, "stuck-1");
        // 模拟"消费者拿到信之后整个 JVM 被 kill": 状态留在 CONSUMING, 租约过了期
        stuck.setStatus(AgentInboxEntry.S_CONSUMING);
        stuck.setClaimedAt(LocalDateTime.now().minusMinutes(30));
        inbox.saveAndFlush(stuck);

        int reclaimed = mailbox.reclaimStale();

        assertTrue(reclaimed >= 1);
        assertEquals(AgentInboxEntry.S_PENDING, rowOf(stuck.getEventId()).getStatus());
    }

    @Test
    void freshLeasesAreLeftAlone() {
        AgentInboxEntry inFlight = persistPending(agentA, "inflight-1");
        inFlight.setStatus(AgentInboxEntry.S_CONSUMING);
        inFlight.setClaimedAt(LocalDateTime.now());   // 刚认领, 正在处理
        inbox.saveAndFlush(inFlight);

        mailbox.reclaimStale();

        assertEquals(AgentInboxEntry.S_CONSUMING, rowOf(inFlight.getEventId()).getStatus(),
                "抢回一条正在被处理的信 = 她隔几分钟重复回复同一条消息");
    }

    @Test
    @Transactional
    void expiredMailIsDroppedButNotDeleted() {
        AgentInboxEntry old = persistPending(agentA, "old-1");
        // createdAt 是 @CreationTimestamp, 实体保存时会被覆盖, 所以只能走原生 SQL 回填
        em.createNativeQuery("update agent_inbox set created_at = ? where id = ?")
                .setParameter(1, LocalDateTime.now().minusDays(10))
                .setParameter(2, old.getId())
                .executeUpdate();

        int dropped = mailbox.dropExpired(24);

        assertTrue(dropped >= 1);
        AgentInboxEntry after = rowOf(old.getEventId());
        assertNotNull(after, "转 DROPPED, 不是 DELETE —— \"她漏掉了什么\"要留得下来");
        assertEquals(AgentInboxEntry.S_DROPPED, after.getStatus());
    }

    @Test
    void inboxDepthIsVisibleFromOutside() {
        pause(agentA);
        mailbox.accept(envelope(agentA, "depth-1"));
        mailbox.accept(envelope(agentA, "depth-2"));
        assertEquals(2, mailbox.depthOf(agentA));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private EventEnvelope envelope(String agentId, String key) {
        return EventEnvelope.withDeterministicId(agentId, AgentEventType.USER_MESSAGE_RECEIVED,
                EventSource.CHAT_PLATFORM, key,
                Map.of("conversationId", "conv-" + key, "messageIds", List.of("m-" + key)));
    }

    /** 事件 id 由信封自己算 —— 测试<b>不</b>手写这个字符串, 否则改了 id 规则这里会静默失配。 */
    private String pendingEventId(String key) {
        return envelope(agentA, key).eventId();
    }

    /** 直接落一条 PENDING 行 —— 模拟"信收下了但没人拆"。 */
    private AgentInboxEntry persistPending(String agentId, String key) {
        EventEnvelope e = EventEnvelope.withDeterministicId(agentId, AgentEventType.USER_MESSAGE_RECEIVED,
                EventSource.CHAT_PLATFORM, key, Map.of("conversationId", "conv-" + key));
        AgentInboxEntry entry = new AgentInboxEntry();
        entry.setEventId(e.eventId());
        entry.setAgentId(agentId);
        entry.setEventType(e.type().wire());
        entry.setSource(e.source().wire());
        entry.setPriority(e.priority().name());
        entry.setPayload("{\"conversationId\":\"conv-" + key + "\"}");
        entry.setOccurredAt(LocalDateTime.now());
        entry.setStatus(AgentInboxEntry.S_PENDING);
        return inbox.saveAndFlush(entry);
    }

    private void pause(String agentId) {
        Companion c = new Companion();
        c.setId(agentId);
        c.setUserId("user-" + agentId);
        c.setName("小满");
        companions.saveAndFlush(c);
        assertTrue(agentSwitch.pause(agentId), "测试脚手架应当真的把它暂停了");
        assertTrue(!agentSwitch.isRunnable(agentId));
    }

    private AgentInboxEntry rowOf(String eventId) {
        return inbox.findByEventId(eventId).orElse(null);
    }

    /** 等某一行的状态落定 —— 消费是异步的, 直接断言会时红时绿。 */
    private AgentInboxEntry awaitRow(String eventId, String status) {
        AgentInboxEntry row = await(() -> {
            AgentInboxEntry r = rowOf(eventId);
            return r != null && status.equals(r.getStatus()) ? r : null;
        });
        return row == null ? rowOf(eventId) : row;
    }

    private static <T> T await(Supplier<T> probe) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.get();
            if (v != null) {
                return v;
            }
            sleepQuietly();
        }
        return probe.get();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @TestConfiguration
    static class ConsumerConfig {
        @Bean
        RecordingConsumer recordingConsumer() {
            return new RecordingConsumer();
        }
    }

    /** 一个把所有类型都收下的消费者, 记录"谁在什么线程上试了几次、成了几次"。 */
    static class RecordingConsumer implements AgentInboxConsumer {

        /** 试过几次 —— 与"成功几次"分开: 失败重试的用例要等的是前者。 */
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> successes = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> threads = new ConcurrentHashMap<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        volatile int maxInFlight;
        volatile CyclicBarrier barrier;
        final AtomicInteger barrierBroken = new AtomicInteger();
        final AtomicInteger failNext = new AtomicInteger();

        @Override
        public boolean supports(AgentEventType type) {
            return true;
        }

        @Override
        public void consume(EventEnvelope envelope) {
            String agent = envelope.agentId();
            int now = inFlight.incrementAndGet();
            maxInFlight = Math.max(maxInFlight, now);
            threads.computeIfAbsent(agent, k -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName());
            try {
                CyclicBarrier b = barrier;
                if (b != null) {
                    try {
                        b.await(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // 等不齐 = 两条链没能并行。记下来, 让断言去说这件事。
                        barrierBroken.incrementAndGet();
                    }
                }
                if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IllegalStateException("测试注入的失败");
                }
                successes.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
            } finally {
                attempts.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                inFlight.decrementAndGet();
            }
        }

        int successesOf(String agent) {
            return get(successes, agent);
        }

        int attemptsOf(String agent) {
            return get(attempts, agent);
        }

        Set<String> threadsOf(String agent) {
            return threads.getOrDefault(agent, Set.of());
        }

        void awaitSuccesses(String agent, int expected) {
            await(() -> successesOf(agent) >= expected ? Boolean.TRUE : null);
        }

        void awaitAttempts(String agent, int expected) {
            await(() -> attemptsOf(agent) >= expected ? Boolean.TRUE : null);
        }

        private static int get(Map<String, AtomicInteger> m, String agent) {
            AtomicInteger c = m.get(agent);
            return c == null ? 0 : c.get();
        }

        void reset() {
            attempts.clear();
            successes.clear();
            threads.clear();
            inFlight.set(0);
            maxInFlight = 0;
            barrier = null;
            barrierBroken.set(0);
            failNext.set(0);
        }
    }
}
