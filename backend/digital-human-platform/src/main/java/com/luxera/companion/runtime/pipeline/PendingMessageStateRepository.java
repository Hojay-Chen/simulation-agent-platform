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

    /**
     * Agent 被删除时的清理 —— 见 {@code CompanionService#delete}。
     *
     * <p>这条尤其不能省: {@code PendingMessageReevaluationJob} 是**定时扫表**的
     * ({@code findByStatusAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc}), 它不认识
     * "这个 Agent 已经没了"。留下的待复审行会被反复捞起来, 每次都要去 chat 拉一个已经
     * 不存在的会话 —— 变成一条永远失败、永远不会被清掉的定时任务噪声。
     */
    long deleteByCompanionId(String companionId);
}
