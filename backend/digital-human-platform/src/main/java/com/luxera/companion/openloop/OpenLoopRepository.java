package com.luxera.companion.openloop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpenLoopRepository extends JpaRepository<OpenLoop, String> {
    List<OpenLoop> findByCompanionIdAndStatusInOrderByImportanceDesc(String companionId, List<String> statuses);
    List<OpenLoop> findByStatusInAndExpectedResolutionAtBefore(List<String> statuses, java.time.LocalDateTime now);
}
