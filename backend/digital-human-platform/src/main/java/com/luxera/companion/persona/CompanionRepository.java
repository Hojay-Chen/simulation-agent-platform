package com.luxera.companion.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionRepository extends JpaRepository<Companion, String> {
    List<Companion> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(String userId);
    long countByUserIdAndDeletedAtIsNull(String userId);
}
