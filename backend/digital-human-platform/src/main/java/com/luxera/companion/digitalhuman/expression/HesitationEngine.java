package com.luxera.companion.digitalhuman.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * V10 §5.3 Hesitation Engine: 犹豫/停顿/省略/弱化。
 *
 * 真人表达特征:
 * - NoticeLevel 低/不确定时 → 句尾加 `...` / `嗯...` / `就这样吧`
 * - 句首加 `那个/其实/怎么说呢/我想想`
 * - 在 ConversationRequest dynamicSuffix 注入 `hesitation_hint` 行,
 *   LLM 看到后自然带出
 */
@Slf4j
@Component
public class HesitationEngine {

    private static final List<String> SENTENCE_END_HESITATIONS = List.of(
            "...", "嗯...", "就这样吧...", "然后呢...", "不知道怎么说...", "大概是这样..."
    );

    private static final List<String> SENTENCE_START_HESITATIONS = List.of(
            "那个...", "其实...", "怎么说呢...", "我想想...", "怎么说好呢..."
    );

    private static final List<String> WEAKENING_PHRASES = List.of(
            "吧", "呢", "大概", "可能", "应该", "差不多", "或许"
    );

    public HesitationMarkers computeHesitation(HesitationContext ctx) {
        HesitationMarkers m = new HesitationMarkers();

        double noticeLevel = ctx.noticeLevel();
        double confidence = ctx.confidence() != null ? ctx.confidence() : 0.5;

        // NoticeLevel 低/confidence 低 → 更多犹豫
        double hesitationScore = (1.0 - noticeLevel) * 0.7 + (1.0 - confidence) * 0.3;

        if (ThreadLocalRandom.current().nextDouble() < hesitationScore) {
            // 句尾犹豫
            m.sentenceEnd = pick(SENTENCE_END_HESITATIONS);
            // 50% 句首也加
            if (ThreadLocalRandom.current().nextDouble() < 0.5) {
                m.sentenceStart = pick(SENTENCE_START_HESITATIONS);
            }
            // 30% 弱化词
            if (ThreadLocalRandom.current().nextDouble() < 0.3) {
                m.weakening = pick(WEAKENING_PHRASES);
            }
        }

        return m;
    }

    private <T> T pick(List<T> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    public record HesitationContext(
            double noticeLevel,
            Double confidence,
            String personaName
    ) {}

    public static class HesitationMarkers {
        public String sentenceStart;
        public String sentenceEnd;
        public String weakening;

        public void applyTo(StringBuilder sb, boolean atStart) {
            if (atStart && sentenceStart != null) {
                sb.insert(0, sentenceStart);
            }
            if (!atStart && sentenceEnd != null) {
                sb.append(sentenceEnd);
            }
        }
    }
}