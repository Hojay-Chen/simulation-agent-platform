package com.luxera.companion.reflection;

import com.luxera.companion.persona.PersonaEvolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WeeklyReflectionJob {

    private final ReflectionService reflectionService;
    private final PersonaEvolutionService personaEvolutionService;

    public WeeklyReflectionJob(ReflectionService reflectionService,
                               PersonaEvolutionService personaEvolutionService) {
        this.reflectionService = reflectionService;
        this.personaEvolutionService = personaEvolutionService;
    }

    @Scheduled(cron = "${app.scheduler.weekly-reflection-cron}")
    public void runWeekly() {
        reflectionService.runAllWeekly();
        // 每周反思后,基于证据保守地演化人格
        try {
            personaEvolutionService.runAll();
        } catch (Exception e) {
            log.warn("人格演化失败: {}", e.getMessage());
        }
    }
}
