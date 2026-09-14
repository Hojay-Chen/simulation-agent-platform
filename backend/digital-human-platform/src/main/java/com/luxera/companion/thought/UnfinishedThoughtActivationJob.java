package com.luxera.companion.thought;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * §31 Unfinished Thought 激活 Job: 周期扫描所有伴侣的未完成想法,
 * 在"冷却期过后 + 优先级达标"时重新激活, 让 ProactiveEngine 能在未来想起它。
 *
 * 模拟真人: "想说的话被打断, 过了半小时想起, 就回去补一句"。
 */
@Component
public class UnfinishedThoughtActivationJob {

    private final ThoughtService thoughtService;
    private final CompanionRepository companionRepository;

    public UnfinishedThoughtActivationJob(ThoughtService thoughtService,
                                          CompanionRepository companionRepository) {
        this.thoughtService = thoughtService;
        this.companionRepository = companionRepository;
    }

    @Scheduled(cron = "${app.scheduler.unfinished-thought-cron}")
    @Transactional
    public void activate() {
        LocalDateTime now = LocalDateTime.now();
        for (Companion c : companionRepository.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                var reactivated = thoughtService.activateUnfinished(c.getId(), now);
                if (!reactivated.isEmpty()) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .info("[UnfinishedThought] 激活 {} 个未完成想法 companion={}",
                                    reactivated.size(), c.getId());
                }
            } catch (Exception ignored) {
                // 单个伴侣失败不影响整体
            }
        }
    }
}
