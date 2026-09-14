package com.luxera.companion.behavior;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BehaviorPatternRepository extends JpaRepository<BehaviorPattern, String> {
    List<BehaviorPattern> findByCompanionIdOrderByStrengthDesc(String companionId);
    Optional<BehaviorPattern> findByCompanionIdAndPattern(String companionId, String pattern);
}
