package com.luxera.companion.life;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LifeActivityRepository extends JpaRepository<LifeActivity, String> {
    List<LifeActivity> findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
            String companionId, LocalDateTime from, LocalDateTime to);
    List<LifeActivity> findTop30ByCompanionIdOrderByPlannedStartDesc(String companionId);
    long countByCompanionIdAndStatus(String companionId, String status);
}
