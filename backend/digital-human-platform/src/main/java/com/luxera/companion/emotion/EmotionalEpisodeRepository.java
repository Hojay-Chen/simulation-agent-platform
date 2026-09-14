package com.luxera.companion.emotion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmotionalEpisodeRepository extends JpaRepository<EmotionalEpisode, String> {
    List<EmotionalEpisode> findByCompanionIdAndResolvedFalseOrderByStartedAtDesc(String companionId);
    List<EmotionalEpisode> findByCompanionIdAndResolvedTrueAndEndedAtBefore(String companionId, java.time.LocalDateTime before);
}
