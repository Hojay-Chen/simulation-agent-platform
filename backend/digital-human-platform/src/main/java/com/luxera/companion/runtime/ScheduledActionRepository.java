package com.luxera.companion.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledActionRepository extends JpaRepository<ScheduledAction, String> {

    List<ScheduledAction> findByStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(String status, LocalDateTime now);

    List<ScheduledAction> findByCompanionIdAndStatus(String companionId, String status);

    List<ScheduledAction> findByCompanionIdAndStatusAndActionType(String companionId, String status, String actionType);
}
