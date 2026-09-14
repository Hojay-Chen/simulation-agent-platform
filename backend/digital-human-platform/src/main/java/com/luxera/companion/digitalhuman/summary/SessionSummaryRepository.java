package com.luxera.companion.digitalhuman.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, String> {
    Optional<SessionSummary> findByConversationId(String conversationId);
}
