package com.luxera.companion.runtime;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.api.ConversationView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V9 Reality 一致性: 她睡着时收到的消息, 醒来后补处理。
 *
 * 真人睡觉时手机静音, 消息不会被看到; 醒来拿起手机 → 看到全部夜间消息 → 已读/回复。
 * SleepTickJob 检测到"刚醒"(lastWakeAt 近 3 分钟)时调用本服务,
 * 把该 agent 近 24h 未读(DELIVERED)的用户消息按会话分批提交给 AgentRuntime 补处理。
 */
@Slf4j
@Service
public class WakeupCatchUpService {

    /** 刚醒判定窗口(分钟) */
    private static final long JUST_WOKE_WINDOW_MINUTES = 5;
    /** 补处理窗口(小时): 只补近 24h 的消息 */
    private static final int CATCHUP_WINDOW_HOURS = 24;

    private final ChatWorldPort chatWorld;
    private final AgentRuntime agentRuntime;

    public WakeupCatchUpService(ChatWorldPort chatWorld,
                                AgentRuntime agentRuntime) {
        this.chatWorld = chatWorld;
        this.agentRuntime = agentRuntime;
    }

    /** 是否刚醒(供 SleepTickJob 判断) */
    public boolean justWoke(LocalDateTime lastWakeAt, LocalDateTime now) {
        if (lastWakeAt == null) return false;
        return !lastWakeAt.isBefore(now.minusMinutes(JUST_WOKE_WINDOW_MINUTES));
    }

    /** 补处理: 该 agent 近 24h 未读用户消息, 按会话分批提交 */
    @Transactional
    public int catchUp(String companionId, String userId, LocalDateTime now) {
        LocalDateTime since = now.minusHours(CATCHUP_WINDOW_HOURS);
        List<MessageView> unread = new ArrayList<>();
        for (MessageView m : chatWorld.userMessagesSince(companionId, since)) {
            if (m.getDeliveryStatus() == null
                    || "DELIVERED".equals(m.getDeliveryStatus())
                    || "NOTIFIED".equals(m.getDeliveryStatus())) {
                unread.add(m);
            }
        }
        if (unread.isEmpty()) return 0;

        // 按会话分组
        Map<String, List<MessageView>> byConv = new LinkedHashMap<>();
        for (MessageView m : unread) {
            byConv.computeIfAbsent(m.getConversationId(), k -> new ArrayList<>()).add(m);
        }
        int total = 0;
        for (Map.Entry<String, List<MessageView>> e : byConv.entrySet()) {
            ConversationView conv = chatWorld.conversation(e.getKey()).orElse(null);
            if (conv == null) continue;
            try {
                // catchup 阶段: 与实时送达(live)是两次独立处理时机, 不互相幂等短路
                agentRuntime.submitWithPhase(userId, companionId, e.getKey(), e.getValue(), "catchup");
                total += e.getValue().size();
            } catch (Exception ex) {
                log.debug("[WakeupCatchUp] 补处理失败 conv={}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (total > 0) {
            log.info("[WakeupCatchUp] {} 醒来, 补处理 {} 条夜间消息", companionId, total);
        }
        return total;
    }
}
