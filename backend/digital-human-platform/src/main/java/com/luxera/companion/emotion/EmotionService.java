package com.luxera.companion.emotion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmotionService {

    private final EmotionalEpisodeRepository repo;

    public EmotionService(EmotionalEpisodeRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public EmotionalEpisode record(String companionId, String trigger, String emotion, double intensity,
                                   String cause, String thought, String behaviorTendency,
                                   String sourceType, String sourceId) {
        EmotionalEpisode e = new EmotionalEpisode();
        e.setCompanionId(companionId);
        e.setTrigger(trigger);
        e.setEmotion(emotion);
        e.setIntensity(clamp(intensity));
        e.setCause(cause);
        e.setThought(thought);
        e.setBehaviorTendency(behaviorTendency);
        e.setStartedAt(LocalDateTime.now());
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        return repo.save(e);
    }

    @Transactional
    public void resolve(String episodeId) {
        repo.findById(episodeId).ifPresent(e -> {
            e.setResolved(true);
            e.setEndedAt(LocalDateTime.now());
            repo.save(e);
        });
    }

    @Transactional
    public void resolveAll(String companionId) {
        for (EmotionalEpisode e : repo.findByCompanionIdAndResolvedFalseOrderByStartedAtDesc(companionId)) {
            e.setResolved(true);
            e.setEndedAt(LocalDateTime.now());
            repo.save(e);
        }
    }

    @Transactional(readOnly = true)
    public List<EmotionalEpisode> activeEpisodes(String companionId) {
        return repo.findByCompanionIdAndResolvedFalseOrderByStartedAtDesc(companionId);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
