package com.luxera.companion.life;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompanionLifeRepository extends JpaRepository<CompanionLife, String> {
    Optional<CompanionLife> findByCompanionId(String companionId);
    List<CompanionLife> findByLifeDateBefore(LocalDate date);
}
