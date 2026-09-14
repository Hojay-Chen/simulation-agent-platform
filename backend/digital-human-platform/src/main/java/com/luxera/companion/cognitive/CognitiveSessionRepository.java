package com.luxera.companion.cognitive;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CognitiveSessionRepository extends JpaRepository<CognitiveSession, String> {
    Optional<CognitiveSession> findByCompanionId(String companionId);

    /** 乐观锁条件更新: 仅当版本匹配时提交 */
    @Modifying
    @Query("update CognitiveSession c set c.currentFocus = :focus, c.currentThought = :thought, "
            + "c.currentIntention = :intention, c.activePlans = :plans, c.emotionSummary = :emotion, "
            + "c.stateVersion = :newVersion where c.companionId = :companionId and c.stateVersion = :expectedVersion")
    int updateIfVersion(@Param("companionId") String companionId,
                        @Param("focus") String focus, @Param("thought") String thought,
                        @Param("intention") String intention, @Param("plans") String plans,
                        @Param("emotion") String emotion,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("newVersion") long newVersion);
}
