package com.luxera.companion.digitalhuman.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * processed_event 表访问(幂等判定)。
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventRecord, String> {

    boolean existsByEventId(String eventId);
}
