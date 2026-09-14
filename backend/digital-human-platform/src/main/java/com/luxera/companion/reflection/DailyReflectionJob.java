package com.luxera.companion.reflection;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyReflectionJob {

    private final ReflectionService reflectionService;

    public DailyReflectionJob(ReflectionService reflectionService) {
        this.reflectionService = reflectionService;
    }

    @Scheduled(cron = "${app.scheduler.daily-reflection-cron}")
    public void runDaily() {
        reflectionService.runAllDaily();
    }
}
