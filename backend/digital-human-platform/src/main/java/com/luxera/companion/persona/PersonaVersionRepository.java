package com.luxera.companion.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonaVersionRepository extends JpaRepository<PersonaVersion, String> {
    List<PersonaVersion> findByCompanionIdOrderByVersionAsc(String companionId);
    Optional<PersonaVersion> findByCompanionIdAndActiveTrue(String companionId);
    Optional<PersonaVersion> findTopByCompanionIdOrderByVersionDesc(String companionId);
}
