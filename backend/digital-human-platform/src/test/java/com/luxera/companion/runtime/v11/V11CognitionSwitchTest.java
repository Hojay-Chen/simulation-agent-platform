package com.luxera.companion.runtime.v11;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 §25.1 —— <b>认知开关与前面两个开关的差别</b>。
 *
 * <p>这个文件存在的理由只有一个: {@code turns.isEffective()} 必须同时看 runtime,
 * 而 {@code cognition.isEffective()} <b>不能</b>。两处判断不一样, 后人很容易"顺手统一"一下,
 * 而统一之后的现象是: 一个真的在改变行为的配置, 在诊断端点里显示为"未生效"。
 * 那种错不会报错, 只会让人做出错误的切流判断。
 */
class V11CognitionSwitchTest {

    private V11CognitionSwitch sw(boolean enabled, boolean shadow) {
        V11CognitionSwitch s = new V11CognitionSwitch();
        ReflectionTestUtils.setField(s, "enabled", enabled);
        ReflectionTestUtils.setField(s, "shadow", shadow);
        return s;
    }

    @Test
    @DisplayName("默认(生产): 算决策、记账, 但回复仍由老链决定")
    void defaults() {
        V11CognitionSwitch s = sw(false, true);
        assertFalse(s.isEnabled());
        assertTrue(s.isShadow());
        assertTrue(s.isActive(), "shadow 期必须算 —— 差异率就是切流判据");
        assertFalse(s.isEffective());
    }

    @Test
    void bothOffMeansZeroCost() {
        assertFalse(sw(false, false).isActive());
    }

    @Test
    void enabledAloneIsEffectiveEvenWithTheDeliveryChainOff() {
        // 与 turns 的关键差别: 认知决策挂在**回复路径**上, 而回复路径无论哪条主链在跑都存在。
        // 所以 runtime.enabled=false 并不让这个开关变成死配置 —— 这一条必须钉住,
        // 因为"顺便加个 runtimeSwitch 检查"是很自然的误改
        V11CognitionSwitch s = sw(true, false);
        assertTrue(s.isEffective(),
                "认知决策不依赖 V11 送达主链 —— 它改的是'回不回', 而'回不回'在今天那条链上也有");
    }

    @Test
    void shadowAloneIsNotEffective() {
        assertFalse(sw(false, true).isEffective());
        assertFalse(sw(false, false).isEffective());
    }
}
