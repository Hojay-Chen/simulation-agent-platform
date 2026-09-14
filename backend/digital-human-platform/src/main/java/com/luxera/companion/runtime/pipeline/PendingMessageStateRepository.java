package com.luxera.companion.runtime.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PendingMessageStateRepository extends JpaRepository<PendingMessageState, String> {

    List<PendingMessageState> findByStatusAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(String status, LocalDateTime now);

    List<PendingMessageState> findByCompanionIdAndStatus(String companionId, String status);

    Optional<PendingMessageState> findByMessageId(String messageId);

    Optional<PendingMessageState> findByMessageIdAndStatus(String messageId, String status);
}
