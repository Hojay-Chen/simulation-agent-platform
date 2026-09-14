package com.luxera.companion.digitalhuman.attention;

import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.digitalhuman.perception.PerceptionLevel;

/**
 * 注意力策略 → 感知等级的映射。
 *
 * <p>应用在 manifest 里用 {@link AttentionPolicy} 声明"这个动作/这类事件该占数字人多少注意力";
 * 那是应用作者说得清、也应该说的话。但"注意力"翻译成数字人内部的
 * {@link PerceptionLevel} 是数字人自己的事 —— 映射只能朝这个方向, 反过来会让 contracts 依赖
 * digital-human-platform。
 *
 * <p>本类原先叫 {@code ActionDescriptor.AttentionPolicy.toPerceptionLevel()}, 而
 * {@code ActionDescriptor} 是数字人内部的一份动作描述符副本。R3 起描述符统一为 contracts 的
 * {@code ActionSpec}, 于是这个映射落在这里 —— 它是 DH 里唯一一处认识 {@code AttentionPolicy}
 * 的地方。
 */
public final class AttentionPolicies {

    private AttentionPolicies() {
    }

    /**
     * 不写 default: {@link AttentionPolicy} 新增档位时这里必须编译失败,
     * 而不是把新档位静默当成 {@code NONE}(=数字人永远不知道)。
     */
    public static PerceptionLevel toPerceptionLevel(AttentionPolicy policy) {
        if (policy == null) return PerceptionLevel.AWARE;
        return switch (policy) {
            case NONE -> PerceptionLevel.NONE;
            case SUBCONSCIOUS -> PerceptionLevel.SUBCONSCIOUS;
            case AWARE -> PerceptionLevel.AWARE;
            case FOCUSED -> PerceptionLevel.FOCUSED;
        };
    }
}
