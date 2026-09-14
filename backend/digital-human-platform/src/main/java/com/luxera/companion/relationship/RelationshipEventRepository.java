package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationshipEventRepository extends JpaRepository<RelationshipEvent, String> {
    List<RelationshipEvent> findByRelationshipIdOrderByOccurredAtAsc(String relationshipId);
    long countByRelationshipIdAndType(String relationshipId, String type);
}
