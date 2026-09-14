package com.luxera.companion.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemoryRepository extends JpaRepository<Memory, String> {

    List<Memory> findTop100ByUserIdAndCompanionIdAndStatusOrderByCreatedAtDesc(String userId, String companionId, String status);

    Optional<Memory> findTopByUserIdAndCompanionIdAndContentAndStatusOrderByCreatedAtDesc(
            String userId, String companionId, String content, String status);

    @Query("""
            select m from Memory m
            where m.userId = :userId and m.companionId = :companionId and m.status = 'active'
              and (m.type = :mtype or :mtype is null)
            order by m.createdAt desc
            """)
    List<Memory> search(@Param("userId") String userId,
                        @Param("companionId") String companionId,
                        @Param("mtype") String type);

    @Query("""
            select m from Memory m
            where m.userId = :userId and m.companionId = :companionId and m.status = 'active'
              and (lower(m.content) like lower(concat('%', :q, '%')) or lower(coalesce(m.summary,'')) like lower(concat('%', :q, '%')))
            order by m.createdAt desc
            """)
    List<Memory> searchByKeyword(@Param("userId") String userId,
                                 @Param("companionId") String companionId,
                                 @Param("q") String q);

    long countByUserIdAndCompanionIdAndStatus(String userId, String companionId, String status);

    List<Memory> findByUserIdAndCompanionIdAndStatus(String userId, String companionId, String status);

    @Query("select m from Memory m where m.status = 'active' and m.occurredAt < :cutoff")
    List<Memory> findStaleActive(@Param("cutoff") LocalDateTime cutoff);
}
