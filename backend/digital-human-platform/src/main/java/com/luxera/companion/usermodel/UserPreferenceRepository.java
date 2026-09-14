package com.luxera.companion.usermodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, String> {
    Optional<UserPreference> findTopByUserIdAndCompanionIdAndCategoryAndPreferenceAndStatus(
            String userId, String companionId, String category, String preference, String status);
    List<UserPreference> findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(String userId, String companionId, String status);
    List<UserPreference> findByUserIdAndCompanionIdAndStatus(String userId, String companionId, String status);
}
