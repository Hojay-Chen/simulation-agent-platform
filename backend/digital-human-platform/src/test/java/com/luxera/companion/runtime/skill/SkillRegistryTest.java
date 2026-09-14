package com.luxera.companion.runtime.skill;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 技能注册表测试(§50-§53): SKILL.md 加载 + Agent 固定技能列表。 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.proactive-cron=0 0 0 1 1 *",
        "app.scheduler.thought-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.emotion-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.memory-consolidation-cron=0 0 0 1 1 *",
        "app.scheduler.daily-reflection-cron=0 0 0 1 1 *",
        "app.scheduler.weekly-reflection-cron=0 0 0 1 1 *",
        "app.scheduler.birthday-cron=0 0 0 1 1 *",
        "app.scheduler.open-loop-cron=0 0 0 1 1 *"
})
class SkillRegistryTest {

    @Autowired
    SkillRegistry registry;

    @Autowired
    SkillPromptComposer composer;

    @Test
    void loadsAllCoreSkills() {
        assertTrue(registry.get("core.identity") != null);
        assertTrue(registry.get("core.personality") != null);
        assertTrue(registry.get("core.relationship") != null);
        assertTrue(registry.get("emotion.appraisal") != null);
        assertTrue(registry.get("brain.executive") != null);
        assertTrue(registry.get("memory.recall") != null);
        assertTrue(registry.get("expression.generation") != null);
        assertTrue(registry.get("event.simulation") != null);
    }

    @Test
    void agentSkillListsAreFixed() {
        List<Skill> emotionSkills = registry.listForAgent("emotion");
        assertEquals(4, emotionSkills.size());
        assertTrue(emotionSkills.stream().anyMatch(s -> s.id().equals("emotion.appraisal")));
        assertTrue(emotionSkills.stream().anyMatch(s -> s.id().equals("core.identity")));

        List<Skill> brainSkills = registry.listForAgent("brain");
        assertTrue(brainSkills.stream().anyMatch(s -> s.id().equals("brain.executive")));

        List<Skill> expressionSkills = registry.listForAgent("expression");
        assertTrue(expressionSkills.stream().anyMatch(s -> s.id().equals("expression.cadence")));
    }

    @Test
    void composerProducesSkillBase() {
        String base = composer.composeBase("emotion", "情绪评估 Agent");
        assertNotNull(base);
        assertTrue(base.contains("情绪评估"));
        assertTrue(base.contains("emotion.appraisal") || base.contains("appraisal"));
    }
}
