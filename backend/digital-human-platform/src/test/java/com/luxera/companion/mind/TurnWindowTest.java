package com.luxera.companion.mind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §8.3 —— <b>窗口配错了要在启动时炸</b>。
 *
 * <p>这三条校验的意义不在"参数不合法", 而在两种<b>不会报错的</b>故障模式:
 * <ul>
 *   <li>窗口 0 → 每句话各自成回合。现象是"她突然每句话都回一次", 看起来只是话多;</li>
 *   <li>窗口无限大 → 她再也不回话。现象是"她不理我了", 而日志里一切正常。</li>
 * </ul>
 * 一个在深夜表现为性格变化的配置错误, 比一个启动失败难查一百倍。
 */
class TurnWindowTest {

    @Test
    @DisplayName("出厂值: 4 秒静默 / 45 秒硬上限 / 最多 8 条")
    void defaults() {
        TurnWindow w = TurnWindow.defaults();
        assertEquals(4_000, w.quietWindowMs());
        assertEquals(45_000, w.maxWindowMs());
        assertEquals(8, w.maxMessages());
    }

    @Test
    void quietWindowMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TurnWindow(0, 45_000, 8));
        assertThrows(IllegalArgumentException.class, () -> new TurnWindow(-1, 45_000, 8));
    }

    @Test
    void hardLimitCannotBeShorterThanTheQuietWindow() {
        // 否则静默窗口永远轮不到生效, 硬上限成了唯一判据 —— 那不是配置, 是笔误
        assertThrows(IllegalArgumentException.class, () -> new TurnWindow(10_000, 5_000, 8));
    }

    @Test
    void maxMessagesMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new TurnWindow(4_000, 45_000, 0));
    }

    @Test
    void equalQuietAndHardWindowsAreLegal() {
        // 边界是"不能更短", 相等是合法的: 那表示"到点就封, 不等静默"
        assertDoesNotThrow(() -> new TurnWindow(4_000, 4_000, 8));
    }

    @Test
    void sealPointsAreDerivedFromTheGivenTimestamps() {
        TurnWindow w = new TurnWindow(4_000, 45_000, 8);
        LocalDateTime t = LocalDateTime.of(2026, 9, 18, 10, 0, 0);
        assertEquals(t.plusSeconds(4), w.quietSealAt(t));
        assertEquals(t.plusSeconds(45), w.hardSealAt(t));
    }

    @Test
    void nullTimestampsYieldNullSealPoints() {
        // 回合还没有时间戳时不封口 —— 返回 now 会让它在下一秒被封掉
        TurnWindow w = TurnWindow.defaults();
        assertNull(w.quietSealAt(null));
        assertNull(w.hardSealAt(null));
    }

    @Test
    void fullIsInclusiveOfTheLimit() {
        TurnWindow w = new TurnWindow(4_000, 45_000, 8);
        assertFalse(w.full(7));
        assertTrue(w.full(8));
        assertTrue(w.full(9));
    }
}
