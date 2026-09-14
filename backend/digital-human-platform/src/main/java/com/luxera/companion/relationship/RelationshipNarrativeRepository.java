package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RelationshipNarrativeRepository extends JpaRepository<RelationshipNarrative, String> {
    Optional<RelationshipNarrative> findByRelationshipId(String relationshipId);
}
