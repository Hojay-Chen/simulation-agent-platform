package com.luxera.companion.intention;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface IntentionRepository extends JpaRepository<Intention, String> {
    List<Intention> findByCompanionIdAndStatusOrderByCreatedAtDesc(String companionId, String status);
    List<Intention> findByCompanionIdOrderByCreatedAtDesc(String companionId);
    List<Intention> findByStatusAndExpiryTimeBefore(String status, LocalDateTime now);
    List<Intention> findByCompanionIdAndStatusIn(String companionId, List<String> statuses);
}
