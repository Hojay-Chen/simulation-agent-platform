package com.luxera.companion.agent;

import com.luxera.companion.behavior.BehaviorDecision;

/** 一次认知处理的产物(设计文档 §27) */
public record CognitiveResult(String reply, String rawReply,
                              PerceptionEngine.Perception perception,
                              BehaviorDecision decision,
                              CompanionContext context) {}
