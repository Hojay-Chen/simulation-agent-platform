package com.luxera.companion.relationship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedExperienceRepository extends JpaRepository<SharedExperience, String> {
    List<SharedExperience> findByRelationshipIdOrderByOccurredAtDesc(String relationshipId);
}
