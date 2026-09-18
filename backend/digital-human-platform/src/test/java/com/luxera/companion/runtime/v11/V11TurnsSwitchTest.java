package com.luxera.companion.runtime.v11;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §25.1 —— <b>回合开关与送达开关是两个开关</b>。
 *
 * <p>这里钉住的不是"能读配置", 而是两条会让切流那天无法归因的组合:
 * <ul>
 *   <li>{@code effective} 必须是 {@code enabled && runtime.enabled} —— 回合合并长在
 *       V11 送达主链上, 主链没接管时它只是一行配置。诊断端点直接读这个判断,
 *       而不是各自去拼两个开关(各自拼 = 迟早有一处拼错, 而那一处会显示"已生效")。</li>
 *   <li>{@code active} 含 shadow —— shadow 期状态机必须跑, 否则合并率这个判据
 *       在切流之前根本读不到。</li>
 * </ul>
 */
class V11TurnsSwitchTest {

    private V11TurnsSwitch sw(boolean turnsEnabled, boolean turnsShadow, boolean runtimeEnabled) {
        V11RuntimeSwitch runtime = new V11RuntimeSwitch();
        ReflectionTestUtils.setField(runtime, "enabled", runtimeEnabled);
        ReflectionTestUtils.setField(runtime, "shadow", false);

        V11TurnsSwitch s = new V11TurnsSwitch(runtime);
        ReflectionTestUtils.setField(s, "enabled", turnsEnabled);
        ReflectionTestUtils.setField(s, "shadow", turnsShadow);
        return s;
    }

    @Test
    @DisplayName("默认(生产): 聚合在 shadow 跑, 认知仍归老链")
    void defaults() {
        V11TurnsSwitch s = sw(false, true, true);
        assertFalse(s.isEnabled());
        assertTrue(s.isShadow());
        assertTrue(s.isActive(), "shadow 期状态机要跑 —— 合并率就是切流判据");
        assertFalse(s.isEffective());
    }

    @Test
    void bothOffMeansZeroCost() {
        V11TurnsSwitch s = sw(false, false, true);
        assertFalse(s.isActive());
    }

    @Test
    void enabledWithoutTheRuntimeMainChainIsNotEffective() {
        // 这是最容易被误读的一种组合: 配置里写着 enabled=true, 而行为一点没变
        V11TurnsSwitch s = sw(true, true, false);
        assertTrue(s.isEnabled());
        assertFalse(s.isEffective(),
                "runtime.enabled=false 时回合合并不会有任何效果 —— 老链没有'注意到了但还没读'这个状态");
    }

    @Test
    void effectiveOnlyWhenBothSwitchesAgree() {
        assertTrue(sw(true, true, true).isEffective());
        assertTrue(sw(true, false, true).isEffective());
        assertFalse(sw(true, true, false).isEffective());
        assertFalse(sw(false, true, true).isEffective());
    }
}
