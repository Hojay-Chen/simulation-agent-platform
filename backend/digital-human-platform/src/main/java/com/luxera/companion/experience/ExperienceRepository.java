package com.luxera.companion.experience;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceRepository extends JpaRepository<Experience, String> {
    List<Experience> findByCompanionIdOrderByOccurredAtDesc(String companionId);
    List<Experience> findByCompanionIdAndStatusOrderByOccurredAtDesc(String companionId, String status);
    List<Experience> findByStatusAndCreatedAtBefore(String status, java.time.LocalDateTime before);
}
