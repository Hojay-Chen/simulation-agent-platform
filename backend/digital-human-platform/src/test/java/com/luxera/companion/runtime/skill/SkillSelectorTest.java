package com.luxera.companion.runtime.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §21 Skill 按需加载: 按 Intent 选技能(不每轮全量加载)。
 */
class SkillSelectorTest {

    private static SkillSelector selector() {
        SkillRegistry registry = new SkillRegistry(new SkillLoader());
        registry.init();   // 手动触发 @PostConstruct(测试环境不走 Spring 生命周期)
        return new SkillSelector(registry);
    }

    @Test
    void sadIntentSelectsEmotionSkills() {
        SkillSelector selector = selector();
        String text = selector.composeForIntent("sad");
        assertNotNull(text);
        assertFalse(text.isBlank());
        assertTrue(selector.skillIdsFor("sad").contains("emotion.appraisal"));
    }

    @Test
    void planningIntentSelectsEventSkill() {
        SkillSelector selector = selector();
        assertTrue(selector.skillIdsFor("planning").contains("event.simulation"));
        assertNotNull(selector.composeForIntent("planning"));
    }

    @Test
    void unknownIntentReturnsNull() {
        SkillSelector selector = selector();
        assertNull(selector.composeForIntent("hello"), "普通问候不需要额外技能");
        assertNull(selector.composeForIntent(null));
    }
}
