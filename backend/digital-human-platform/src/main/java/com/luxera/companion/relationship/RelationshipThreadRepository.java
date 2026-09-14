package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RelationshipThreadRepository extends JpaRepository<RelationshipThread, String> {
    List<RelationshipThread> findByRelationshipIdOrderByLastActivityAtDesc(String relationshipId);
    List<RelationshipThread> findByRelationshipIdAndStatusOrderByLastActivityAtDesc(String relationshipId, String status);
    Optional<RelationshipThread> findTopByRelationshipIdAndTopic(String relationshipId, String topic);
}
