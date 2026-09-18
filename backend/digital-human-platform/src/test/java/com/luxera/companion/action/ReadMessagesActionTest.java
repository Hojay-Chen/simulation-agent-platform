package com.luxera.companion.action;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.phone.AwarenessLadder;
import com.luxera.companion.phone.MessageBatch;
import com.luxera.companion.phone.PhoneCapability;
import com.luxera.companion.phone.ReadPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §7.2 / §24.3 —— 「她看了一眼」这个动作。
 *
 * <p>最要紧的一条是<b>顺序</b>: 先读, 后记 READ 台阶。反过来会制造一个谎 ——
 * 读取失败时数据库里留下一条"她读了", 而她其实什么都没看到。
 * 台阶是事实的记录, 不是意图的记录。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadMessagesActionTest {

    private static final String AGENT = "agent-1";
    private static final String CONV = "conv-1";

    @Mock private PhoneCapability phone;
    @Mock private AwarenessLadder ladder;

    private ReadMessagesAction action;

    @BeforeEach
    void setUp() {
        action = new ReadMessagesAction(phone, ladder);
    }

    private static MessageView msg(String id, String content) {
        return MessageView.builder().id(id).conversationId(CONV).content(content).build();
    }

    @Nested
    @DisplayName("顺序: 先读, 后记")
    class Ordering {

        @Test
        void theReadHappensBeforeTheLadderIsTold() {
            when(phone.readMessages(eq(AGENT), eq(CONV), any()))
                    .thenReturn(new MessageBatch(List.of(msg("m1", "在吗")),
                            MessageBatch.Transport.CHAT_WORLD_PORT, "1 条"));

            action.execute(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            InOrder inOrder = inOrder(phone, ladder);
            inOrder.verify(phone).readMessages(eq(AGENT), eq(CONV), any());
            inOrder.verify(ladder).read(eq(AGENT), eq(CONV), eq(List.of("m1")), any());
        }

        @Test
        void aReadThatBroughtNothingBackRecordsNoReadStep() {
            // 这是"先读后记"的全部意义。反过来会让库里留下"她读了",
            // 而她其实一条都没看到 —— 一个无法被发现的谎。
            when(phone.readMessages(eq(AGENT), eq(CONV), any()))
                    .thenReturn(MessageBatch.unreachable("设备没配对"));

            action.execute(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            verify(phone).readMessages(any(), any(), any());
            verifyNoInteractions(ladder);
        }

        @Test
        void aNullBatchFromTheImplementationIsTreatedAsUnreachable() {
            // 契约说"不返回 null", 但真返回了也不该让认知链炸掉
            when(phone.readMessages(any(), any(), any())).thenReturn(null);

            MessageBatch batch = action.execute(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertNotNull(batch);
            assertTrue(batch.isEmpty());
            assertNotNull(batch.note());
            verifyNoInteractions(ladder);
        }
    }

    @Nested
    @DisplayName("读了什么就记什么")
    class Recording {

        @Test
        void theTransportIsRecordedSoTheDeviceChannelIsAuditable() {
            when(phone.readMessages(eq(AGENT), eq(CONV), any()))
                    .thenReturn(new MessageBatch(List.of(msg("m1", "在吗")),
                            MessageBatch.Transport.SIMULATOR, "设备读了 1 条"));

            action.execute(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            verify(ladder).read(AGENT, CONV, List.of("m1"), MessageBatch.Transport.SIMULATOR);
        }

        @Test
        void onlyTheMessagesActuallyReturnedAreRecordedAsRead() {
            // 点名读了 m1/m2, 设备只回了一条 —— 那就只该记一条。
            // 记两条等于凭空造出一次"她读了 m2"。
            when(phone.readMessages(eq(AGENT), eq(CONV), any()))
                    .thenReturn(new MessageBatch(List.of(msg("m1", "在吗")),
                            MessageBatch.Transport.SIMULATOR, "设备读了 1 条"));

            action.execute(AGENT, CONV, ReadPolicy.ids(List.of("m1", "m2")));

            verify(ladder).read(eq(AGENT), eq(CONV), eq(List.of("m1")), any());
        }
    }

    @Nested
    @DisplayName("便捷入口")
    class Convenience {

        @Test
        void readDeliveredAsksForExactlyTheNamedMessages() {
            // 传 id 而不是"整个会话"是刻意的 —— 见 ReadPolicy 的类注释
            when(phone.readMessages(eq(AGENT), eq(CONV), any()))
                    .thenReturn(new MessageBatch(List.of(), MessageBatch.Transport.CHAT_WORLD_PORT, ""));

            action.readDelivered(AGENT, CONV, List.of("m1", "m2"));

            var captor = org.mockito.ArgumentCaptor.forClass(ReadPolicy.class);
            verify(phone).readMessages(eq(AGENT), eq(CONV), captor.capture());
            assertInstanceOf(ReadPolicy.ByIds.class, captor.getValue());
            assertEquals(List.of("m1", "m2"),
                    ((ReadPolicy.ByIds) captor.getValue()).messageIds());
            assertTrue(captor.getValue().deliveryDriven());
        }

        @Test
        void readDeliveredWithNothingNamedDoesNotTouchThePhone() {
            MessageBatch batch = action.readDelivered(AGENT, CONV, List.of());

            assertTrue(batch.isEmpty());
            assertNotNull(batch.note());
            verifyNoInteractions(phone, ladder);
        }

        @Test
        void browsingIsNotDeliveryDriven() {
            when(phone.readMessages(any(), any(), any()))
                    .thenReturn(new MessageBatch(List.of(), MessageBatch.Transport.SIMULATOR, ""));

            action.browse(AGENT, CONV, 20);

            var captor = org.mockito.ArgumentCaptor.forClass(ReadPolicy.class);
            verify(phone).readMessages(eq(AGENT), eq(CONV), captor.capture());
            assertFalse(captor.getValue().deliveryDriven(),
                    "她自己去翻聊天记录不是送达驱动的 —— 它不该触发回退");
            assertEquals(20, captor.getValue().upperBound());
        }
    }

    @Nested
    @DisplayName("取正文")
    class Contents {

        @Test
        void contentsOfExtractsTheTextInOrder() {
            MessageBatch batch = new MessageBatch(
                    List.of(msg("m1", "在吗"), msg("m2", "在干嘛")),
                    MessageBatch.Transport.SIMULATOR, "");
            assertEquals(List.of("在吗", "在干嘛"), ReadMessagesAction.contentsOf(batch));
        }

        @Test
        void blankAndNullContentsAreDropped() {
            MessageBatch batch = new MessageBatch(
                    List.of(msg("m1", "在吗"), msg("m2", null), msg("m3", "   ")),
                    MessageBatch.Transport.SIMULATOR, "");
            assertEquals(List.of("在吗"), ReadMessagesAction.contentsOf(batch));
        }

        @Test
        void contentsOfNullOrEmptyIsEmptyNotAnException() {
            assertEquals(List.of(), ReadMessagesAction.contentsOf(null));
            assertEquals(List.of(), ReadMessagesAction.contentsOf(MessageBatch.unreachable("没信号")));
        }
    }
}
