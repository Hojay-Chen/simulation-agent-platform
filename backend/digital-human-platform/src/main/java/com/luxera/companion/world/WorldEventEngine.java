package com.luxera.companion.world;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §四十三~§四十七 WorldEventEngine: 数字人世界的事件基础设施。
 *
 * 所有"世界上发生的事"统一经过这里: 时间推进、生活活动、身体状态、
 * 社会关系、记忆激活、意图激活、外界事件、通信事件。
 * publish 落库(独立事务, 失败不影响业务), 供 BehaviorEngine 消费。
 */
@Slf4j
@Service
public class WorldEventEngine {

    public static final String TYPE_TIME_TICK = "TIME_TICK";
    public static final String TYPE_MESSAGE_CREATED = "MESSAGE_CREATED";
    public static final String TYPE_ACTIVITY_STARTED = "ACTIVITY_STARTED";
    public static final String TYPE_ACTIVITY_FINISHED = "ACTIVITY_FINISHED";
    public static final String TYPE_SLEEP_STATE_CHANGED = "SLEEP_STATE_CHANGED";
    public static final String TYPE_SOCIAL_SILENCE = "SOCIAL_SILENCE";
    public static final String TYPE_MEMORY_ACTIVATED = "MEMORY_ACTIVATED";
    public static final String TYPE_INTENTION_ACTIVATED = "INTENTION_ACTIVATED";
    public static final String TYPE_RELATIONSHIP_CHANGED = "RELATIONSHIP_CHANGED";
    public static final String TYPE_CONTACT_OTHER_PERSON = "CONTACT_OTHER_PERSON";

    private final WorldEventRepository repo;

    public WorldEventEngine(WorldEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorldEvent publish(String agentId, String type, String source,
                              String subject, String target, Map<String, Object> payload,
                              double importance) {
        try {
            WorldEvent e = new WorldEvent();
            e.setAgentId(agentId);
            e.setType(type);
            e.setSource(source);
            e.setSubject(subject);
            e.setTarget(target);
            e.setPayload(payload == null ? new LinkedHashMap<>() : payload);
            e.setImportance(Math.max(0, Math.min(1, importance)));
            return repo.saveAndFlush(e);
        } catch (Exception ex) {
            log.debug("[WorldEvent] 落库失败 agent={} type={}: {}", agentId, type, ex.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<WorldEvent> unprocessed(String agentId) {
        return repo.findUnprocessed(agentId);
    }

    @Transactional
    public void markProcessed(WorldEvent e) {
        if (e == null) return;
        e.setProcessedAt(LocalDateTime.now());
        repo.save(e);
    }
}
