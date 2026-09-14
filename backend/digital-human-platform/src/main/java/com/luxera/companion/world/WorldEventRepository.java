package com.luxera.companion.world;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WorldEventRepository extends JpaRepository<WorldEvent, Long> {
    List<WorldEvent> findByAgentIdOrderByIdDesc(String agentId, org.springframework.data.domain.Pageable pageable);

    @Query("select w from WorldEvent w where w.agentId = :agentId and w.processedAt is null order by w.id asc")
    List<WorldEvent> findUnprocessed(@Param("agentId") String agentId);

    long countByAgentIdAndOccurredAtAfter(String agentId, LocalDateTime since);
}
