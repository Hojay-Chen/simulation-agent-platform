package com.luxera.companion.runtime.agent.event;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件模拟定时任务(§78): 周期性检查, 只有休闲/通勤/晚间等合理时段才模拟。
 * 不是每 10 分钟一个剧情 —— 事件概率极低, 大部分时候无事发生。
 */
@Slf4j
@Component
public class EventSimulationJob {

    private final EventSimulator eventSimulator;
    private final CompanionRepository companionRepo;
    private final CompanionSchedule schedule;

    public EventSimulationJob(EventSimulator eventSimulator, CompanionRepository companionRepo,
                              CompanionSchedule schedule) {
        this.eventSimulator = eventSimulator;
        this.companionRepo = companionRepo;
        this.schedule = schedule;
    }

    @Scheduled(cron = "${app.scheduler.event-simulation-cron:0 */30 * * * *}")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<Companion> companions = companionRepo.findAll();
        for (Companion c : companions) {
            if (c.getDeletedAt() != null) continue;
            // 睡觉时不模拟生活事件
            if (schedule.activityFor(c.getId(), now) == CompanionSchedule.Activity.SLEEP) continue;
            try {
                eventSimulator.simulate(c.getId(), now);
            } catch (Exception e) {
                log.warn("[事件模拟] {} 失败: {}", c.getId(), e.getMessage());
            }
        }
    }
}
