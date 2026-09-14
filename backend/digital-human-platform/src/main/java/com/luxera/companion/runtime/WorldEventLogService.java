package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 世界事件日志服务(§62): 记录/查询世界事件。
 * 非关键路径 —— 写失败不影响主流程。
 */
@Service
public class WorldEventLogService {

    private final WorldEventLogRepository repo;
    private final ObjectMapper mapper;

    public WorldEventLogService(WorldEventLogRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional
    public WorldEventLog record(WorldEvent event) {
        try {
            WorldEventLog log = new WorldEventLog();
            log.setCompanionId(event.companionId());
            log.setEventType(event.type());
            log.setOccurredAt(event.timestamp());
            try {
                log.setPayload(event.payload() == null ? null : mapper.writeValueAsString(event.payload()));
            } catch (Exception e) {
                log.setPayload(null);
            }
            return repo.save(log);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<WorldEventLog> recent(String companionId, int limit) {
        List<WorldEventLog> all = repo.findTop200ByCompanionIdOrderByOccurredAtDesc(companionId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    @Transactional(readOnly = true)
    public List<WorldEventLog> since(String companionId, LocalDateTime after) {
        return repo.findByCompanionIdAndOccurredAtAfterOrderByOccurredAtAsc(companionId, after);
    }
}
