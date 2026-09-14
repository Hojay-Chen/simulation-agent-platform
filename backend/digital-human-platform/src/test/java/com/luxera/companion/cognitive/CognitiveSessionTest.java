package com.luxera.companion.cognitive;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §4.3 Cognitive Session: 连续心智的读写与乐观锁。
 */
@ActiveProfiles("test")
@SpringBootTest
class CognitiveSessionTest {

    @Autowired
    CognitiveSessionService service;
    @Autowired
    CognitiveSessionRepository repo;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("cs-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void getOrCreateIsSingleton() {
        CognitiveSession a = service.getOrCreate(companionId);
        CognitiveSession b = service.getOrCreate(companionId);
        assertEquals(a.getId(), b.getId(), "每个 agent 只有一条认知会话");
    }

    @Test
    void touchOnMessageUpdatesFocusAndVersion() {
        service.touchOnMessage(companionId, "他工作上遇到麻烦", "想多陪陪他", "有点担心他");
        CognitiveSession s = service.get(companionId);
        assertEquals("他工作上遇到麻烦", s.getCurrentFocus());
        assertEquals("想多陪陪他", s.getCurrentThought());
        assertEquals(1, s.getStateVersion(), "每次更新版本递增(乐观锁)");

        service.touchOnMessage(companionId, "他面试通过了", null, null);
        CognitiveSession s2 = service.get(companionId);
        assertEquals("他面试通过了", s2.getCurrentFocus());
        assertEquals(2, s2.getStateVersion());
    }

    @Test
    void describeComposesCognitiveSummary() {
        service.touchOnMessage(companionId, "他心情不好", "想安慰他", "有点担心");
        String desc = service.describe(companionId);
        assertNotNull(desc);
        assertTrue(desc.contains("正关注:他心情不好"));
        assertTrue(desc.contains("心里想:想安慰他"));
    }

    @Test
    void activePlansBriefsRoundTrip() {
        service.setActivePlans(companionId, List.of(
                CognitiveSessionService.planBrief("晚上去跑步", "PLANNED", "2026-08-18T20:00:00")));
        List<Map<String, Object>> briefs = service.activePlanBriefs(companionId);
        assertEquals(1, briefs.size());
        assertEquals("晚上去跑步", briefs.get(0).get("title"));
    }
}
