package com.luxera.companion.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentTraceRepository extends JpaRepository<AgentTrace, String> {

    List<AgentTrace> findByCompanionIdAndTraceIdOrderByCreatedAtAsc(String companionId, String traceId);

    List<AgentTrace> findTop50ByCompanionIdOrderByCreatedAtDesc(String companionId);

    List<AgentTrace> findByCompanionIdAndCreatedAtAfterOrderByCreatedAtAsc(String companionId, LocalDateTime after);
}
