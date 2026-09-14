package com.luxera.companion.phone;

import com.luxera.companion.attention.AttentionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * §16/§31 手机通知生命周期服务。
 * 用户消息 → 通知产生 → 递进 heard/seen/opened/read, 每步都不必然。
 *
 * 真实场景:
 * - 洗澡: phoneAvailability=0 → 通知完全没被听到(heard=false)
 * - 客厅做饭: 听到但没拿手机(heard=true, seen=false)
 * - 刷手机: 看到通知(seen=true) → 打开聊天(opened=true) → 阅读(read)
 */
@Service
public class PhoneNotificationService {

    private final PhoneNotificationRepository repo;

    public PhoneNotificationService(PhoneNotificationRepository repo) {
        this.repo = repo;
    }

    /** 消息到达 → 创建通知(delivered), 附带手机状态(是否响/震) */
    @Transactional
    public PhoneNotification create(String companionId, String conversationId, String messageId,
                                    String preview, boolean sound, boolean vibration, LocalDateTime now) {
        PhoneNotification n = new PhoneNotification();
        n.setCompanionId(companionId);
        n.setConversationId(conversationId);
        n.setMessageId(messageId);
        n.setPreview(preview);
        n.setSound(sound);
        n.setVibration(vibration);
        n.setDelivered(true);
        n.setDeliveredAt(now);
        return repo.save(n);
    }

    /** 推进通知状态: 返回是否已到"阅读"。依据 AttentionService 判定。 */
    @Transactional
    public PhoneNotification advance(PhoneNotification n, AttentionService.Attention attention,
                                     boolean phoneAvailable, LocalDateTime now) {
        // 手机不在身边/静音 → 没听到
        if (!n.isHeard() && phoneAvailable && (n.isSound() || n.isVibration())) {
            if (attention.noticeProbability() >= 0.3) {
                n.setHeard(true);
                n.setHeardAt(now);
            }
        }
        // 听到后可能看到(瞥一眼通知栏)
        if (n.isHeard() && !n.isSeen() && attention.inspectProbability() >= 0.4) {
            n.setSeen(true);
            n.setSeenAt(now);
        }
        // 看到后可能打开聊天
        if (n.isSeen() && !n.isOpened() && attention.inspectProbability() >= 0.55) {
            n.setOpened(true);
            n.setOpenedAt(now);
        }
        // 打开后可能阅读
        if (n.isOpened() && !n.isRead()) {
            n.setRead(true);
            n.setReadAt(now);
        }
        return repo.save(n);
    }

    /** 直接标记已读(Agent 明确阅读了消息) */
    @Transactional
    public void markRead(String messageId, LocalDateTime now) {
        for (PhoneNotification n : repo.findByMessageId(messageId)) {
            n.setRead(true);
            n.setReadAt(now);
            if (!n.isOpened()) {
                n.setOpened(true);
                n.setOpenedAt(now);
            }
            if (!n.isSeen()) {
                n.setSeen(true);
                n.setSeenAt(now);
            }
            if (!n.isHeard()) {
                n.setHeard(true);
                n.setHeardAt(now);
            }
            repo.save(n);
        }
    }
}
