package com.luxera.companion.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryEntityRepository extends JpaRepository<MemoryEntity, String> {
    Optional<MemoryEntity> findByUserIdAndCompanionIdAndName(String userId, String companionId, String name);
    List<MemoryEntity> findByUserIdAndCompanionIdAndStatusOrderBySalienceDesc(String userId, String companionId, String status);
}
