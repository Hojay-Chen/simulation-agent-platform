package com.luxera.companion.usermodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFactRepository extends JpaRepository<UserFact, String> {
    Optional<UserFact> findTopByUserIdAndCompanionIdAndPredicateAndObjectAndStatus(
            String userId, String companionId, String predicate, String object, String status);
    List<UserFact> findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(String userId, String companionId, String status);
    List<UserFact> findByUserIdAndCompanionIdAndStatus(String userId, String companionId, String status);
}
