package com.luxera.companion.digitalhuman.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, String> {
    Optional<SessionSummary> findByConversationId(String conversationId);

    /**
     * Agent 被删除时的清理 —— 见 {@code CompanionService#delete}。
     *
     * <p>这张表按 {@code conversationId} 存, 而那个 id 是 chat 平台分配的: 本仓调用
     * {@code ensureConversation} 时拿到过、但没有留底。所以只能按 chat 在 purge 响应里
     * 还回来的那份清单删 —— 这也正是 {@code ChatWorldPort#purgePeer} 必须返回 id 的原因。
     */
    long deleteByConversationIdIn(Collection<String> conversationIds);
}
