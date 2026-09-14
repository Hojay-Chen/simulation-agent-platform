package com.luxera.companion.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * §71/§72 Anti-AI Pattern Evaluation: 专门检测典型的"AI 行为"。
 * 目标不是追求 100% 欺骗, 而是"在正常聊天体验中, 不因典型 AI 行为而让用户轻易识别为 AI"。
 *
 * 检测的反 AI 模式(§72):
 * - 每次秒回(replyLatency < 阈值)
 * - 每次都很完整(永远长段落)
 * - 每次都积极回应(never ignores)
 * - 永远不主动结束(never ends)
 * - 永远记得上下文(no forgetting)
 * - 永远情绪稳定(no fluctuation)
 * - 每次只发一条消息(no multi-message)
 * - 主动消息过多(proactive spam)
 */
@Component
public class AntiAIPatternEvaluator {

    /** 行为指标输入(由 Runtime 统计) */
    public record BehaviorStats(
            long totalReplies,
            long instantReplies,          // 秒回(<3s)
            long deferredReplies,         // 延迟回复
            long ignoredMessages,         // 忽略
            long proactiveMessages,       // 主动消息
            long endedConversations,      // 主动结束
            long multiMessageReplies,     // 连发多条
            long forgetEvents,            // 遗忘/没想起
            double avgReplyLength) {
    }

    /** 评估结果: 命中的反 AI 模式 + 健康度 */
    public record EvalResult(List<String> aiPatterns, double humanLikenessScore, String summary) {
        public boolean passes() {
            return aiPatterns.isEmpty();
        }
    }

    /** 秒回阈值(ms) */
    private static final long INSTANT_THRESHOLD_MS = 3000;

    /**
     * 评估一段交互史是否存在反 AI 模式。
     * 分数越低越像 AI; 0-1 表示真人感。
     */
    public EvalResult evaluate(BehaviorStats s) {
        List<String> patterns = new ArrayList<>();
        double penalties = 0;

        if (s.totalReplies() > 0) {
            // 秒回比例过高
            double instantRate = (double) s.instantReplies() / s.totalReplies();
            if (instantRate > 0.8) {
                patterns.add("每次秒回(" + Math.round(instantRate * 100) + "%)");
                penalties += 0.25;
            }

            // 从不忽略: 真人偶尔会不回
            if (s.ignoredMessages() == 0 && s.deferredReplies() == 0 && s.totalReplies() > 20) {
                patterns.add("从不忽略/从不延迟(永远积极回应)");
                penalties += 0.2;
            }

            // 从不主动结束对话
            if (s.endedConversations() == 0 && s.totalReplies() > 30) {
                patterns.add("永远不主动结束对话");
                penalties += 0.15;
            }

            // 每次只发一条(缺乏真人连发节奏)
            double multiRate = (double) s.multiMessageReplies() / s.totalReplies();
            if (multiRate == 0 && s.totalReplies() > 15) {
                patterns.add("每次只发一条消息");
                penalties += 0.1;
            }
        }

        // 主动消息过多(真人不会一直主动轰炸)
        if (s.totalReplies() > 0) {
            double proactiveRate = (double) s.proactiveMessages() / (s.totalReplies() + s.proactiveMessages());
            if (proactiveRate > 0.5) {
                patterns.add("主动消息过多(" + Math.round(proactiveRate * 100) + "%)");
                penalties += 0.2;
            }
        }

        // 从不遗忘(真人会忘)
        if (s.forgetEvents() == 0 && s.totalReplies() > 40) {
            patterns.add("永远记得所有上下文");
            penalties += 0.1;
        }

        // 平均回复过长(永远完整段落)
        if (s.avgReplyLength() > 120 && s.totalReplies() > 10) {
            patterns.add("每次回复都很完整(平均" + Math.round(s.avgReplyLength()) + "字)");
            penalties += 0.15;
        }

        double score = Math.max(0, Math.min(1, 1 - penalties));
        String summary = patterns.isEmpty()
                ? "未检测到明显反 AI 模式, 真人感良好"
                : "检测到 " + patterns.size() + " 个反 AI 模式: " + String.join("; ", patterns);
        return new EvalResult(patterns, score, summary);
    }
}
