package com.luxera.companion.runtime.agent.event;

import java.util.List;

/**
 * Event Simulation Agent 输出(§28): 候选事件 + 概率 + 触发条件 + 可能后果。
 * LLM 只提出候选, 真正是否发生由 World Runtime 决定。
 */
public record EventSimulationResult(
        List<EventCandidate> candidates,
        boolean fallback) {

    public record EventCandidate(String eventType, double probability,
                                 String trigger, List<String> consequences) {
    }

    public static final String NORMAL = "NORMAL";
    public static final String FORGOT_UMBRELLA = "FORGOT_UMBRELLA";
    public static final String MEET_ACQUAINTANCE = "MEET_ACQUAINTANCE";
    public static final String SUDDEN_PLAN_CHANGE = "SUDDEN_PLAN_CHANGE";
    public static final String WORK_INTERRUPTION = "WORK_INTERRUPTION";
    public static final String GOOD_NEWS = "GOOD_NEWS";

    public static EventSimulationResult empty() {
        return new EventSimulationResult(List.of(), true);
    }
}
