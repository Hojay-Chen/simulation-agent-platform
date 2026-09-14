package com.luxera.companion.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LifeEventRepository extends JpaRepository<LifeEvent, String> {
    List<LifeEvent> findByCompanionIdOrderByStartTimeAsc(String companionId);
    void deleteByCompanionId(String companionId);
}
