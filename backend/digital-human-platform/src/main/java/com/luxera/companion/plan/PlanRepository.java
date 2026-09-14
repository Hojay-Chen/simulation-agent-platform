package com.luxera.companion.plan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, String> {
    List<Plan> findByCompanionIdAndStatusOrderByExpectedTimeAsc(String companionId, String status);

    @Query("select p from Plan p where p.companionId = :companionId "
            + "and p.status in ('PLANNED','ACTIVE') order by p.expectedTime asc")
    List<Plan> findActive(@Param("companionId") String companionId);

    List<Plan> findByCompanionIdAndStatusAndExpectedTimeBefore(
            String companionId, String status, LocalDateTime before);

    Optional<Plan> findFirstByCompanionIdAndStatusOrderByExpectedTimeAsc(String companionId, String status);
}
