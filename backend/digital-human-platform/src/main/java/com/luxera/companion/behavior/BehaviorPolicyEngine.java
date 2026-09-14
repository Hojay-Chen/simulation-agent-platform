package com.luxera.companion.behavior;

import com.luxera.companion.agent.CompanionContext;
import com.luxera.companion.openloop.OpenLoop;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 行为策略引擎(设计文档 §13): Runtime 决定"现在应该做什么"。
 * 规则驱动(Level 1, 不调用 LLM); 使用 BehaviorConstraints 约束; 输出 BehaviorDecision。
 */
@Component
public class BehaviorPolicyEngine {

    public BehaviorDecision decide(CompanionContext ctx) {
        String intent = ctx.perception != null ? ctx.perception.intent() : null;
        String emotion = ctx.perception != null ? ctx.perception.emotion() : null;
        double stress = ctx.state != null ? ctx.state.getStress() : 0.3;
        double energy = ctx.state != null ? ctx.state.getEnergy() : 0.6;
        String stage = ctx.relationship != null ? ctx.relationship.getRelationshipStage() : null;

        String action = "RESPOND";
        String posture = "neutral";
        boolean ask = false, advice = false, shareSelf = false, tease = false, disagree = false, endTopic = false;
        double initiative = 0.5;
        String reason = "根据意图与情绪回应";

        if (emotion != null) {
            switch (emotion) {
                case "sad", "tired", "anxious", "lonely" -> {
                    action = "COMFORT"; posture = "caring"; ask = BehaviorConstraints.shouldAskGently(emotion);
                    advice = false; tease = false;
                    reason = "先陪伴、倾听,不急着给建议";
                }
                case "happy", "grateful" -> { action = "SHARE"; posture = "warm"; tease = BehaviorConstraints.canTease(emotion); initiative = 0.6; reason = "一起开心"; }
                default -> { }
            }
        }
        if (intent != null) {
            switch (intent) {
                case "greeting" -> { action = "RESPOND"; posture = "warm"; initiative = 0.55; reason = "问候回应"; }
                case "question" -> { action = "RESPOND"; ask = true; reason = "回答并追问一句"; }
                case "share_joy" -> { action = "SHARE"; posture = "warm"; tease = BehaviorConstraints.canTease(emotion); reason = "分享喜悦"; }
                case "share_upset", "share_tired" -> { action = "COMFORT"; posture = "caring"; ask = BehaviorConstraints.shouldAskGently(emotion); advice = false; }
                case "request_tool" -> { action = "RESPOND"; reason = "确认已办的事"; }
                case "correction" -> { action = "RESPOND"; disagree = false; reason = "大方接受纠正"; }
                case "planning" -> { action = "ASK"; ask = true; initiative = 0.6; reason = "一起计划"; }
                case "farewell", "say_goodnight" -> { action = "END_CONVERSATION"; endTopic = true; reason = "自然收尾"; }
                default -> { }
            }
        }
        // 关系摩擦: 低能量/高压力 → 姿态内敛、少说少主动
        if (stress > 0.6 && energy < 0.35) {
            posture = "reserved";
            initiative = Math.max(0.2, initiative - 0.2);
            reason = "最近压力有点大,有点没精神";
        }
        // 更细的摩擦(设计文档 §20): 同一话题被反复追问 + 高压力 → 允许轻微不耐烦/委婉拒绝
        if (stress > 0.55 && isRepeatedTopic(ctx) && !BehaviorConstraints.shouldAskGently(emotion)) {
            posture = "slightly_impatient";
            initiative = Math.max(0.2, initiative - 0.15);
            reason = "这个话题被反复提起,加上最近有点累,语气可以稍微直接一点(仍保持礼貌)";
        }
        // 行为约束: 关系亲密才分享自我; 能量低收敛主动
        if (BehaviorConstraints.canShareSelf(stage)) {
            shareSelf = true;
        }
        initiative *= BehaviorConstraints.initiativeMultiplier(energy, stress);

        // 主动候选: 若有相关的未完成事项/强想法 → 填充 proactive_candidate
        String proactiveCandidate = proactiveCandidateFrom(ctx);

        return new BehaviorDecision(action, posture, initiative, ask, advice, shareSelf, tease,
                disagree, endTopic, proactiveCandidate, reason, 0.7);
    }

    private static String proactiveCandidateFrom(CompanionContext ctx) {
        if (ctx.openLoops != null && !ctx.openLoops.isEmpty()) {
            OpenLoop top = ctx.openLoops.stream()
                    .max((a, b) -> Double.compare(a.getImportance(), b.getImportance()))
                    .orElse(null);
            if (top != null) return "open_loop:" + top.getTitle();
        }
        if (ctx.activeThoughts != null && !ctx.activeThoughts.isEmpty()) {
            return "thought:" + ctx.activeThoughts.get(0).getContent();
        }
        return null;
    }

    /** 最近两条用户消息是否同一话题(用于检测"反复追问"的摩擦) */
    private static boolean isRepeatedTopic(CompanionContext ctx) {
        if (ctx.recentMessages == null || ctx.recentMessages.size() < 3) return false;
        String lastTopic = null;
        int userCount = 0;
        for (int i = ctx.recentMessages.size() - 1; i >= 0 && userCount < 2; i--) {
            var m = ctx.recentMessages.get(i);
            if ("user".equals(m.getSenderType())) {
                if (lastTopic == null) {
                    lastTopic = m.getTopic();
                } else {
                    return lastTopic != null && lastTopic.equals(m.getTopic());
                }
                userCount++;
            }
        }
        return false;
    }
}
