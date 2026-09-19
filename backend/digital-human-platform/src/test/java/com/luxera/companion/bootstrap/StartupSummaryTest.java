package com.luxera.companion.bootstrap;

import com.luxera.companion.bootstrap.StartupSummary.Recovery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupSummary} 的测试。
 *
 * <h2>为什么这一行摘要值得有测试</h2>
 * 因为它是**运维面唯一一个"装配对不对"的入口**。§8.6.7 的四个数各自回答一个
 * 运维问题, 而它们的失效方式不是抛异常, 是**印一行看起来正常的日志**:
 * 类型数少了(某个 {@code registerTypes} 没被调到)、替身数非零(有东西读不回来)
 * —— 这两种情况在屏幕上与健康状态长得一模一样, 唯一的区别就是那几个数。
 *
 * <h2>为什么其中一条测试是在钉<b>格式</b></h2>
 * 因为这一行会被 {@code grep}。人、脚本、看板都会按 {@code [Sim] 装配完成} 与
 * "{@code 条替身}" 这样的片段去找它 —— 于是措辞变成一个**接口**,
 * 而接口改变了要有东西红。这与 chat-platform 里那条"通知载荷不许带 content"
 * 的反射钉子是同一种测试: 钉的不是逻辑, 是**别人依赖的约定**。
 */
class StartupSummaryTest {

    private static Recovery healthy() {
        return new Recovery(12, 0, 8, 3);
    }

    @Test
    void 一行摘要把四个运维问题都说全了() {
        StartupSummary summary = new StartupSummary(
                47, 7, List.of(), 1, 1, healthy(), 1000L);

        String line = summary.describe();

        // 四个数各是一个运维问题(§8.6.7): 类型数/命名空间数、World 与 Human 的数量、
        // 恢复的三个数(含替身)、tick 间隔。少任何一个, 那一类故障就没有入口了。
        assertTrue(line.contains("47 个类型"), line);
        assertTrue(line.contains("7 个命名空间"), line);
        assertTrue(line.contains("1 个 World"), line);
        assertTrue(line.contains("1 个 Human"), line);
        assertTrue(line.contains("账本 12 条"), line);
        assertTrue(line.contains("0 条替身"), line);
        assertTrue(line.contains("计划 8 项"), line);
        assertTrue(line.contains("世界历史 3 天"), line);
        assertTrue(line.contains("tick 间隔 1000ms"), line);
        // 前缀是给人 grep 的锚点 —— 它比后面的内容更不该变
        assertTrue(line.startsWith("[Sim] 装配完成: "), line);
        // 摘要是一行: 换行会让别的日志插进来, 于是它再也拼不回去
        assertFalse(line.contains("\n"), "摘要必须是单行, 收到: " + line);
    }

    @Test
    void 替身数非零时摘要照样印得出来_但判据为真() {
        // 这是"有一批历史只能以替身重建"的样子: 数值上可能全对, 但解释丢了。
        // 摘要不该因为这件事而拒印 —— 恰恰相反, 它必须在场, 否则运维看不到它。
        Recovery damaged = new Recovery(12, 3, 8, 3);
        StartupSummary summary = new StartupSummary(47, 7, List.of(), 1, 1, damaged, 1000L);

        assertTrue(summary.recovery().hasOpaqueEntries(),
                "3 条替身必须让判据为真 —— 它是这一行里唯一一个'健康状态为假'的判据");
        assertTrue(summary.describe().contains("3 条替身"), summary.describe());

        assertFalse(healthy().hasOpaqueEntries(), "0 条替身是健康状态");
    }

    @Test
    void 替身数不可能超过账本总数() {
        // 替身是账本的子集。这个不等式不成立说明数的人把两个集合算混了,
        // 而那样印出来的摘要会让人以为"有 5 条读不回来"而账本只有 3 条 ——
        // 一个自相矛盾的数字比没有数字更坏。
        assertThrows(IllegalArgumentException.class,
                () -> new Recovery(3, 5, 0, 0),
                "替身多于总数时必须当场拒绝, 而不是印一行自相矛盾的摘要");
    }

    @Test
    void 恢复计数不能是负数() {
        assertThrows(IllegalArgumentException.class, () -> new Recovery(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Recovery(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Recovery(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Recovery(0, 0, 0, -1));
    }

    @Test
    void 类型与数量计数不能是负数() {
        assertThrows(IllegalArgumentException.class,
                () -> new StartupSummary(-1, 0, List.of(), 0, 0, healthy(), 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new StartupSummary(0, 0, List.of(), -1, 0, healthy(), 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new StartupSummary(0, 0, List.of(), 0, -1, healthy(), 1000L));
    }

    @Test
    void 恢复摘要不能为空() {
        // 一个可空的 Recovery 会让"账本 0 条"与"恢复根本没跑"在日志上印成同一句话 ——
        // 而 §8.5.6 的第一条硬约束就是"恢复必须在 tick 壳之前跑完"。
        // 这两件事必须分得开, 所以这里不允许用 null 表示"没有"。
        assertThrows(NullPointerException.class,
                () -> new StartupSummary(0, 0, List.of(), 0, 0, null, 1000L));
    }

    @Test
    void 没有冲突时不打告警() {
        StartupSummary summary = new StartupSummary(47, 7, List.of(), 1, 1, healthy(), 1000L);
        assertTrue(summary.conflictWarning().isEmpty(),
                "没有冲突时必须是空的 —— 调用方写 ifPresent(log::warn) 才是全部");
    }

    @Test
    void 有冲突时告警里带着冲突的类型名() {
        StartupSummary summary = new StartupSummary(47, 7,
                List.of("device.phone.v1"), 1, 1, healthy(), 1000L);

        String warning = summary.conflictWarning().orElseThrow(
                () -> new AssertionError("有一个类型名冲突, 告警不该是空的"));

        // 类名/类型名必须在消息里: 只说"有 1 个冲突"的告警, 收到的人第一件事
        // 就是去翻代码找是哪两个 —— 那句话等于没写。
        assertTrue(warning.contains("device.phone.v1"), warning);
        // 并且要说清楚后果 —— 否则读的人不知道该不该管
        assertTrue(warning.contains("遮住"), warning);
    }

    @Test
    void 冲突清单是不可变的() {
        // 摘要对象会被传给记录日志的地方, 而一个可变的清单可能在打印之前被改掉 ——
        // 于是"日志里说的"与"装配时的"不是同一件事。这是记录类的常规纪律,
        // 但它是这里唯一一处可能被漏掉的防御, 所以钉一条。
        List<String> mutable = new java.util.ArrayList<>(List.of("a.v1"));
        StartupSummary summary = new StartupSummary(1, 1, mutable, 0, 0, healthy(), 1L);

        mutable.add("b.v1");

        assertEquals(1, summary.conflicts().size(),
                "外部改动不该影响已经造好的摘要 —— 否则日志会印出一个后来才出现的冲突");
        assertThrows(UnsupportedOperationException.class, () -> summary.conflicts().add("c.v1"));
    }
}
