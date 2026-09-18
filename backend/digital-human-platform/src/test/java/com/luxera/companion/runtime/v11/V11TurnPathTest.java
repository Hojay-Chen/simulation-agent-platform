package com.luxera.companion.runtime.v11;

import com.luxera.companion.action.ReadMessagesAction;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.mind.ConversationTurnAggregator;
import com.luxera.companion.mind.MindStateService;
import com.luxera.companion.phone.MessageBatch;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §8 —— <b>回合封口之后到底发生了什么</b>。
 *
 * <p>三条硬边界各自对应本文件里的一组用例:
 * <ul>
 *   <li>{@link Sealing#threeMessagesAreReadExactlyOnce} —— 三句话并成一个回合, 于是
 *       正文<b>只读一次</b>、READ 台阶只记一次。老链是一次送达一次读, 合并之后若不改
 *       这一点, 就会变成三次读取同一批消息。</li>
 *   <li>{@link Shadow#observeTouchesNeitherTheMindNorCognition} —— shadow 跑的是同一台
 *       状态机, 但它不读正文、不写心智、不调认知。混进任何一条, shadow 就不再是观察。</li>
 *   <li>{@link Sealing#cognitionFailureStillRecordsTheSealedTurn} —— 回合已经封口是事实,
 *       不因为一次认知异常而消失; 但 {@code last_cognitive_at} 只在认知真跑过时才推。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V11TurnPathTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 10, 0, 0);

    @Mock private V11TurnsSwitch turnsSwitch;
    @Mock private MindStateService mindStates;
    @Mock private ReadMessagesAction readMessages;

    private ConversationTurnAggregator agg;
    private V11TurnPath path;
    /** sink 收到过什么 —— 它就是"认知链有没有被调用"的唯一证据。 */
    private final List<List<MessageView>> handed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agg = new ConversationTurnAggregator(4_000, 45_000, 8);
        path = new V11TurnPath(agg, turnsSwitch, mindStates, readMessages);
        handed.clear();
        when(turnsSwitch.isEnabled()).thenReturn(true);
        when(turnsSwitch.isActive()).thenReturn(true);
    }

    private MessageView msg(String id) {
        return MessageView.builder().id(id).conversationId(CONV).content("内容 " + id).build();
    }

    private MessageBatch batch(String... ids) {
        return new MessageBatch(List.of(ids).stream().map(this::msg).toList(),
                MessageBatch.Transport.SIMULATOR, "ok");
    }

    private ConversationTurnAggregator.Delivery delivery(String conv, LocalDateTime at, String... ids) {
        return new ConversationTurnAggregator.Delivery(AGENT, USER, conv, List.of(ids), at);
    }

    /** enabled 的入口: 收下这三句(会写心智、满了会当场交给认知)。 */
    private void hand() {
        path.accept(delivery(CONV, T0, "m1"), handed::add);
        path.accept(delivery(CONV, T0.plusSeconds(1), "m2"), handed::add);
        path.accept(delivery(CONV, T0.plusSeconds(2), "m3"), handed::add);
    }

    /**
     * shadow 的入口: 同样三句话, 但走 {@code observe}。
     *
     * <p>走 {@code accept} 会让一个 mock 的 {@code MindStateService} 收到
     * {@code noteThreadHolding} —— 生产上那次调用会被闸门挡住(闸门就在 Service 里),
     * 而在 mock 上没有闸门, 于是用例会看见一个生产上不存在的交互。用什么入口进,
     * 测的就是什么路径。
     */
    private void observeHand() {
        path.observe(delivery(CONV, T0, "m1"));
        path.observe(delivery(CONV, T0.plusSeconds(1), "m2"));
        path.observe(delivery(CONV, T0.plusSeconds(2), "m3"));
    }

    // ─────────────────────────── 封口 ───────────────────────────

    @Nested
    @DisplayName("封口: 读一次, 交给认知一次")
    class Sealing {

        @Test
        void threeMessagesAreReadExactlyOnce() {
            when(readMessages.readDelivered(eq(AGENT), eq(CONV), anyList()))
                    .thenReturn(batch("m1", "m2", "m3"));
            hand();
            assertEquals(0, handed.size(), "静默窗口没走完之前, 一条正文都不该被交出去");

            List<ConversationTurnAggregator.Turn> due = path.dueTurns(T0.plusSeconds(6));
            assertEquals(1, due.size());
            assertTrue(path.seal(due.get(0), T0.plusSeconds(6), handed::add));

            assertEquals(1, handed.size(), "三句话只该换来一次认知, 不是三次");
            assertEquals(3, handed.get(0).size());
            // 这是 Phase 3 的核心收益: 正文读一次、READ 台阶记一次
            verify(readMessages, times(1)).readDelivered(AGENT, CONV, List.of("m1", "m2", "m3"));
            verify(mindStates).noteTurnSealed(AGENT, CONV, CONV + "#m1", 3, true, T0.plusSeconds(6));
            assertEquals(1, agg.stats().turnsCognized());
        }

        @Test
        void acceptingADeliveryRecordsTheThreadOnHerWorkbench() {
            path.accept(delivery(CONV, T0, "m1"), handed::add);

            verify(mindStates).noteThreadHolding(AGENT, CONV, USER, 1, true, T0);
            assertEquals(0, handed.size(), "收下一条不等于读了它");
        }

        @Test
        void aFullTurnIsHandedOverOnTheSpot() {
            // 攒满就不再等静默窗口 —— 对方一直刷屏时她不该永远不回话
            ConversationTurnAggregator small = new ConversationTurnAggregator(4_000, 45_000, 2);
            V11TurnPath p = new V11TurnPath(small, turnsSwitch, mindStates, readMessages);
            when(readMessages.readDelivered(eq(AGENT), eq(CONV), anyList())).thenReturn(batch("m1", "m2"));

            p.accept(delivery(CONV, T0, "m1"), handed::add);
            assertEquals(0, handed.size());
            p.accept(delivery(CONV, T0.plusSeconds(1), "m2"), handed::add);

            assertEquals(1, handed.size(), "攒满的那一条到达时就该进认知");
            assertEquals(2, handed.get(0).size());
            assertEquals(0, small.openTurns().size());
        }

        @Test
        void nothingReadableMeansSheIsMissingSomethingNotThatNobodyCalled() {
            when(readMessages.readDelivered(any(), any(), anyList()))
                    .thenReturn(MessageBatch.unreachable("设备未配对"));

            hand();
            ConversationTurnAggregator.Turn t = path.dueTurns(T0.plusSeconds(6)).get(0);

            assertFalse(path.seal(t, T0.plusSeconds(6), handed::add));
            assertEquals(0, handed.size());
            verify(mindStates).noteTurnSealed(AGENT, CONV, t.turnId(), 3, false, T0.plusSeconds(6));
            assertEquals(0, agg.stats().turnsCognized(), "没读到正文就不算'她想过这件事'");
            assertEquals(1, agg.stats().turnsSealed(), "但这个回合确实结束了");
        }

        @Test
        void cognitionFailureStillRecordsTheSealedTurn() {
            when(readMessages.readDelivered(eq(AGENT), eq(CONV), anyList())).thenReturn(batch("m1", "m2", "m3"));
            hand();
            ConversationTurnAggregator.Turn t = path.dueTurns(T0.plusSeconds(6)).get(0);

            boolean ok = path.seal(t, T0.plusSeconds(6), msgs -> {
                throw new IllegalStateException("LLM 挂了");
            });

            assertFalse(ok);
            verify(mindStates).noteTurnSealed(AGENT, CONV, t.turnId(), 3, false, T0.plusSeconds(6));
            assertEquals(0, agg.stats().turnsCognized());
        }

        @Test
        void abandonRecordsTheTurnWithoutAdvancingHerLastThought() {
            hand();
            ConversationTurnAggregator.Turn t = path.dueTurns(T0.plusSeconds(6)).get(0);
            path.abandon(t, T0.plusSeconds(6), "agent 已暂停");

            verify(mindStates).noteTurnSealed(AGENT, CONV, t.turnId(), 3, false, T0.plusSeconds(6));
            verifyNoInteractions(readMessages);
            assertEquals(0, handed.size());
        }
    }

    // ─────────────────────────── shadow ───────────────────────────

    @Nested
    @DisplayName("shadow: 只留数字")
    class Shadow {

        @Test
        void observeTouchesNeitherTheMindNorCognition() {
            when(turnsSwitch.isEnabled()).thenReturn(false);

            observeHand();
            assertFalse(path.isAggregating(), "shadow 下认知仍然由老链驱动");
            assertTrue(path.isObserving(), "但状态机要跑 —— 合并率是切流判据");

            verifyNoInteractions(mindStates);
            verifyNoInteractions(readMessages);
            assertEquals(0, handed.size());
            // 状态机与 enabled 时完全一致, 所以它算出来的合并率是真实预测, 不是估计
            assertEquals(1, agg.openTurns().size());
            assertEquals(3, agg.openTurns().get(0).size());
        }

        @Test
        void drainDueSealsTheTurnsButHandsNothingOver() {
            when(turnsSwitch.isEnabled()).thenReturn(false);
            observeHand();

            assertEquals(1, path.drainDue(T0.plusSeconds(6)));
            assertEquals(1, agg.stats().turnsSealed());
            assertEquals(3.0, agg.stats().messagesPerTurn(), 0.001);
            assertEquals(0, agg.stats().turnsCognized(), "shadow 不接管认知");
            assertEquals(0, handed.size());
            verifyNoInteractions(readMessages);
            verifyNoInteractions(mindStates);
        }

        @Test
        void aggregatingFollowsTheEnabledFlagNotTheShadowFlag() {
            when(turnsSwitch.isEnabled()).thenReturn(false);
            when(turnsSwitch.isActive()).thenReturn(true);
            assertFalse(path.isAggregating());
            assertTrue(path.isObserving());

            when(turnsSwitch.isEnabled()).thenReturn(true);
            assertTrue(path.isAggregating());
        }

        @Test
        void bothFlagsOffMeansNothingIsHandedIn() {
            when(turnsSwitch.isEnabled()).thenReturn(false);
            when(turnsSwitch.isActive()).thenReturn(false);
            // 调用方(V11DeliveryPath)靠这两个判断决定要不要走回合这条路
            assertFalse(path.isAggregating());
            assertFalse(path.isObserving());
        }
    }
}
