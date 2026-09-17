package com.luxera.companion.phone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhoneNotificationRepository extends JpaRepository<PhoneNotification, String> {

    List<PhoneNotification> findByCompanionIdOrderByCreatedAtDesc(String companionId);

    List<PhoneNotification> findByMessageId(String messageId);

    /** 未读通知数(行为引擎判断"要不要看手机") */
    long countByCompanionIdAndReadFalse(String companionId);

    /**
     * Agent 被删除时的清理 —— 见 {@code CompanionService#delete}。
     *
     * <p>必须清的理由不是"整洁", 是**这是一条活队列**: 通知指向的会话在 chat 侧被硬删之后,
     * 任何一条后续读取都会拿到空世界。删掉的 Agent 不该继续占着一条指向空气的收件箱。
     */
    long deleteByCompanionId(String companionId);
}
