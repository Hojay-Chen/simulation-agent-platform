package com.luxera.companion.world;

import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.runtime.WorldEventType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V11 §5.2 —— 三套事件词汇归一之后, <b>不许再漏</b>。
 *
 * <p>这个测试的全部价值在于它是<b>覆盖性</b>的而不是举例性的: 它不检查"这几个我知道的
 * 映射对不对", 它遍历 {@code WorldEventType} 的<b>每一个</b>常量, 要求映射表里都有它。
 * 于是将来有人往旧词汇里加一个常量而忘了在这边跟, 这个测试会红, 而不是那条事件
 * 在运行时静默消失。
 */
class AgentEventTypeTest {

    @Test
    void everyLegacyWorldEventTypeIsMapped() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String constant : worldEventTypeConstants()) {
            if (!AgentEventType.legacyWireTable().containsKey(constant)) {
                missing.add(constant);
            }
        }
        assertTrue(missing.isEmpty(),
                "runtime/WorldEventType 里有常量没有被映射到 AgentEventType: " + missing
                        + " —— 漏掉的后果不是编译错误, 是那类事件在运行时被静默丢弃");
    }

    @Test
    void theMappingTableIsNotVacuous() throws Exception {
        // 防止"把表清空"也能让上面那条变绿: 表必须覆盖旧词汇的每一个常量, 且非空
        int legacyCount = worldEventTypeConstants().size();
        assertTrue(legacyCount >= 14,
                "只读到 " + legacyCount + " 个 WorldEventType 常量 —— 反射多半没读到东西, 上面那条测试什么也没检查");
        assertEquals(legacyCount, AgentEventType.legacyWireTable().size(),
                "映射表大小与旧词汇常量数不一致");
    }

    @Test
    void everyExternalEventTypeIsMapped() {
        for (ExternalEventType type : ExternalEventType.values()) {
            Optional<AgentEventType> mapped = AgentEventType.fromExternal(type);
            assertTrue(mapped.isPresent(),
                    "ExternalEventType." + type + " 没有对应的世界事件类型"
                            + " —— 那条链上跑着真实的消息, 映射漏一个就是一条消息永远进不了信箱");
        }
    }

    @Test
    void chatMessageDeliveredIsReceivedNotNotified() {
        // 送达是**世界**的事实, 通知是**手机**的事实。旧链把两者合成一个事件名,
        // 于是"她还没被通知"这个状态根本无法表达 —— 这条断言钉住这个区分不被改回去。
        assertEquals(AgentEventType.USER_MESSAGE_RECEIVED,
                AgentEventType.fromExternal(ExternalEventType.CHAT_MESSAGE_DELIVERED).orElseThrow());
        assertNotEquals(AgentEventType.USER_MESSAGE_RECEIVED, AgentEventType.USER_MESSAGE_NOTIFIED);
    }

    @Test
    void thePerceptionLadderIsExactlyFiveStepsAndInOrder() {
        List<AgentEventType> ladder = new ArrayList<>();
        for (AgentEventType t : AgentEventType.values()) {
            if (t.isPerceptionLadder()) {
                ladder.add(t);
            }
        }
        assertEquals(List.of(
                        AgentEventType.USER_MESSAGE_RECEIVED,
                        AgentEventType.USER_MESSAGE_NOTIFIED,
                        AgentEventType.USER_MESSAGE_NOTICED,
                        AgentEventType.USER_MESSAGE_READ,
                        AgentEventType.USER_MESSAGE_DEFERRED),
                ladder,
                "意识阶梯的五个台阶与顺序是对外契约的一部分(设计文档 §2.2.2), 改了要一并改文档");
    }

    @Test
    void replyIsNotAnEvent() {
        // "回复"是一个动作不是一个事件(设计文档 §2.3)。它出现在这里就意味着有人
        // 又想把"她回了"塞回事件表 —— 那正是旧的 ChatBot 模型。
        for (AgentEventType t : AgentEventType.values()) {
            assertFalse(t.wire().contains("REPLY"),
                    "事件词汇里出现了 " + t.wire() + " —— 回复是动作, 归 action/ 管");
        }
    }

    @Test
    void wireNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (AgentEventType t : AgentEventType.values()) {
            assertTrue(seen.add(t.wire()), "wire 名重复: " + t.wire() + " —— 落库后无法区分");
        }
    }

    @Test
    void wireNamesRoundTrip() {
        for (AgentEventType t : AgentEventType.values()) {
            assertSame(t, AgentEventType.requireWire(t.wire()), "wire 往返失败: " + t.wire());
        }
    }

    @Test
    void recognisableHistoryIsEmptyRatherThanFatal() {
        assertTrue(AgentEventType.fromLegacyWire(null).isEmpty());
        assertTrue(AgentEventType.fromLegacyWire("  ").isEmpty());
        // 迁移期会读到已经被淘汰的旧事件名 —— 一条读不懂的历史记录不该让重放崩掉
        assertTrue(AgentEventType.fromLegacyWire("SOME_EVENT_FROM_2019").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> AgentEventType.requireWire("SOME_EVENT_FROM_2019"));
    }

    @Test
    void legacyLookupIgnoresCaseAndPadding() {
        assertEquals(Optional.of(AgentEventType.USER_MESSAGE_READ),
                AgentEventType.fromLegacyWire(" user_message_read "));
    }

    /** 读出 {@code WorldEventType} 的全部 String 常量值。 */
    private static List<String> worldEventTypeConstants() throws Exception {
        List<String> out = new ArrayList<>();
        for (Field f : WorldEventType.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                out.add((String) f.get(null));
            }
        }
        return out;
    }
}
