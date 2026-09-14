package com.luxera.companion.sleep;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SleepSessionRepository extends JpaRepository<SleepSession, String> {
    List<SleepSession> findByCompanionIdOrderByStartTimeDesc(String companionId);
    List<SleepSession> findByCompanionIdAndStartTimeAfterOrderByStartTimeAsc(String companionId, LocalDateTime after);
    long countByCompanionId(String companionId);
}
