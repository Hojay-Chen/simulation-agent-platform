package com.luxera.companion.selfmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelfModelRepository extends JpaRepository<SelfModel, String> {
    Optional<SelfModel> findByCompanionId(String companionId);
}
