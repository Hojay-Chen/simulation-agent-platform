package com.luxera.companion.agent;

import com.luxera.companion.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作记忆(设计文档 24 节): 保存当前会话状态(分钟/小时级), Redis 可替换。
 * - 最近消息(short-term)
 * - 当前话题 / 当前意图 / 当前情绪 / 当前实体
 * 超过 TTL 自动失效,切换会话即重置。
 */
@Component
public class WorkingMemory {

    private static final int MAX_RECENT = 12;

    private final AppProperties props;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public WorkingMemory(AppProperties props) {
        this.props = props;
    }

    /** 记录一条消息,并更新当前话题/意图/情绪 */
    public void record(String companionId, String conversationId, RecentLine line,
                       PerceptionEngine.Perception perception) {
        Entry e = store.computeIfAbsent(key(companionId, conversationId), k -> new Entry());
        e.recent.addLast(line);
        while (e.recent.size() > MAX_RECENT) {
            e.recent.removeFirst();
        }
        if (perception != null) {
            if (perception.intent() != null && !perception.intent().isBlank()) e.currentIntent = perception.intent();
            if (perception.emotion() != null && !perception.emotion().isBlank()) e.currentEmotion = perception.emotion();
            if (perception.topic() != null && !perception.topic().isBlank()) e.currentTopic = perception.topic();
        }
        e.lastUpdated = LocalDateTime.now();
    }

    /** 由 LLM 感知精炼写入当前实体(人名/地名/话题对象) */
    public void setEntities(String companionId, String conversationId, List<String> entities) {
        Entry e = store.computeIfAbsent(key(companionId, conversationId), k -> new Entry());
        e.currentEntities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
        e.lastUpdated = LocalDateTime.now();
    }

    /** 读取当前工作记忆,TTL 过期或不存在返回 null */
    public WorkingContext get(String companionId, String conversationId) {
        Entry e = store.get(key(companionId, conversationId));
        if (e == null) return null;
        if (e.lastUpdated == null
                || e.lastUpdated.isBefore(LocalDateTime.now().minusMinutes(props.getAgent().getWorkingMemoryTtlMinutes()))) {
            store.remove(key(companionId, conversationId));
            return null;
        }
        return new WorkingContext(
                List.copyOf(e.recent),
                e.currentTopic, e.currentIntent, e.currentEmotion,
                List.copyOf(e.currentEntities),
                e.lastUpdated);
    }

    public void reset(String companionId, String conversationId) {
        store.remove(key(companionId, conversationId));
    }

    private static String key(String companionId, String conversationId) {
        return companionId + ":" + conversationId;
    }

    /** 会话内最近一条消息的轻量快照 */
    public record RecentLine(String sender, String content, LocalDateTime time) {}

    /** 工作记忆快照(注入 Prompt 用) */
    public record WorkingContext(
            List<RecentLine> recent,
            String currentTopic,
            String currentIntent,
            String currentEmotion,
            List<String> currentEntities,
            LocalDateTime lastUpdated) {

        public boolean isEmpty() {
            return currentTopic == null && currentIntent == null && currentEmotion == null
                    && (currentEntities == null || currentEntities.isEmpty());
        }
    }

    private static class Entry {
        final Deque<RecentLine> recent = new ArrayDeque<>();
        String currentTopic = "";
        String currentIntent = "";
        String currentEmotion = "";
        List<String> currentEntities = new ArrayList<>();
        LocalDateTime lastUpdated;
    }
}
