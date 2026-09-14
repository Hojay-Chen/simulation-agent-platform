package com.luxera.companion.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 实体服务(P2 §五十四): 记录/更新/检索用户长期提到的实体。 */
@Service
public class MemoryEntityService {

    private final MemoryEntityRepository repo;

    public MemoryEntityService(MemoryEntityRepository repo) {
        this.repo = repo;
    }

    /** 记录一次提及: 同名实体合并更新(提到越多越凸显), 新实体则创建 */
    @Transactional
    public MemoryEntity mention(String userId, String companionId, String name, String type,
                          String description, String context) {
        if (name == null || name.isBlank() || name.length() > 128) return null;
        String trimmed = name.trim();
        MemoryEntity e = repo.findByUserIdAndCompanionIdAndName(userId, companionId, trimmed)
                .orElseGet(() -> {
                    MemoryEntity n = new MemoryEntity();
                    n.setUserId(userId);
                    n.setCompanionId(companionId);
                    n.setName(trimmed);
                    n.setType(type == null ? "TOPIC" : type);
                    n.setFirstSeenAt(LocalDateTime.now());
                    return repo.save(n);
                });
        e.setType(type == null ? e.getType() : type);
        e.setMentionCount(e.getMentionCount() + 1);
        e.setLastSeenAt(LocalDateTime.now());
        e.setSalience(Math.min(1.0, 0.2 + e.getMentionCount() * 0.1));
        if (description != null && !description.isBlank()) e.setDescription(description);
        if (context != null && !context.isBlank()) e.setLastContext(context);
        if (!"active".equals(e.getStatus())) e.setStatus("active");
        return repo.save(e);
    }

    /** 用户最近常提的实体(供上下文注入) */
    @Transactional(readOnly = true)
    public List<MemoryEntity> recent(String userId, String companionId, int limit) {
        return repo.findByUserIdAndCompanionIdAndStatusOrderBySalienceDesc(userId, companionId, "active")
                .stream().limit(limit).toList();
    }

    @Transactional
    public void markSeen(String userId, String companionId, List<String> names) {
        if (names == null) return;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            repo.findByUserIdAndCompanionIdAndName(userId, companionId, name.trim())
                    .ifPresent(e -> e.setLastSeenAt(LocalDateTime.now()));
        }
    }
}
