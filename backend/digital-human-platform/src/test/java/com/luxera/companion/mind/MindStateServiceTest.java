package com.luxera.companion.mind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.luxera.companion.cognitive.CognitiveSession;
import com.luxera.companion.cognitive.CognitiveSessionService;
import com.luxera.companion.intention.Intention;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.runtime.v11.V11TurnsSwitch;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §9.2 —— 心智的读写。
 *
 * <p>本文件里最重要的三条不是"能写进去", 而是三条**边界**:
 * <ol>
 *   <li>{@link Gating#nothingIsWrittenWhenTheSwitchIsOff} —— 迁移期间心智不该落库。
 *       一个"顺手写一下"的路径会让 53 个 agent 各多一行没人读的数据, 而那正是 V10
 *       那个只写不读的影子记录器的形状。</li>
 *   <li>{@link Reads#snapshotDegradesPerDimension} —— 一个维度读不到不该让整个切面读不到。
 *       诊断的价值在切流之前, 而切流之前恰恰是最容易出问题的时候。</li>
 *   <li>{@link Writes#cognizedFalseDoesNotAdvanceLastCognitiveAt} ——
 *       "她上一次真正想过事是什么时候"必须是一句真话。被暂停丢掉的回合传 false。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MindStateServiceTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 10, 0, 0);

    @Mock private MindStateRepository repo;
    @Mock private CognitiveSessionService sessions;
    @Mock private OpenLoopService openLoops;
    @Mock private IntentionService intentions;
    @Mock private V11TurnsSwitch turnsSwitch;

    private MindStateService service;
    /** 每次 save 时那一行的 working_threads —— 用字符串快照, 因为实体是被原地改的。 */
    private final List<String> savedJsons = new ArrayList<>();
    private MindState row;

    /**
     * 与生产同形的 ObjectMapper: <b>必须注册 JavaTimeModule</b>。
     *
     * <p>{@code new ObjectMapper()} 裸用会让 {@code 工作台} 的序列化直接抛
     * {@code InvalidDefinitionException}(LocalDateTime 不被默认支持) —— 而
     * {@code writeThreads} 失败时是<b>安静地不写</b>(那是对的, 见它的注释), 于是
     * 一串"工作台是空的"用例会红得让人以为是逻辑错了。生产用的是 Spring 注入的那个,
     * 它自动注册了 jsr310, 所以这里必须补齐同一个模块, 否则测的是一个不存在的环境。
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    void setUp() {
        service = new MindStateService(repo, sessions, openLoops, intentions, turnsSwitch, mapper());
        savedJsons.clear();
        row = null;
        when(repo.findByCompanionId(anyString())).thenAnswer(inv -> Optional.ofNullable(row));
        when(repo.save(any(MindState.class))).thenAnswer(inv -> {
            MindState m = inv.getArgument(0);
            row = m;
            savedJsons.add(m.getWorkingThreads());
            return m;
        });
    }

    private void persisting(boolean on) {
        when(turnsSwitch.isEnabled()).thenReturn(on);
    }

    // ─────────────────────────── 闸门 ───────────────────────────

    @Nested
    @DisplayName("闸门: 写由一处统一上闸, 读不上闸")
    class Gating {

        @Test
        void nothingIsWrittenWhenTheSwitchIsOff() {
            persisting(false);
            assertFalse(service.isPersisting());

            service.noteThreadHolding(AGENT, CONV, USER, 3, true, T0);
            service.noteFocus(AGENT, CONV, "面试", 0.7, T0);
            service.noteTurnSealed(AGENT, CONV, CONV + "#m1", 3, true, T0);

            // 建行的入口(getOrCreate)是 private 的, 于是"闸门之后才可能建行"是可验证的:
            // 三个写方法全被挡住时, repo 一次都没被碰过
            verifyNoInteractions(repo);
            assertTrue(savedJsons.isEmpty());
        }

        @Test
        void readsStillWorkWhenTheSwitchIsOff() {
            persisting(false);
            row = new MindState();
            row.setCompanionId(AGENT);
            row.setWorkingThreads("[]");

            assertNotNull(service.get(AGENT), "闸门关的是写, 不是观察 —— 切流之前就要能看见她手上有什么");
            assertNotNull(service.snapshot(AGENT));
        }

        @Test
        void getNeverCreatesARow() {
            persisting(true);
            assertNull(service.get(AGENT));
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────────────── 写 ───────────────────────────

    @Nested
    @DisplayName("写: 工作台 / 关注点 / 回合账目")
    class Writes {

        @Test
        void firstDeliveryOpensAThreadAndCountsATurn() {
            persisting(true);
            service.noteThreadHolding(AGENT, CONV, USER, 1, true, T0);

            assertNotNull(row);
            assertEquals(AGENT, row.getCompanionId());
            assertEquals(1, row.getTurnsOpened());
            List<WorkingThread> threads = service.threadsOf(AGENT);
            assertEquals(1, threads.size());
            assertEquals(CONV, threads.get(0).conversationId());
            assertEquals(1, threads.get(0).pendingCount());
            assertEquals(WorkingThread.STATE_OPEN, threads.get(0).state());
        }

        @Test
        void aSecondBatchExtendsTheSameThreadAndDoesNotCountASecondTurn() {
            persisting(true);
            service.noteThreadHolding(AGENT, CONV, USER, 1, true, T0);
            service.noteThreadHolding(AGENT, CONV, USER, 2, false, T0.plusSeconds(1));

            assertEquals(1, row.getTurnsOpened());
            assertEquals(1, service.threadsOf(AGENT).size());
            assertEquals(2, service.threadsOf(AGENT).get(0).pendingCount());
            assertEquals(WorkingThread.STATE_QUIET_WAIT, service.threadsOf(AGENT).get(0).state());
        }

        @Test
        void theWorkbenchIsCappedAndKeepsTheMostRecentThreads() {
            persisting(true);
            for (int i = 0; i < 13; i++) {
                service.noteThreadHolding(AGENT, "conv-" + i, USER, 1, true, T0.plusSeconds(i));
            }
            List<WorkingThread> threads = service.threadsOf(AGENT);
            assertEquals(12, threads.size(), "工作台必须封顶 —— 否则 JSON 会随着会话数一直长");
            assertEquals("conv-12", threads.get(0).conversationId(), "最上面的是最近来的那条线");
        }

        @Test
        void focusIsWrittenWithTheImportanceTheCognitiveChainAlreadyComputed() {
            persisting(true);
            service.noteFocus(AGENT, CONV, "明天的面试", 0.8, T0);

            assertEquals("明天的面试", row.getFocusWhat());
            assertEquals(CONV, row.getFocusSource());
            assertEquals(0.8, row.getFocusIntensity(), 0.001);
            assertEquals(T0, row.getFocusSince());
        }

        @Test
        void sealingRemovesTheThreadAndCountsTheMessages() {
            persisting(true);
            service.noteThreadHolding(AGENT, CONV, USER, 3, true, T0);
            service.noteTurnSealed(AGENT, CONV, CONV + "#m1", 3, true, T0.plusSeconds(6));

            assertTrue(service.threadsOf(AGENT).isEmpty(), "回合结束 = 这条线从工作台上撤下来");
            assertEquals(1, row.getTurnsSealed());
            assertEquals(3, row.getMessagesAggregated());
            assertEquals(CONV, row.getLastConversationId());
            assertEquals(CONV + "#m1", row.getLastTurnId());
            assertEquals(3.0, row.messagesPerTurn(), 0.001,
                    "3 条消息 / 1 个回合 —— 这就是那个要拿去决定切不切流的数字");
        }

        @Test
        void cognizedFalseDoesNotAdvanceLastCognitiveAt() {
            persisting(true);
            service.noteTurnSealed(AGENT, CONV, "t1", 3, false, T0);

            assertNull(row.getLastCognitiveAt(),
                    "被暂停丢掉、或者一条正文都没读到的回合, 不该把'她上一次想过事'往前推");
            assertEquals(1, row.getTurnsSealed(), "但它确实结束了, 账目照样要记");
        }

        @Test
        void writingThenFailingToReadIsNotSilent() {
            persisting(true);
            when(repo.save(any(MindState.class))).thenThrow(new RuntimeException("db down"));
            // 心智写失败不该把一次送达炸掉 —— 它是痕迹, 不是事实
            assertDoesNotThrow(() -> service.noteThreadHolding(AGENT, CONV, USER, 1, true, T0));
            assertDoesNotThrow(() -> service.noteFocus(AGENT, CONV, "x", 0.5, T0));
            assertDoesNotThrow(() -> service.noteTurnSealed(AGENT, CONV, "t", 1, true, T0));
        }

        @Test
        void blankIdentifiersAreIgnored() {
            persisting(true);
            service.noteThreadHolding(null, CONV, USER, 1, true, T0);
            service.noteThreadHolding(AGENT, "  ", USER, 1, true, T0);
            service.noteFocus(AGENT, CONV, null, 0.5, T0);
            service.noteTurnSealed(null, CONV, "t", 1, true, T0);
            verify(repo, never()).save(any());
        }

        @Test
        void aThreadSurvivesASaveThatCouldNotBeSerialized() {
            persisting(true);
            service.noteThreadHolding(AGENT, CONV, USER, 1, true, T0);
            String good = row.getWorkingThreads();
            assertNotEquals("[]", good);

            // 第二次写入时序列化失败(用一个 writeValueAsString 会炸的 mapper 模拟)
            MindStateService broken = new MindStateService(repo, sessions, openLoops, intentions,
                    turnsSwitch, new ObjectMapper() {
                        @Override
                        public String writeValueAsString(Object value) {
                            throw new IllegalStateException("boom");
                        }
                    });
            broken.noteThreadHolding(AGENT, "conv-9", USER, 1, true, T0);

            assertEquals(good, row.getWorkingThreads(),
                    "序列化失败必须保留库里原来的工作台 —— 返回 [] 会把一次技术故障写成她的记忆");
            assertEquals(1, row.getTurnsOpened(), "而且不该顺手把计数器推一格");
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    @Nested
    @DisplayName("读: 单维度降级, 观察者不改变被观察者")
    class Reads {

        @Test
        void brokenThreadJsonReadsAsNoThreads() {
            persisting(true);
            row = new MindState();
            row.setCompanionId(AGENT);
            row.setWorkingThreads("{ 这不是 JSON");

            assertTrue(service.threadsOf(AGENT).isEmpty());
            // 反向选择(抛出去)会让一条写坏的记录从此让这个 agent 的每次读写都失败
            assertNotNull(service.snapshot(AGENT));
        }

        @Test
        void snapshotDegradesPerDimension() {
            when(openLoops.activeLoops(anyString())).thenThrow(new RuntimeException("open_loops 读不到"));
            when(intentions.activatable(anyString(), anyDouble())).thenReturn(List.of());
            when(sessions.get(anyString())).thenReturn(null);

            MindSnapshot snap = service.snapshot(AGENT);

            assertNotNull(snap);
            assertTrue(snap.openLoops().isEmpty(), "读不到的那一维降级成空");
            assertTrue(snap.intentions().isEmpty());
            assertEquals(AGENT, snap.agentId(), "而整个切面照样出得来");
            assertNotNull(snap.takenAt());
        }

        @Test
        void snapshotCarriesFocusLoopsIntentionsAndTurns() {
            persisting(true);
            service.noteFocus(AGENT, CONV, "面试", 0.7, T0);
            service.noteThreadHolding(AGENT, CONV, USER, 2, true, T0);
            service.noteTurnSealed(AGENT, CONV, "t1", 3, true, T0.plusSeconds(6));

            OpenLoop loop = new OpenLoop();
            loop.setId("loop-1");
            loop.setTitle("等面试结果");
            loop.setImportance(0.9);
            when(openLoops.activeLoops(AGENT)).thenReturn(List.of(loop));

            Intention it = new Intention();
            it.setId("int-1");
            it.setContent("想问问他面试怎么样");
            it.setActivationProbability(0.8);
            when(intentions.activatable(eq(AGENT), anyDouble())).thenReturn(List.of(it));

            CognitiveSession cs = new CognitiveSession();
            cs.setCurrentFocus("他今天不太对劲");
            cs.setCurrentThought("想多陪陪他");
            when(sessions.get(AGENT)).thenReturn(cs);

            MindSnapshot snap = service.snapshot(AGENT);

            assertEquals("面试", snap.focus().what());
            assertEquals(CONV, snap.focus().source());
            assertTrue(snap.focus().isPresent());
            assertEquals(1, snap.openLoops().size());
            assertEquals("等面试结果", snap.openLoops().get(0).title());
            assertEquals(1, snap.intentions().size());
            assertEquals("想多陪陪他", snap.sessionThought());
            assertEquals(1, snap.turns().turnsSealed());
            assertEquals(3, snap.turns().messagesAggregated());
            assertEquals(3.0, snap.turns().messagesPerTurn(), 0.001);
        }

        @Test
        void anAgentThatNeverWokeUpHasNoMindRatherThanADefaultOne() {
            MindSnapshot snap = service.snapshot("agent-never-seen");

            assertFalse(snap.focus().isPresent(), "一个从没醒过的 agent 的心智是'没有', 不是一个占位符");
            assertNull(snap.focus().what());
            assertTrue(snap.threads().isEmpty());
            assertEquals(0, snap.turns().turnsSealed());
            assertEquals(0.0, snap.turns().messagesPerTurn(), 0.001, "没有任何回合时是 0, 不是 NaN");
        }

        @Test
        void threadOfFindsOneThread() {
            persisting(true);
            service.noteThreadHolding(AGENT, CONV, USER, 1, true, T0);

            assertTrue(service.threadOf(AGENT, CONV).isPresent());
            assertTrue(service.threadOf(AGENT, "conv-other").isEmpty());
        }
    }
}
