package com.luxera.companion.digitalhuman.perception;

/**
 * V10 §10.3 PerceptionLevel: 感知等级。
 *
 * 数字人对一个外部事件的感知不是二元(收到/没收到), 而是分级:
 * - NONE:        完全不知道(静音/勿扰/手机不在身边/深度沉浸)
 * - SUBCONSCIOUS: 潜意识层面(隐约觉得有什么, 但没形成注意)
 * - AWARE:       意识到有事件发生(可能看一眼)
 * - FOCUSED:     完全注意到(会停下当前事去处理)
 */
public enum PerceptionLevel {

    NONE,
    SUBCONSCIOUS,
    AWARE,
    FOCUSED;

    /** 是否足以引起认知(进入 Awareness → Cognition) */
    public boolean triggersCognition() {
        return this == AWARE || this == FOCUSED;
    }

    /** 是否强烈到需要立即行动 */
    public boolean demandsAttention() {
        return this == FOCUSED;
    }
}
