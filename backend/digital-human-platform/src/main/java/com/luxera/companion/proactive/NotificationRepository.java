package com.luxera.companion.proactive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findTop50ByUserIdAndCompanionIdOrderByCreatedAtDesc(String userId, String companionId);
    List<Notification> findTop10ByUserIdAndCompanionIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, String companionId, LocalDateTime after);
    long countByUserIdAndCompanionIdAndCreatedAtAfter(String userId, String companionId, LocalDateTime after);
    long countByUserIdAndCompanionIdAndReadFalse(String userId, String companionId);
    Notification findTopByCompanionIdAndTypeOrderByCreatedAtDesc(String companionId, String type);
}
