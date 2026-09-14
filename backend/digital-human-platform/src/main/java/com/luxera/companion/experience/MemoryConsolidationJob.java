package com.luxera.companion.experience;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 记忆固话 + 经历生命周期清理(设计文档 §23/§41): 每日固话高价值经历, 清理 30 天前的低价值经历 */
@Slf4j
@Component
public class MemoryConsolidationJob {

    private final CompanionRepository companionRepo;
    private final ExperienceProcessor experienceProcessor;
    private final ExperienceRepository experienceRepo;

    public MemoryConsolidationJob(CompanionRepository companionRepo, ExperienceProcessor experienceProcessor,
                                  ExperienceRepository experienceRepo) {
        this.companionRepo = companionRepo;
        this.experienceProcessor = experienceProcessor;
        this.experienceRepo = experienceRepo;
    }

    @Scheduled(cron = "${app.scheduler.memory-consolidation-cron}")
    public void consolidateAll() {
        int total = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                total += experienceProcessor.consolidate(c.getId());
            } catch (Exception e) {
                log.warn("记忆固话失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        if (total > 0) {
            log.info("[MemoryConsolidation] 固话 {} 条记忆", total);
        }
        // 生命周期: 清理 30 天前被丢弃的低价值经历, 防数据库无限增长
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int removed = 0;
        for (Experience e : experienceRepo.findByStatusAndCreatedAtBefore("DISCARDED", cutoff)) {
            experienceRepo.delete(e);
            removed++;
        }
        if (removed > 0) {
            log.info("[MemoryConsolidation] 清理 {} 条过期低价值经历", removed);
        }
    }
}
