package com.luxera.companion.usermodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserHypothesisRepository extends JpaRepository<UserHypothesis, String> {
    Optional<UserHypothesis> findTopByUserIdAndCompanionIdAndHypothesisAndStatus(
            String userId, String companionId, String hypothesis, String status);
    List<UserHypothesis> findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(String userId, String companionId, String status);
}
