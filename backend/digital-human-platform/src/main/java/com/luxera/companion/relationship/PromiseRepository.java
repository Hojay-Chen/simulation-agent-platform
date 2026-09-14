package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromiseRepository extends JpaRepository<Promise, String> {
    List<Promise> findByRelationshipIdAndStatusOrderByCreatedAtDesc(String relationshipId, String status);
    List<Promise> findByRelationshipIdOrderByCreatedAtDesc(String relationshipId);
}
