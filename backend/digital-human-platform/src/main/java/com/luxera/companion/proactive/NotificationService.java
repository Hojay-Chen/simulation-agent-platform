package com.luxera.companion.proactive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Notification notify(String userId, String companionId, String type, String title, String content) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setCompanionId(companionId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        return repo.save(n);
    }

    @Transactional(readOnly = true)
    public List<Notification> list(String userId, String companionId) {
        return repo.findTop50ByUserIdAndCompanionIdOrderByCreatedAtDesc(userId, companionId);
    }

    @Transactional
    public void markRead(String userId, String notificationId) {
        repo.findById(notificationId).filter(n -> n.getUserId().equals(userId)).ifPresent(n -> {
            n.setRead(true);
            repo.save(n);
        });
    }

    @Transactional
    public void markAllRead(String userId, String companionId) {
        for (Notification n : repo.findTop50ByUserIdAndCompanionIdOrderByCreatedAtDesc(userId, companionId)) {
            if (!n.isRead()) {
                n.setRead(true);
                repo.save(n);
            }
        }
    }

    @Transactional(readOnly = true)
    public long unreadCount(String userId, String companionId) {
        return repo.countByUserIdAndCompanionIdAndReadFalse(userId, companionId);
    }
}
