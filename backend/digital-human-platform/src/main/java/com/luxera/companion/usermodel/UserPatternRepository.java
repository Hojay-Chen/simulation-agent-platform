package com.luxera.companion.usermodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPatternRepository extends JpaRepository<UserPattern, String> {
    Optional<UserPattern> findTopByUserIdAndCompanionIdAndPatternAndStatus(
            String userId, String companionId, String pattern, String status);
    List<UserPattern> findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(String userId, String companionId, String status);
}
