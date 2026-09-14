package com.luxera.companion.plan;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §5 Plan 状态机: 计划可概率/可撤销/可被打断, 变更沿因果链可解释。
 */
@ActiveProfiles("test")
@SpringBootTest
class PlanServiceTest {

    @Autowired
    PlanService planService;
    @Autowired
    PlanRepository planRepo;
    @Autowired
    PlanRevisionRepository revisionRepo;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("plan-user");
        c.setName("小满");
        companionRepository.save(c);
    }

    @Test
    void planLifecycleWithRevisions() {
        Plan p = planService.create(companionId, "ACTIVITY", "晚上去跑步", 0.6, 0.5,
                LocalDateTime.now().plusHours(3), "如果不下雨", null);
        assertEquals(Plan.STATUS_PLANNED, p.getStatus());
        assertEquals(0.6, p.getConfidence(), 0.001);

        planService.activate(companionId, p.getId(), "吃完饭没事干, 就去跑");
        planService.complete(companionId, p.getId(), "跑完了, 五公里");
        assertEquals(Plan.STATUS_COMPLETED, planRepo.findById(p.getId()).orElseThrow().getStatus());

        var revs = revisionRepo.findByPlanIdOrderByOccurredAtAsc(p.getId());
        assertEquals(3, revs.size(), "创建/激活/完成各一条 revision");
        assertEquals("CREATED", revs.get(0).getAction());
        assertEquals("COMPLETED", revs.get(2).getAction());
    }

    @Test
    void interruptIsExplanable() {
        Plan p = planService.create(companionId, "ACTIVITY", "周末去爬山", 0.8, 0.4,
                LocalDateTime.now().plusDays(1), null, null);
        // 突发事件打断(比如朋友约饭)
        planService.interrupt(companionId, p.getId(), "friend_dinner", "朋友临时喊我吃饭, 就没去成");

        assertEquals(Plan.STATUS_SUPERSEDED, planRepo.findById(p.getId()).orElseThrow().getStatus());

        // 用户追问"你不是说要去爬山吗" → 沿 revision 链自然解释
        String explain = planService.explain(companionId, "爬山");
        assertNotNull(explain, "被中断的计划应可解释");
        assertTrue(explain.contains("爬山"), "解释应包含原计划: " + explain);
        assertTrue(explain.contains("朋友") || explain.contains("没去成"), "解释应包含中断原因: " + explain);
    }

    @Test
    void staleLowConfidencePlanExpires() {
        Plan p = planService.create(companionId, "ACTIVITY", "随手翻翻书", 0.5, 0.8,
                LocalDateTime.now().minusHours(3), null, null);
        int expired = planService.expireStalePlans(companionId, LocalDateTime.now());
        assertEquals(1, expired);
        assertEquals(Plan.STATUS_CANCELLED, planRepo.findById(p.getId()).orElseThrow().getStatus());
    }

    @Test
    void activePlansExcludesFinished() {
        Plan a = planService.create(companionId, "ACTIVITY", "晚上看电影", 0.7, 0.5,
                LocalDateTime.now().plusHours(2), null, null);
        Plan b = planService.create(companionId, "ACTIVITY", "明天去超市", 0.6, 0.5,
                LocalDateTime.now().plusDays(1), null, null);
        planService.complete(companionId, a.getId(), "看完了");
        List<Plan> active = planService.activePlans(companionId);
        assertEquals(1, active.size());
        assertEquals("明天去超市", active.get(0).getTitle());
    }
}
