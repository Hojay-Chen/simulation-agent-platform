package com.luxera.companion.behavior;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * §四十一 行为 Tick: 数字人的世界每 5 分钟推进一次。
 *
 * 取代 的"15 分钟主动检查" —— 不再是"要不要发主动消息"的定时器,
 * 而是"她此刻最可能做什么"的中央行为选择(睡觉/看手机/联系用户/联系朋友/继续生活/发呆)。
 */
@Slf4j
@Component
public class BehaviorTickJob {

    private final BehaviorEngine behaviorEngine;

    public BehaviorTickJob(BehaviorEngine behaviorEngine) {
        this.behaviorEngine = behaviorEngine;
    }

    @Scheduled(cron = "${app.scheduler.behavior-tick-cron}")
    public void run() {
        try {
            behaviorEngine.evaluateAll(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[BehaviorTick] 失败: {}", e.getMessage());
        }
    }
}
