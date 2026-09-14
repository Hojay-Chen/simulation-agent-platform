package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RelationshipRepository extends JpaRepository<Relationship, String> {
    Optional<Relationship> findByUserIdAndCompanionId(String userId, String companionId);
}
