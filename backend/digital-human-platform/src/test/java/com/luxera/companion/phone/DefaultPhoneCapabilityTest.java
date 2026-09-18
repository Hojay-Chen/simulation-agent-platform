package com.luxera.companion.phone;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.simulator.CapabilityResult;
import com.luxera.companion.digitalhuman.simulator.ChatSimulatorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §7.2 —— 手机能力的<b>择优传输</b>。
 *
 * <p>这个类的用例几乎全在测一条边: <b>设备通道答不了的时候必须回退</b>。
 * 不回退的表现是"她明明有手机却看不到昨晚那条消息", 而且只在消息稍旧时出现 ——
 * 线上极难复现, 所以只能在这里钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultPhoneCapabilityTest {

    private static final String AGENT = "agent-1";
    private static final String CONV = "conv-1";

    @Mock private PhoneNotificationRepository notifications;
    @Mock private PhoneStateService phoneStates;
    @Mock private ChatSimulatorClient simulator;
    @Mock private ChatWorldPort chatWorld;
    @Mock private ObjectProvider<ChatSimulatorClient> simulatorProvider;

    private DefaultPhoneCapability phone;

    @BeforeEach
    void setUp() {
        phone = new DefaultPhoneCapability(notifications, phoneStates, simulatorProvider, chatWorld);
    }

    private void deviceConnected(boolean connected) {
        when(simulatorProvider.getIfAvailable()).thenReturn(simulator);
        when(simulator.isConnected(AGENT)).thenReturn(connected);
    }

    private static CapabilityResult deviceResult(List<Map<String, Object>> messages) {
        return new CapabilityResult(true, "ok", Map.of("messages", messages));
    }

    private static Map<String, Object> deviceMessage(String id, String content, String at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("conversationId", CONV);
        m.put("senderType", "USER");
        m.put("content", content);
        m.put("createdAt", at);
        return m;
    }

    private static MessageView platformMessage(String id, String content, String at) {
        return MessageView.builder().id(id).conversationId(CONV).senderType("USER")
                .content(content).createdAt(LocalDateTime.parse(at)).build();
    }

    // ─────────────────────────── 传输选择 ───────────────────────────

    @Nested
    @DisplayName("择优")
    class TransportChoice {

        @Test
        void withADeviceConnectedTheTextComesFromHerOwnHand() {
            deviceConnected(true);
            when(simulator.readMessages(eq(AGENT), eq(CONV), anyInt())).thenReturn(
                    deviceResult(List.of(deviceMessage("m1", "在吗", "2026-09-18T20:00:00"))));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertEquals(MessageBatch.Transport.SIMULATOR, batch.transport());
            assertEquals("在吗", batch.messages().get(0).getContent());
            // 走了设备就<b>不该</b>再碰平台直读 —— 那是一次多余的正文过境
            verifyNoInteractions(chatWorld);
        }

        @Test
        void withNoDeviceConnectedThePlatformIsUsedInstead() {
            deviceConnected(false);
            when(chatWorld.message("m1"))
                    .thenReturn(Optional.of(platformMessage("m1", "在吗", "2026-09-18T20:00:00")));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertEquals(MessageBatch.Transport.CHAT_WORLD_PORT, batch.transport());
            assertEquals(1, batch.messages().size());
        }

        @Test
        void withNoDeviceChannelBeanAtAllItStillWorks() {
            // app.simulator.backend 不是 websocket 时 ChatSimulatorClient 这个 bean 根本不存在。
            // 这不是故障, 是当前部署的形态 —— 绝不能因此让认知链起不来。
            when(chatWorld.message("m1"))
                    .thenReturn(Optional.of(platformMessage("m1", "在吗", "2026-09-18T20:00:00")));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertEquals(MessageBatch.Transport.CHAT_WORLD_PORT, batch.transport());
            assertEquals(1, batch.messages().size());
        }
    }

    // ─────────────────────────── 回退 ───────────────────────────

    @Nested
    @DisplayName("回退: 设备通道答不了的时候")
    class Fallback {

        @Test
        void aDeliveryDrivenReadFallsBackWhenTheDeviceScannedPastTheMessage() {
            // 设备通道只能"读最近 N 条", 没法"按 id 读指定的几条"。
            // 她要读的那条比扫描窗口更老(比如睡了一夜才醒) → 设备返回了别的消息,
            // 筛完是空的。这时必须回退, 否则表现是"她有手机却看不到昨晚那条"。
            deviceConnected(true);
            when(simulator.readMessages(eq(AGENT), eq(CONV), anyInt())).thenReturn(
                    deviceResult(List.of(deviceMessage("other", "别的话", "2026-09-18T20:05:00"))));
            when(chatWorld.message("m1"))
                    .thenReturn(Optional.of(platformMessage("m1", "昨晚那句", "2026-09-17T23:00:00")));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertEquals(MessageBatch.Transport.CHAT_WORLD_PORT, batch.transport(), "没有回退");
            assertEquals(1, batch.messages().size());
            assertEquals("昨晚那句", batch.messages().get(0).getContent());
        }

        @Test
        void herOwnBrowsingDoesNotFallBackJustBecauseTheScanCameUpEmpty() {
            // Recent 策略不是送达驱动的: 扫描结果就是答案, 空就是空。
            // 这里回退会把"她翻了翻, 没什么新东西"变成一次多余的平台直读。
            deviceConnected(true);
            when(simulator.readMessages(eq(AGENT), eq(CONV), anyInt()))
                    .thenReturn(deviceResult(List.of()));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.recent(10));

            assertEquals(MessageBatch.Transport.SIMULATOR, batch.transport());
            assertTrue(batch.messages().isEmpty());
            verifyNoInteractions(chatWorld);
        }

        @Test
        void aDeviceFailureFallsBackRatherThanReportingNoMessages() {
            deviceConnected(true);
            when(simulator.readMessages(eq(AGENT), eq(CONV), anyInt()))
                    .thenReturn(new CapabilityResult(false, "WS 断了", Map.of()));
            when(chatWorld.message("m1"))
                    .thenReturn(Optional.of(platformMessage("m1", "在吗", "2026-09-18T20:00:00")));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1")));

            assertEquals(1, batch.messages().size(), "设备失败不该等同于'没人找她'");
        }
    }

    // ─────────────────────────── 反模式 ───────────────────────────

    @Nested
    @DisplayName("不许把整个会话拉下来筛")
    class NoWholeConversationReads {

        @Test
        void readingByIdsNeverPullsTheWholeConversation() {
            // 这条是本阶段存在的理由。老链的形状是:
            //   chatWorld.messages(conversationId).stream().filter(m -> wanted.contains(m.getId()))
            // —— 在"她注意到了吗"被问出来之前, 整个会话的正文已经进了进程。
            // 所以按 id 读必须是"逐条取", 不是"全量取再筛"。
            deviceConnected(false);
            when(chatWorld.message(anyString())).thenReturn(Optional.empty());

            phone.readMessages(AGENT, CONV, ReadPolicy.ids(List.of("m1", "m2")));

            verify(chatWorld, never()).messages(anyString());
            verify(chatWorld, never()).recentMessages(anyString(), anyInt());
            verify(chatWorld).message("m1");
            verify(chatWorld).message("m2");
        }

        @Test
        void thePlatformFallbackForBrowsingRespectsTheRequestedLimit() {
            deviceConnected(false);
            when(chatWorld.recentMessages(CONV, 5)).thenReturn(List.of());

            phone.readMessages(AGENT, CONV, ReadPolicy.recent(5));

            verify(chatWorld).recentMessages(CONV, 5);
            verify(chatWorld, never()).messages(anyString());
        }
    }

    // ─────────────────────────── 读不到 ───────────────────────────

    @Nested
    @DisplayName("读不到是生活的一部分, 不是异常")
    class Unreachable {

        @Test
        void noConversationMeansAnEmptyBatchWithAReasonNotAnException() {
            MessageBatch batch = phone.readMessages(AGENT, null, ReadPolicy.recent(5));
            assertTrue(batch.messages().isEmpty());
            assertNotNull(batch.note());
        }

        @Test
        void anEmptyByIdsPolicyIsRejectedAtConstruction() {
            // "按 id 读, 但一个 id 都没有"是一次白跑的读取 —— ReadPolicy 在<b>构造时</b>
            // 就把它拒掉, 而不是让它走到设备上浪费一次往返再返回空。
            // 调用方(ReadMessagesAction.readDelivered)因此在构造策略<b>之前</b>就返回
            // unreachable 批次, 所以这条路径在生产上不可达 —— 这里钉住的是那道闸本身。
            assertThrows(IllegalArgumentException.class, () -> ReadPolicy.ids(List.of()));
        }

        @Test
        void aPlatformFailureIsReportedAsUnreachableNotAsSilence() {
            deviceConnected(false);
            when(chatWorld.recentMessages(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("连接池耗尽"));

            MessageBatch batch = phone.readMessages(AGENT, CONV, ReadPolicy.recent(5));

            assertTrue(batch.messages().isEmpty());
            // 关键区别: 空 + 有原因 = "她错过了什么"; 空 + 没原因 = "没人找她"。
            // 两者今天在数据库里长得一样, 正是 V11 要修的东西 —— 所以原因必须真的带上,
            // 而不是一句泛泛的"读取失败"。
            assertNotNull(batch.note(), "读不到时必须留下原因");
            assertTrue(batch.note().contains("连接池耗尽"), "原因被吞掉了: " + batch.note());
        }
    }

    // ─────────────────────────── 通知 ───────────────────────────

    @Nested
    @DisplayName("通知里结构上装不下正文")
    class NotificationsCarryNoText {

        @Test
        void theSnapshotHasNoFieldThatCanHoldMessageContent() {
            // Phase 2 的核心不变量。做成反射断言是因为它必须对<b>将来</b>的改动也成立:
            // 谁哪天顺手加一个 preview 字段, 这条会红, 而不是等到线上发现
            // "她还没看就知道内容了"。
            //
            // 刻意<b>不</b>把 "message" 列进来: messageId 是坐标, 不是内容,
            // 而一个会误报的守卫最后一定会被人加白名单绕过去 —— 那比没有守卫更糟。
            // (这条不是我想到的, 是第一版写成 contains("message") 之后被这条用例自己
            //  用 messageId 撞红的。)
            for (var f : PhoneCapability.NotificationSnapshot.PendingNotification.class.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                assertFalse(n.contains("content") || n.contains("body") || n.contains("text")
                                || n.contains("preview") || n.contains("snippet"),
                        "通知里出现了能装正文的字段: " + f.getName());
            }
        }

        @Test
        void theSnapshotReportsPendingNotificationsAsCoordinatesOnly() {
            PhoneNotification n = new PhoneNotification();
            n.setCompanionId(AGENT);
            n.setConversationId(CONV);
            n.setMessageId("m1");
            n.setRead(false);
            when(notifications.countByCompanionIdAndReadFalse(AGENT)).thenReturn(2L);
            when(notifications.findByCompanionIdOrderByCreatedAtDesc(AGENT)).thenReturn(List.of(n));

            var snap = phone.notifications(AGENT);

            assertEquals(2, snap.unreadCount());
            assertEquals(1, snap.pending().size());
            assertEquals("m1", snap.pending().get(0).messageId());
            assertNotNull(snap.takenAt());
        }

        @Test
        void aPhoneThatCannotBeHeardIsReportedAsUnavailable() {
            PhoneState dnd = new PhoneState();
            dnd.setDoNotDisturb(true);
            when(phoneStates.current(eq(AGENT), any())).thenReturn(dnd);
            when(notifications.findByCompanionIdOrderByCreatedAtDesc(AGENT)).thenReturn(List.of());

            assertFalse(phone.notifications(AGENT).available(),
                    "勿扰是'她主动不想被打扰', 与'她没看见'是两件事, 必须分开表达");
        }

        @Test
        void anUnreadablePhoneStateFailsOpen() {
            // 读不到手机状态时当作可用。方向很重要: 宁可让她被打扰一次,
            // 也不要因为一次查询失败让她静默地错过所有消息。
            when(phoneStates.current(eq(AGENT), any())).thenThrow(new RuntimeException("库挂了"));
            when(notifications.findByCompanionIdOrderByCreatedAtDesc(AGENT)).thenReturn(List.of());

            assertTrue(phone.notifications(AGENT).available());
        }
    }
}
