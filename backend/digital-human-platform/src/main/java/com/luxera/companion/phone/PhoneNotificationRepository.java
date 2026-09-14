package com.luxera.companion.phone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhoneNotificationRepository extends JpaRepository<PhoneNotification, String> {

    List<PhoneNotification> findByCompanionIdOrderByCreatedAtDesc(String companionId);

    List<PhoneNotification> findByMessageId(String messageId);

    /** 未读通知数(行为引擎判断"要不要看手机") */
    long countByCompanionIdAndReadFalse(String companionId);
}
