package com.luxera.companion.thought;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 想法维护(设计文档 §6.3): 24h 弱化, 72h 过期 —— 思想必须允许消失 */
@Component
public class ThoughtMaintenanceJob {

    private final ThoughtRepository repo;

    public ThoughtMaintenanceJob(ThoughtRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${app.scheduler.thought-maintenance-cron}")
    @Transactional
    public void maintain() {
        LocalDateTime now = LocalDateTime.now();
        for (Thought t : repo.findAll()) {
            if (!"ACTIVE".equals(t.getStatus()) && !"SUPPRESSED".equals(t.getStatus())) continue;
            if (t.getCreatedAt().isBefore(now.minusHours(72))) {
                t.setStatus("EXPIRED");
                repo.save(t);
            } else if (t.getCreatedAt().isBefore(now.minusHours(24)) && t.getStrength() < 0.6) {
                t.setStrength(t.getStrength() * 0.5);   // 弱化
                repo.save(t);
            }
        }
    }
}
