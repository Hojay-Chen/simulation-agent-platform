package com.luxera.companion.thought;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ThoughtRepository extends JpaRepository<Thought, String> {
    List<Thought> findByCompanionIdAndStatusInOrderByStrengthDesc(String companionId, List<String> statuses);
    List<Thought> findByStatusInAndExpiresAtBefore(List<String> statuses, java.time.LocalDateTime now);
    List<Thought> findByCompanionIdAndStatusOrderByCreatedAtDesc(String companionId, String status);
    long countByCompanionIdAndStatus(String companionId, String status);
}
