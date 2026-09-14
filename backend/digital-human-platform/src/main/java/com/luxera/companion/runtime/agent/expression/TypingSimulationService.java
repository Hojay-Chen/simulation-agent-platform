package com.luxera.companion.runtime.agent.expression;

import org.springframework.stereotype.Component;

/**
 * §57 Typing Simulation: 模拟真人打字节奏。
 * 第一阶段不模拟逐字符输入, 只模拟 typingDuration / messageGap / typingPause。
 *
 * - 短消息: 0.8~2.0s
 * - 复杂消息: 2~8s
 * - 多消息: 每条之间 0.8~4s
 */
@Component
public class TypingSimulationService {

    /** 估算一段消息的打字时长(ms): 按内容长度 + 复杂度 */
    public long typingDurationMs(String content, double emotionalComplexity) {
        if (content == null || content.isBlank()) return 800;
        int len = content.length();
        // 基础: 每字 ~70ms(中文), 短消息下限 800ms
        double base = Math.max(800, len * 70);
        // 情绪复杂度: 深度情绪 → 更慢(边想边说)
        base += emotionalComplexity * 2500;
        // 上限 8s
        return (long) Math.min(8000, base);
    }

    /** 消息之间的自然间隔(ms): 0.8~4s, 复杂度高 → 间隔更长 */
    public long messageGapMs(double emotionalComplexity, int segmentIndex) {
        // 第一条前通常 0.8~1.5s(刚看到就回); 后续每条停顿更长
        double base = segmentIndex == 0 ? 900 : 1500;
        return (long) Math.min(4000, base + emotionalComplexity * 2000);
    }

    /** 单条短消息(一句话)的轻快节奏 */
    public long shortReplyDurationMs() {
        return 900 + (long) (Math.random() * 600);   // 0.9~1.5s
    }
}
