package com.luxera.companion.emotion;

import com.luxera.companion.state.AgentStateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 情绪维护定时任务(含 负面情绪 hurt/anger 随时间的自然衰减) */
@Component
public class EmotionMaintenanceJob {

    private final EmotionDecayService decayService;
    private final AgentStateService agentStateService;

    public EmotionMaintenanceJob(EmotionDecayService decayService, AgentStateService agentStateService) {
        this.decayService = decayService;
        this.agentStateService = agentStateService;
    }

    @Scheduled(cron = "${app.scheduler.emotion-maintenance-cron}")
    public void maintain() {
        decayService.decay();
        // 负面情绪自然愈合(每次衰减 0.08)
        agentStateService.decayAllNegative(0.08);
    }
}
