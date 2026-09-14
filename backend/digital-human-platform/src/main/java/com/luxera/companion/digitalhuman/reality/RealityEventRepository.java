package com.luxera.companion.digitalhuman.reality;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * timeline_event 表访问。只读查询 —— 写入只能经 RealityLedger.append(追加)。
 */
@Repository
public interface RealityEventRepository extends JpaRepository<RealityEventRecord, String> {

    List<RealityEventRecord> findByPersonIdOrderByOccurredAtDesc(String personId, Pageable pageable);

    List<RealityEventRecord> findByPersonIdOrderByOccurredAtDesc(String personId);

    List<RealityEventRecord> findByPersonIdAndOccurredAtAfterOrderByOccurredAtAsc(
            String personId, LocalDateTime after);

    @Query("select r from RealityEventRecord r where r.personId = :personId and r.eventType = :type order by r.occurredAt desc")
    List<RealityEventRecord> findByPersonIdAndType(@Param("personId") String personId, @Param("type") String type);

    long countByPersonId(String personId);
}
