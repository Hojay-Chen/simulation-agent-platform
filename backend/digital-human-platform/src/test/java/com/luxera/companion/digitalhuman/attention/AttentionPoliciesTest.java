package com.luxera.companion.digitalhuman.attention;

import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用声明的注意力策略 → 数字人感知等级的映射。
 *
 * <p>这四行映射原先挂在 {@code ActionDescriptor.AttentionPolicy.toPerceptionLevel()} 上,
 * 而 {@code ActionDescriptor} 是数字人内部的一份动作描述符副本 —— R3 起描述符统一为 contracts
 * 的 {@code ActionSpec}, 映射留在 DH(见 {@link AttentionPolicies})。
 *
 * <p>四个档位逐个断言而不是抽查三个: 漏掉的那一档若被静默当成 NONE, 表现为"数字人对某类事件
 * 永远无感", 是那种没人会去查的 bug。
 */
class AttentionPoliciesTest {

    @Test
    void everyPolicyMapsToItsOwnPerceptionLevel() {
        assertEquals(PerceptionLevel.NONE, AttentionPolicies.toPerceptionLevel(AttentionPolicy.NONE));
        assertEquals(PerceptionLevel.SUBCONSCIOUS,
                AttentionPolicies.toPerceptionLevel(AttentionPolicy.SUBCONSCIOUS));
        assertEquals(PerceptionLevel.AWARE, AttentionPolicies.toPerceptionLevel(AttentionPolicy.AWARE));
        assertEquals(PerceptionLevel.FOCUSED, AttentionPolicies.toPerceptionLevel(AttentionPolicy.FOCUSED));
    }

    @Test
    void missingPolicyDefaultsToAware() {
        assertEquals(PerceptionLevel.AWARE, AttentionPolicies.toPerceptionLevel(null),
                "应用没声明注意力时按'知道了'处理, 而不是'永远不知道'");
    }
}
