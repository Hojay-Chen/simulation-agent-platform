package com.luxera.companion.persona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态字符串 → 枚举的解析。**这个类守的是整个开关里唯一一处"往危险方向兜底"的决定。**
 *
 * <p>把"暂停"误读成"运行"要多花一点钱; 把"运行"误读成"暂停"会让一个 agent 变成哑巴,
 * 而症状是"她不回我了" —— 没有报错、没有日志、没有任何指向这里的线索。所以认不出来的
 * 值一律当运行中, 而这条规矩必须有测试钉住: 它看起来像是"忘了处理某个枚举值",
 * 下一个人很容易顺手"修"成抛异常或返回 PAUSED。
 */
class AgentLifecycleTest {

    @Test
    void theTwoRealValuesRoundTrip() {
        assertEquals(AgentLifecycle.PAUSED, AgentLifecycle.of("paused"));
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of("active"));
        assertEquals("paused", AgentLifecycle.PAUSED.wire());
        assertEquals("active", AgentLifecycle.ACTIVE.wire());
    }

    @Test
    void caseAndWhitespaceAreForgivenBecauseTheyAreTypingHabitsNotIntent() {
        assertEquals(AgentLifecycle.PAUSED, AgentLifecycle.of("PAUSED"));
        assertEquals(AgentLifecycle.PAUSED, AgentLifecycle.of("  Paused  "));
        assertEquals(AgentLifecycle.PAUSED, AgentLifecycle.of("paused\n"),
                "换行也算打字习惯 —— 有人从表格里复制出来的值会带一个");
    }

    @Test
    void unknownOrMissingValuesMeanRunningNotSilentlyStopped() {
        // 这一条如果失败, 说明有人把兜底方向改成了"停"。改回去之前请先想清楚:
        // 你要的是一次静默停机, 还是一次能被看见的多花一点钱?
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of(null), "null = 存量行没写过这一列");
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of(""));
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of("   "));
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of("sleeping"),
                "将来若真的加了第三档, 默认行为必须是继续跑 —— 否则加一个值就停一批 agent");
        assertEquals(AgentLifecycle.ACTIVE, AgentLifecycle.of("paused!"),
                "只有整词才算 —— 认不出的语法拼法不许被当成停");
    }

    @Test
    void isPausedAnswersOnlyForTheOneValueThatMeansStop() {
        assertTrue(AgentLifecycle.PAUSED.isPaused());
        assertFalse(AgentLifecycle.ACTIVE.isPaused());
    }
}
