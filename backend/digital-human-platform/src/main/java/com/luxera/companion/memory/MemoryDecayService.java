package com.luxera.companion.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 记忆衰减: 低重要性且长期未被回忆的旧记忆归档,重要记忆不因时间消失。
 * (设计文档 30-31 节;检索强度中的 recency_decay 在 Memory#retrievalStrength 实时生效)
 */
@Slf4j
@Component
public class MemoryDecayService {

    private final MemoryRepository repo;

    public MemoryDecayService(MemoryRepository repo) {
        this.repo = repo;
    }

    /** 每周一 04:30 归档超过 180 天、重要性低、很少被回忆的记忆 */
    @Scheduled(cron = "${app.scheduler.memory-decay-cron:0 30 4 * * MON}")
    @Transactional
    public void decayOldMemories() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int archived = 0;
        for (Memory m : repo.findStaleActive(cutoff)) {
            if (m.getImportance() < 0.4 && m.getRetrievalCount() < 3) {
                m.setStatus("archived");
                repo.save(m);
                archived++;
            }
        }
        if (archived > 0) {
            log.info("记忆衰减: 归档 {} 条低活性记忆", archived);
        }
    }
}
