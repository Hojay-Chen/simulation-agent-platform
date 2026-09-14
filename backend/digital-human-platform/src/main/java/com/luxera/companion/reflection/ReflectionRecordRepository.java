package com.luxera.companion.reflection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReflectionRecordRepository extends JpaRepository<ReflectionRecord, String> {
    List<ReflectionRecord> findByCompanionIdOrderByCreatedAtDesc(String companionId);
    List<ReflectionRecord> findByCompanionIdAndTypeAndPeriod(String companionId, String type, String period);
}
