package com.luxera.companion.emotion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/** 情绪自然消退(设计文档 §41): 数小时~数周后结束/清理 */
@Component
public class EmotionDecayService {

    private final EmotionalEpisodeRepository repo;

    public EmotionDecayService(EmotionalEpisodeRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void decay() {
        LocalDateTime now = LocalDateTime.now();
        for (EmotionalEpisode e : repo.findAll()) {
            if (!e.isResolved()) {
                // 超过 12 小时未结束的情绪 → 结束
                long hours = Duration.between(e.getStartedAt(), now).toHours();
                if (hours >= 12) {
                    e.setResolved(true);
                    e.setEndedAt(now);
                    repo.save(e);
                }
            } else if (e.getEndedAt() != null && e.getEndedAt().isBefore(now.minusDays(14))) {
                // 已结束且超过 14 天 → 清理
                repo.delete(e);
            }
        }
    }
}
