package com.luxera.companion.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox_event 表访问。
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    boolean existsByEventKey(String eventKey);

    /** 到期待发事件(按创建时间升序, 先入先出) */
    List<OutboxEvent> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, LocalDateTime now);
}
