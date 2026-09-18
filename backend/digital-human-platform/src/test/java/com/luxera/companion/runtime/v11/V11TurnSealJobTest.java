package com.luxera.companion.runtime.v11;

import com.luxera.companion.action.ReadMessagesAction;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.mind.ConversationTurnAggregator;
import com.luxera.companion.mind.MindStateService;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.phone.MessageBatch;
import com.luxera.companion.runtime.AgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §8.2 —— <b>封口这件事绝不能在调度线程上做完</b>。
 *
 * <p>本文件最重要的一条是 {@link Handoff#cognitionRunsOnTheAgentsOwnThread}。Spring 默认的
 * {@code ThreadPoolTaskScheduler} 池大小是 1, 全平台 ~19 个 {@code @Scheduled} 共用那<b>一根</b>
 * 线程(含 5 秒一次的 outbox 中继), 而一次认知要跑几秒到几十秒的 LLM。所以
 * "封口 → 认知"之间必须隔一次入队。
 *
 * <p>这里用<b>真实的</b> {@link PersonActorRegistry} 而不是 mock: 用一个"记录下来待会儿再跑"的
 * 假入队, 恰恰会把这条纪律测没了 —— 它能证明有入队, 但证明不了入队之后真的换了线程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V11TurnSealJobTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";

    /**
     * 时间原点<b>相对当前时刻</b>而不是写死 —— 因为 {@code tick()} 读的是真实时钟
     * (它就是调度器), 一个固定在过去某天的时间戳会让"到期了吗"取决于跑测试的钟点。
     * 往前挪 5 分钟: 静默窗口与硬上限都已过, 于是"该封口了"是稳定的。
     */
    private LocalDateTime T0;

    @Mock private V11TurnsSwitch turnsSwitch;
    @Mock private MindStateService mindStates;
    @Mock private ReadMessagesAction readMessages;
    @Mock private AgentRuntime runtime;
    @Mock private AgentSwitchService agentSwitch;

    /** 真的: 只有真的才会真的换线程 */
    private final PersonActorRegistry personActors = new PersonActorRegistry();

    private ConversationTurnAggregator agg;
    private V11TurnPath turnPath;
    private V11TurnSealJob job;

    @BeforeEach
    void setUp() {
        T0 = LocalDateTime.now().minusMinutes(5);
        agg = new ConversationTurnAggregator(4_000, 45_000, 8);
        turnPath = new V11TurnPath(agg, turnsSwitch, mindStates, readMessages);
        job = new V11TurnSealJob(agg, turnsSwitch, turnPath, runtime, personActors, agentSwitch);

        when(turnsSwitch.isEnabled()).thenReturn(true);
        when(turnsSwitch.isActive()).thenReturn(true);
        when(agentSwitch.isRunnable(anyString())).thenReturn(true);
        when(readMessages.readDelivered(eq(AGENT), eq(CONV), anyList()))
                .thenReturn(new MessageBatch(List.of(
                        MessageView.builder().id("m1").conversationId(CONV).content("今天好累").build(),
                        MessageView.builder().id("m2").conversationId(CONV).content("老师讲得好快").build(),
                        MessageView.builder().id("m3").conversationId(CONV).content("我都没听懂").build()),
                        MessageBatch.Transport.SIMULATOR, "ok"));
    }

    @AfterEach
    void tearDown() {
        personActors.shutdownAll();
    }

    /** 三句话进入同一个回合(enabled 入口: 会写心智)。 */
    private void threeMessagesInOneTurn() {
        turnPath.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m1"), T0), msgs -> { });
        turnPath.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m2"), T0.plusSeconds(1)), msgs -> { });
        turnPath.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m3"), T0.plusSeconds(2)), msgs -> { });
    }

    /**
     * shadow 下的同样三句话: 直接喂状态机。
     *
     * <p>不能用 {@code turnPath.accept} —— 那个入口会写心智, 而 shadow 下心智的落库闸门
     * 在 {@code MindStateService} 里; 这里它是 mock, 没有闸门, 于是用例会看见一个
     * 生产上不存在的交互, 并把"shadow 不写心智"这条纪律测成假的。
     */
    private void seedTurnInShadow() {
        agg.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m1"), T0));
        agg.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m2"), T0.plusSeconds(1)));
        agg.accept(new ConversationTurnAggregator.Delivery(AGENT, USER, CONV, List.of("m3"), T0.plusSeconds(2)));
    }

    // ─────────────────────────── 交接 ───────────────────────────

    @Nested
    @DisplayName("交接: 调度线程只入队, 认知在 agent 自己的线程上跑")
    class Handoff {

        @Test
        void cognitionRunsOnTheAgentsOwnThread() throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> workerThread = new AtomicReference<>();
            AtomicReference<List<MessageView>> got = new AtomicReference<>();
            doAnswer(inv -> {
                workerThread.set(Thread.currentThread().getName());
                got.set(inv.getArgument(3));
                done.countDown();
                return null;
            }).when(runtime).process(anyString(), anyString(), anyString(), anyList());

            threeMessagesInOneTurn();
            job.tick();

            assertTrue(done.await(5, TimeUnit.SECONDS), "回合到期就该被送进她自己的邮箱并跑起来");
            assertNotEquals(Thread.currentThread().getName(), workerThread.get(),
                    "认知绝不能在调度线程上跑 —— 那根线程全平台只有一条, 还跑着 5 秒一次的 outbox 中继");
            assertEquals("person-actor-" + AGENT, workerThread.get());
            assertEquals(3, got.get().size(), "三句话一次交出去");
            // 账目在认知<b>之后</b>才写, 所以这里要等 —— 用 timeout 而不是假设它已经写完
            verify(mindStates, timeout(3_000))
                    .noteTurnSealed(eq(AGENT), eq(CONV), anyString(), eq(3), eq(true), any());
        }

        @Test
        void tickReturnsWithoutWaitingForCognition() {
            // 认知在这里"跑很久"(占住消费线程), 而 tick 必须立刻返回
            doAnswer(inv -> {
                Thread.sleep(2_000);
                return null;
            }).when(runtime).process(anyString(), anyString(), anyString(), anyList());

            threeMessagesInOneTurn();
            long t = System.nanoTime();
            job.tick();
            long elapsedMs = (System.nanoTime() - t) / 1_000_000;

            assertTrue(elapsedMs < 500,
                    "tick 只该做一次开关查询 + 一次入队, 实测 " + elapsedMs + "ms —— 它一阻塞, 全平台的定时任务跟着停");
        }

        @Test
        void aPausedAgentIsAbandonedInsteadOfQueued() {
            when(agentSwitch.isRunnable(AGENT)).thenReturn(false);
            threeMessagesInOneTurn();

            job.tick();

            verifyNoInteractions(runtime);
            verify(mindStates).noteTurnSealed(eq(AGENT), eq(CONV), anyString(), eq(3), eq(false), any());
            assertEquals(0, agg.openTurns().size(), "被暂停的回合不该留在聚合器里等下次");
        }

        @Test
        void beingPausedWhileQueuedStillStopsIt() {
            // 第一次问(入队前)说能跑, 第二次问(任务体里)说不能 —— 用户刚按了暂停
            when(agentSwitch.isRunnable(AGENT)).thenReturn(true, false);
            threeMessagesInOneTurn();

            job.tick();

            verify(mindStates, timeout(3_000))
                    .noteTurnSealed(eq(AGENT), eq(CONV), anyString(), eq(3), eq(false), any());
            verify(runtime, never()).process(anyString(), anyString(), anyString(), anyList());
        }

    }

    // ─────────────────────────── shadow ───────────────────────────

    @Nested
    @DisplayName("shadow: 跑状态机、记数字, 但不接管认知")
    class Shadow {

        @Test
        void shadowDrainsTurnsWithoutTouchingCognitionTheMindOrThePhone() {
            when(turnsSwitch.isEnabled()).thenReturn(false);
            seedTurnInShadow();

            job.tick();

            verifyNoInteractions(runtime);
            verifyNoInteractions(mindStates);
            verifyNoInteractions(readMessages);
            assertEquals(1, agg.stats().turnsSealed(), "状态机照样封口 —— 数字必须与 enabled 时同形");
            assertEquals(3.0, agg.stats().messagesPerTurn(), 0.001);
            assertEquals(0, agg.stats().turnsCognized());
        }

        @Test
        void aFailingReadStillLeavesAnAccountOfTheTurn() {
            // 读正文这一步炸了(设备服务挂了)。回合已经被聚合器摘掉, 所以异常不能只是
            // 一路逃到 PersonActor 的错误出口 —— 那样这个回合既不在队列里、也没有任何账目
            when(readMessages.readDelivered(any(), any(), anyList()))
                    .thenThrow(new RuntimeException("设备服务挂了"));
            threeMessagesInOneTurn();

            assertDoesNotThrow(() -> job.tick());
            verify(runtime, never()).process(anyString(), anyString(), anyString(), anyList());
            verify(mindStates, timeout(3_000))
                    .noteTurnSealed(eq(AGENT), eq(CONV), anyString(), eq(3), eq(false), any());
        }

        @Test
        void theJobTouchesNothingWhenTurnedOff() {
            // 这是生产上的默认状态(两个开关都关), 所以它必须是真正的零成本:
            // 不查开关、不查库、不碰聚合器 —— 连一次问都不问
            when(turnsSwitch.isActive()).thenReturn(false);

            job.tick();

            assertEquals(0, agg.stats().turnsOpened());
            verifyNoInteractions(runtime);
            verifyNoInteractions(mindStates);
            verifyNoInteractions(readMessages);
            verifyNoInteractions(agentSwitch);
        }
    }
}
