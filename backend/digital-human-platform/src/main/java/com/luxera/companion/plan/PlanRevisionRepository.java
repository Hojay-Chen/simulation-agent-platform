package com.luxera.companion.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanRevisionRepository extends JpaRepository<PlanRevision, String> {
    List<PlanRevision> findByPlanIdOrderByOccurredAtAsc(String planId);
}
