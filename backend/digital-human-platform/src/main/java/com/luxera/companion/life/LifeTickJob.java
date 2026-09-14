package com.luxera.companion.life;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 生活推进定时任务(Level 0-1, 不调用 LLM) */
@Slf4j
@Component
public class LifeTickJob {

    private final CompanionRepository companionRepo;
    private final LifeRuntime lifeRuntime;

    public LifeTickJob(CompanionRepository companionRepo, LifeRuntime lifeRuntime) {
        this.companionRepo = companionRepo;
        this.lifeRuntime = lifeRuntime;
    }

    @Scheduled(cron = "${app.scheduler.life-tick-cron}")
    public void runLifeTick() {
        LocalDateTime now = LocalDateTime.now();
        int ticks = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                lifeRuntime.tick(c.getId(), now);
                ticks++;
            } catch (Exception e) {
                log.warn("生活推进失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        if (ticks > 0) {
            log.debug("[LifeTick] 推进 {} 个伴侣", ticks);
        }
    }
}
