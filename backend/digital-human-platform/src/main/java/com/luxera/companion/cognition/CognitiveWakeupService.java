package com.luxera.companion.cognition;

import org.springframework.stereotype.Service;

/**
 * §22-§23 Cognitive Wakeup: 事件驱动认知分级。
 * Agent 平时 IDLE(生活继续), 只有重要事件才唤醒认知系统。
 *
 * 唤醒等级:
 * - NO_WAKE: 不值得打断(优惠券到账等)
 * - MICRO_WAKE: 轻量注意("哈哈")
 * - ATTENTION: 需要看一眼
 * - DELIBERATION: 需要认真思考(被裁员/重要消息)
 * - DEEP_THINKING: 深度认知(关系冲突/重大人生事件)
 *
 * 核心: 不是每件事都触发 LLM。低价值事件 → NO_WAKE/MICRO_WAKE(不调 LLM)。
 */
@Service
public class CognitiveWakeupService {

    public enum WakeLevel {
        NO_WAKE, MICRO_WAKE, ATTENTION, DELIBERATION, DEEP_THINKING
    }

    /** 唤醒评估输入 */
    public record WakeupInput(
            String eventType,          // MESSAGE / NOTIFICATION / ACTIVITY_END / EMOTION_CHANGE / THOUGHT / RELATIONSHIP
            String content,            // 事件内容(消息文本等)
            double importance,         // 事件重要性 0-1
            double novelty,            // 新颖度 0-1
            double emotionalImpact,    // 情绪冲击 0-1
            double goalRelevance,      // 目标相关性 0-1
            double socialRelevance) {  // 社会/关系相关性 0-1
    }

    /**
     * 评估事件唤醒等级。
     * 规则优先(确定性), 复杂冲突可升级到 LLM(当前阶段全规则)。
     */
    public WakeLevel evaluate(WakeupInput input) {
        return evaluate(input, 0.5);
    }

    /**
     * §三十一/§三十二 事件价值模型: 同一句话, 对不同关系的人唤醒不同。
     *
     * relationshipWeight 0-1(亲密度+好感):
     * - 亲密的人 → 社会相关性放大, 她更敏感("他失业了" vs "陌生人失业了")
     * - 陌生/普通 → 社会相关性被压低, 浅层唤醒
     */
    public WakeLevel evaluate(WakeupInput input, double relationshipWeight) {
        if (input == null) return WakeLevel.NO_WAKE;
        double relWeight = Math.max(0, Math.min(1, relationshipWeight));
        // 关系修正: 亲密关系放大社会相关性(0.5 → 1.0 区间), 陌生关系压低(0.5 → 0.2)
        double socialRelevance = input.socialRelevance() * (0.4 + relWeight * 1.1);
        socialRelevance = Math.max(0, Math.min(1, socialRelevance));

        // 事件类型硬规则
        if ("NOTIFICATION".equals(input.eventType())) {
            // 普通通知(促销等) → 不唤醒
            if (input.importance() < 0.2) return WakeLevel.NO_WAKE;
            if (input.importance() < 0.4) return WakeLevel.MICRO_WAKE;
        }

        // 内容关键词: 用户表达强烈情绪 → 至少 DELIBERATION
        if (input.content() != null) {
            if (containsAny(input.content(), "被裁", "裁员", "失业", "分手", "去世", "生病", "住院", "车祸", "离婚")) {
                return WakeLevel.DEEP_THINKING;
            }
            if (containsAny(input.content(), "难过", "哭", "崩溃", "压力", "焦虑", "加班到很晚", "想不开")) {
                return WakeLevel.DELIBERATION;
            }
            if (containsAny(input.content(), "哈哈", "hh", "笑死", "嘻嘻")) {
                // 亲密的人发"哈哈" → 值得看一眼; 普通关系 → 轻量注意
                return relWeight >= 0.6 ? WakeLevel.ATTENTION : WakeLevel.MICRO_WAKE;
            }
        }

        // 综合评分: importance + emotional + 关系修正后的 social
        double score = input.importance() * 0.4 + input.emotionalImpact() * 0.3 + socialRelevance * 0.3;
        if (score >= 0.75 - 1e-9) return WakeLevel.DELIBERATION;
        if (score >= 0.55 - 1e-9) return WakeLevel.ATTENTION;

        // §23 修正: 用户发来的消息绝不 NO_WAKE —— 真人收到消息至少会"知道", 只是可能不深想。
        // NO_WAKE 只保留给低价值系统通知(优惠券等)。
        if ("MESSAGE".equals(input.eventType())) {
            // 亲密关系: 即使普通问候也值得认真看一眼
            if (relWeight >= 0.6 && input.socialRelevance() >= 0.3) {
                return WakeLevel.ATTENTION;
            }
            // 普通问候/寒暄(社交相关性足够) → 至少 ATTENTION(值得看一眼)
            if (socialRelevance >= 0.4 || score >= 0.3 - 1e-9) {
                return WakeLevel.ATTENTION;
            }
            return WakeLevel.MICRO_WAKE;
        }
        if (score >= 0.3 - 1e-9) return WakeLevel.MICRO_WAKE;
        return WakeLevel.NO_WAKE;
    }

    /** 该唤醒等级是否需要调用 LLM(认知处理) */
    public boolean requiresCognition(WakeLevel level) {
        return level == WakeLevel.ATTENTION || level == WakeLevel.DELIBERATION
                || level == WakeLevel.DEEP_THINKING;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
