package com.luxera.companion.openloop;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Open Loop 维护(Level 0, 不调用 LLM): 长期无进展的未完成事项自然淡出 */
@Component
public class OpenLoopJob {

    private final OpenLoopRepository repo;

    public OpenLoopJob(OpenLoopRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${app.scheduler.open-loop-cron}")
    @Transactional
    public void maintain() {
        LocalDateTime now = LocalDateTime.now();
        for (OpenLoop l : repo.findAll()) {
            if ("RESOLVED".equals(l.getStatus()) || "ABANDONED".equals(l.getStatus())
                    || "FORGOTTEN".equals(l.getStatus())) {
                continue;
            }
            if (l.getLastReferencedAt() != null && l.getLastReferencedAt().isBefore(now.minusDays(14))) {
                l.setStatus("ABANDONED");
                repo.save(l);
            } else if ("WAITING".equals(l.getStatus()) && l.getExpectedResolutionAt() != null
                    && l.getExpectedResolutionAt().isBefore(now.minusDays(7))) {
                l.setStatus("ABANDONED");
                repo.save(l);
            }
        }
    }
}
