package com.luxera.companion.proactive;

/**
 * 主动行为决策(设计文档 53 节): 输入上下文,输出是否打扰。
 */
public record ProactiveDecision(
        boolean act,
        String title,
        String content,
        String trigger,
        double expectedValue,
        double interruptionCost) {

    public static ProactiveDecision nothing() {
        return new ProactiveDecision(false, null, null, null, 0, 0);
    }

    public static ProactiveDecision send(String title, String content, String trigger, double expectedValue, double cost) {
        return new ProactiveDecision(true, title, content, trigger, expectedValue, cost);
    }
}
