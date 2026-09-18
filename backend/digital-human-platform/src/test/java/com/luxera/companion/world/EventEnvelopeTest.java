package com.luxera.companion.world;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V11 §2.2.2 —— <b>消息正文装不进信封</b>。
 *
 * <p>这组测试守的是整个 V11 最要紧的一条边界。设计文档指认的根子问题是:
 * 今天 {@code AgentRuntime.onChatMessageDelivered} 在"她注意到了吗"这个问题被问出来
 * <b>之前</b>就把整个会话的正文拉下来了。一旦正文在手, 后面所有的"在忙/没注意/已读不回"
 * 都只是已经知道内容之后找的借口。
 *
 * <p>所以这里的断言不是"我们约定不传正文", 而是"传不进去" —— 一个会在构造时炸的约束,
 * 比一句写在注释里的约定可靠得多。同时也要诚实: 它守的是形状, 不是保密
 * (有人仍可以把正文塞进一个叫 {@code x} 的键), 所以另有一条测试钉住
 * "正常的坐标键不会被误伤" —— 一个会误报的守卫最后一定会被白名单绕过去。
 */
class EventEnvelopeTest {

    private static EventEnvelope envelope(Map<String, Object> refs) {
        return EventEnvelope.of("agent-1", AgentEventType.USER_MESSAGE_RECEIVED,
                EventSource.CHAT_PLATFORM, refs);
    }

    @Test
    void refusesToCarryMessageText() {
        for (String forbidden : List.of("content", "text", "body", "preview", "snippet", "excerpt")) {
            Map<String, Object> refs = new HashMap<>();
            refs.put(forbidden, "在吗");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> envelope(refs), "键 " + forbidden + " 居然被放进了信封");
            assertTrue(e.getMessage().contains(forbidden), "报错里应当点名是哪个键: " + e.getMessage());
        }
    }

    @Test
    void snakeCaseAndCaseVariantsAreCaughtToo() {
        // 旧表里正文列就叫 sender_text —— 不归一化的话这条最容易漏
        for (String forbidden : List.of("sender_text", "senderText", "SenderText", "raw_text", "REPLY")) {
            Map<String, Object> refs = new HashMap<>();
            refs.put(forbidden, "还没回复那句");
            assertThrows(IllegalArgumentException.class, () -> envelope(refs),
                    forbidden + " 是内容类字段, 应当被拦住");
        }
    }

    @Test
    void normalCoordinatesAreNotFalsePositives() {
        // 守卫不能误伤正常坐标, 否则它会被白名单绕过 —— 那时它就不再守任何东西了。
        // 特别是 messageIds / messageId: 它们含 "message" 但那是 id, 不是正文。
        EventEnvelope ok = envelope(Map.of(
                "conversationId", "conv-9",
                "messageIds", List.of("m1", "m2"),
                "messageId", "m2",
                "userId", "u-3",
                "phase", "live",
                "source", "chat-platform"));
        assertEquals("conv-9", ok.str("conversationId"));
        assertEquals(List.of("m1", "m2"), ok.strings("messageIds"));
        assertEquals("m2", ok.str("messageId"));
    }

    @Test
    void referencesAreImmutableOnceInside() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("conversationId", "conv-1");
        EventEnvelope e = envelope(mutable);
        mutable.put("conversationId", "被改掉了");
        assertEquals("conv-1", e.str("conversationId"),
                "信封必须拷贝一份 —— 否则调用者手上的 map 可以事后改变她已经收到的信");
        assertThrows(UnsupportedOperationException.class, () -> e.references().put("x", 1));
    }

    @Test
    void withReferenceStillCannotSmuggleText() {
        EventEnvelope e = envelope(Map.of("conversationId", "c1"));
        assertThrows(IllegalArgumentException.class, () -> e.withReference("content", "在吗"));
    }

    @Test
    void eventIdAndAgentIdAreMandatory() {
        // eventId 是幂等键。一个没有 id 的事件无法去重, 也无法重放 —— 它只能被丢掉,
        // 而"被丢掉"和"处理过了"在数据库里长得一模一样, 这正是本次要消灭的歧义。
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope(null, "a", AgentEventType.USER_MESSAGE_READ,
                        EventSource.CHAT_PLATFORM, EventPriority.NORMAL, LocalDateTime.now(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope("e1", "  ", AgentEventType.USER_MESSAGE_READ,
                        EventSource.CHAT_PLATFORM, EventPriority.NORMAL, LocalDateTime.now(), Map.of()));
    }

    @Test
    void deterministicIdIsStableAndSanitised() {
        EventEnvelope a = EventEnvelope.withDeterministicId("agent-1",
                AgentEventType.USER_MESSAGE_RECEIVED, EventSource.CHAT_PLATFORM,
                "agent-1/conv-9/m2/live", Map.of());
        EventEnvelope b = EventEnvelope.withDeterministicId("agent-1",
                AgentEventType.USER_MESSAGE_RECEIVED, EventSource.CHAT_PLATFORM,
                "agent-1/conv-9/m2/live", Map.of());
        assertEquals(a.eventId(), b.eventId(), "同一件事必须算出同一个 id, 否则幂等不成立");
        assertTrue(a.eventId().matches("[A-Za-z0-9._-]+"),
                "id 里的斜杠等字符要归一化, 否则它会变成一个跨表引用的隐患: " + a.eventId());

        EventEnvelope other = EventEnvelope.withDeterministicId("agent-1",
                AgentEventType.USER_MESSAGE_RECEIVED, EventSource.CHAT_PLATFORM,
                "agent-1/conv-9/m3/live", Map.of());
        assertNotEquals(a.eventId(), other.eventId(), "不同的来源键必须给出不同的 id");
    }

    @Test
    void twoAgentsWithTheSameSourceKeyDoNotCollide() {
        // 幂等是全局的(唯一约束 + existsByEventId), 所以 id 里不带 agent 的话,
        // 一个广播事件或一次给所有人的定时唤醒会让<b>后一个 agent 的信被静默丢掉</b>:
        // 没有异常、没有日志, 收信人永远不知道有这件事。
        EventEnvelope a = EventEnvelope.withDeterministicId("agent-a", AgentEventType.SCHEDULED_WAKEUP,
                EventSource.SCHEDULE, "2026-09-18T09:00", Map.of());
        EventEnvelope b = EventEnvelope.withDeterministicId("agent-b", AgentEventType.SCHEDULED_WAKEUP,
                EventSource.SCHEDULE, "2026-09-18T09:00", Map.of());
        assertNotEquals(a.eventId(), b.eventId(),
                "同一个来源键在不同 agent 上必须算出不同的 id, 否则第二个 agent 收不到唤醒");
        assertTrue(a.eventId().contains("agent-a"), "id 里应当看得出是给谁的: " + a.eventId());
    }

    @Test
    void noPathologicalInputOverflowsTheColumn() {
        // event_id 列是 varchar(128)。逐个类型地试, 而不是只试一种 —— 长度预算里
        // 有一项是 wire 名的长度, 将来加一个更长的类型名就可能把它顶爆,
        // 而那会变成一个 INSERT 失败(整条链报错), 不是丢消息。
        for (AgentEventType type : AgentEventType.values()) {
            EventEnvelope e = EventEnvelope.withDeterministicId("a".repeat(200), type,
                    EventSource.SYSTEM, "k".repeat(500), Map.of());
            assertTrue(e.eventId().length() <= 128,
                    type + " 的幂等键长度 " + e.eventId().length() + " 超了 event_id 的列宽");
        }
    }

    @Test
    void missingOptionalFieldsFallBackInsteadOfExploding() {
        EventEnvelope e = new EventEnvelope("e1", "a1", AgentEventType.THOUGHT_FORMED,
                null, null, null, null);
        assertEquals(EventSource.SYSTEM, e.source());
        assertEquals(EventPriority.NORMAL, e.priority());
        assertTrue(e.references().isEmpty());
        assertTrue(e.occurredAt() != null);
    }

    @Test
    void stringsIsForgivingAboutShapes() {
        EventEnvelope e = envelope(Map.of("messageIds", List.of("m1", "m2")));
        assertEquals(List.of(), e.strings("nope"), "没有这个键 → 空列表, 不抛");
        assertNull(e.str("nope"));
        // 单个字符串也当作一条
        EventEnvelope single = envelope(Map.of("messageId", "m1"));
        assertEquals(List.of(), single.strings("messageId"), "非列表一律返回空列表而不是包装成一条");
        assertTrue(new ArrayList<>(e.strings("messageIds")).size() == 2);
    }

    @Test
    void ladderMembershipComesFromTheTypeNotTheCaller() {
        assertTrue(envelope(Map.of()).onPerceptionLadder());
        EventEnvelope fact = EventEnvelope.of("a1", AgentEventType.ACTIVITY_ENDED,
                EventSource.LIFE_SIMULATION, Map.of());
        assertTrue(!fact.onPerceptionLadder());
        assertTrue(fact.type().isWorldFact());
    }

    @Test
    void describeNeverLeaksContentBecauseThereIsNone() {
        EventEnvelope e = envelope(Map.of("conversationId", "conv-1", "messageIds", List.of("m1")));
        String line = e.describe();
        assertTrue(line.contains("USER_MESSAGE_RECEIVED"));
        assertTrue(line.contains("conv-1"));
    }
}
