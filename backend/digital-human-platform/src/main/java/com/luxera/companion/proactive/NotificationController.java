package com.luxera.companion.proactive;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companions/{companionId}/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notificationService,
                                  CompanionService companionService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Notification> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return notificationService.list(userId, companionId);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return Map.of("count", notificationService.unreadCount(userId, companionId));
    }

    @PutMapping("/{notificationId}/read")
    public void markRead(@PathVariable String companionId, @PathVariable String notificationId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        notificationService.markRead(userId, notificationId);
    }

    @PutMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        notificationService.markAllRead(userId, companionId);
    }
}
