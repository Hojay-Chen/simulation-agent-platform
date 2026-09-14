package com.luxera.companion.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmCallRepository extends JpaRepository<LlmCallRecord, Long> {

    List<LlmCallRecord> findTop20ByCompanionIdOrderByIdDesc(String companionId);

    /** 上一条同 agent 调用(用于 prefix cache 命中估计) */
    Optional<LlmCallRecord> findFirstByCompanionIdOrderByIdDesc(String companionId);

    @Query("select count(l) from LlmCallRecord l where l.companionId = :companionId and l.cacheEstimated = true")
    long countCacheHit(@Param("companionId") String companionId);

    long countByCompanionId(String companionId);

    @Query("select coalesce(avg(l.latencyMs), 0) from LlmCallRecord l where l.companionId = :companionId")
    double avgLatency(@Param("companionId") String companionId);

    @Query("select coalesce(sum(l.promptTokens), 0) from LlmCallRecord l where l.companionId = :companionId")
    long sumPromptTokens(@Param("companionId") String companionId);
}
