package com.luxera.companion.sleep;

import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * §45: 睡眠推进定时任务(Coarse Tick)。
 * 每 1-5 分钟推进一次睡眠压力/节律状态, 并应用睡眠决策。
 *
 * §二十八/§二十九 升级: 睡眠是 Behavior Candidate ——
 * 决策考虑"是否正在陪你聊"(社交参与/动机), 深夜聊天时她会硬撑,
 * 而不是到点就被 tick 强制入睡。
 */
@Slf4j
@Component
public class SleepTickJob {

    private final SleepModel sleepModel;
    private final CompanionRepository companionRepository;
    private final BehaviorEngine behaviorEngine;
    private final com.luxera.companion.runtime.WakeupCatchUpService wakeupCatchUpService;

    public SleepTickJob(SleepModel sleepModel, CompanionRepository companionRepository,
                        BehaviorEngine behaviorEngine,
                        com.luxera.companion.runtime.WakeupCatchUpService wakeupCatchUpService) {
        this.sleepModel = sleepModel;
        this.companionRepository = companionRepository;
        this.behaviorEngine = behaviorEngine;
        this.wakeupCatchUpService = wakeupCatchUpService;
    }

    @Scheduled(cron = "${app.scheduler.sleep-tick-cron}")
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        for (Companion c : companionRepository.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                sleepModel.tick(c.getId(), now);
                // 睡眠决策考虑社交参与(深夜陪你聊 → 硬撑)
                var decision = behaviorEngine.sleepDecision(c.getId(), now);
                if (decision == SleepModel.SleepDecision.SLEEP && !sleepModel.isSleeping(c.getId(), now)) {
                    sleepModel.fallAsleep(c.getId(), now, "NATURAL");
                }
                // V9: 刚醒(自然醒/被吵醒) → 补处理睡眠期间的用户消息(她醒来拿起手机看到全部)
                if (!sleepModel.isSleeping(c.getId(), now) && c.getUserId() != null) {
                    var circ = sleepModel.getOrCreate(c.getId(), now);
                    if (wakeupCatchUpService.justWoke(circ.getLastWakeAt(), now)) {
                        wakeupCatchUpService.catchUp(c.getId(), c.getUserId(), now);
                    }
                }
            } catch (Exception e) {
                log.warn("[SleepTick] {} 失败: {}", c.getId(), e.getMessage());
            }
        }
    }
}
