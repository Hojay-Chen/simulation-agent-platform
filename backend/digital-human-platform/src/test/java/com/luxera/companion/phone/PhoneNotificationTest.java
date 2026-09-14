package com.luxera.companion.phone;

import com.luxera.companion.attention.AttentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §15-§17 Phone Notification 测试:
 * - 消息到达 → 通知创建(delivered)
 * - 手机可用 + 有声 → 可推进 heard
 * - 看到 → opened → read 逐步推进
 * - markRead 强制标记已读
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.phone-tick-cron=0 0 0 1 1 *",
        "app.scheduler.sleep-tick-cron=0 0 0 1 1 *"
})
class PhoneNotificationTest {

    @Autowired
    PhoneNotificationService service;
    @Autowired
    PhoneNotificationRepository repo;

    private String companionId;
    private String conversationId;
    private String messageId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        conversationId = UUID.randomUUID().toString();
        messageId = UUID.randomUUID().toString();
    }

    @Test
    void createNotificationIsDelivered() {
        PhoneNotification n = service.create(companionId, conversationId, messageId,
                "在吗?", true, true, LocalDateTime.of(2026, 8, 16, 20, 0));
        assertTrue(n.isDelivered());
        assertTrue(n.isSound());
        assertTrue(n.isVibration());
        assertFalse(n.isHeard());
        assertFalse(n.isRead());
    }

    @Test
    void advanceWithHighAttentionReachesRead() {
        PhoneNotification n = service.create(companionId, conversationId, messageId,
                "我今天好难过", true, true, LocalDateTime.of(2026, 8, 16, 20, 0));
        // 高注意力(休闲 + 手机在手): notice 0.9, inspect 0.9
        AttentionService.Attention high = new AttentionService.Attention(0.2, 0.2, 0.9, 0.9, 0);
        var advanced = service.advance(n, high, true, LocalDateTime.of(2026, 8, 16, 20, 1));
        assertTrue(advanced.isHeard(), "手机可用+有声+高注意力 → 听到");
        assertTrue(advanced.isSeen(), "听到后高注意力 → 看到");
        assertTrue(advanced.isOpened(), "看到后高注意力 → 打开");
        assertTrue(advanced.isRead(), "打开 → 阅读");
    }

    @Test
    void lowPhoneAvailabilityDoesNotHear() {
        PhoneNotification n = service.create(companionId, conversationId, messageId,
                "你好", true, true, LocalDateTime.of(2026, 8, 16, 20, 0));
        // 洗澡场景: 手机不可用 → 完全没听到
        AttentionService.Attention high = new AttentionService.Attention(0.9, 0.9, 0.9, 0.9, 0);
        var advanced = service.advance(n, high, false, LocalDateTime.of(2026, 8, 16, 20, 1));
        assertFalse(advanced.isHeard(), "手机不可用(洗澡) → 没听到");
        assertFalse(advanced.isSeen());
        assertFalse(advanced.isRead());
    }

    @Test
    void silentNotificationNotHeard() {
        PhoneNotification n = service.create(companionId, conversationId, messageId,
                "在吗", false, false, LocalDateTime.of(2026, 8, 16, 20, 0));
        // 静音 + 无震动 → 即使手机在身边也听不到
        AttentionService.Attention high = new AttentionService.Attention(0.2, 0.2, 0.9, 0.9, 0);
        var advanced = service.advance(n, high, true, LocalDateTime.of(2026, 8, 16, 20, 1));
        assertFalse(advanced.isHeard(), "静音 → 没听到");
    }

    @Test
    void markReadForcesAllStages() {
        service.create(companionId, conversationId, messageId, "很重要的事", true, true,
                LocalDateTime.of(2026, 8, 16, 20, 0));
        service.markRead(messageId, LocalDateTime.of(2026, 8, 16, 20, 30));
        var n = repo.findByMessageId(messageId).get(0);
        assertTrue(n.isHeard() && n.isSeen() && n.isOpened() && n.isRead(),
                "Agent 明确阅读 → 通知应被推进到 read");
    }
}
