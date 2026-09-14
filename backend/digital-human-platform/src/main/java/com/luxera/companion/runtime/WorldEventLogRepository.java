package com.luxera.companion.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface WorldEventLogRepository extends JpaRepository<WorldEventLog, String> {

    List<WorldEventLog> findTop200ByCompanionIdOrderByOccurredAtDesc(String companionId);

    List<WorldEventLog> findByCompanionIdAndOccurredAtAfterOrderByOccurredAtAsc(String companionId, LocalDateTime after);
}
