package com.luxera.companion.reality;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §10 Reality Consistency Checker: 表达与事实冲突时禁止直接发送。
 * 场景: 她在招呼客人, 回复却说"我一直在找手机" → 冲突。
 */
@ActiveProfiles("test")
@SpringBootTest
class RealityConsistencyCheckerTest {

    @Autowired
    RealityConsistencyChecker checker;
    @Autowired
    PlanService planService;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("rc-user");
        c.setName("小满");
        companionRepository.save(c);
    }

    @Test
    void workActivityConflictsWithRestClaim() {
        // 现实在忙工作 → 回复说在休息 → 冲突
        String conflict = checker.check("我一直在休息躺着呢", companionId,
                "现在是周二 15:00,小满正在忙工作。", LocalDateTime.now());
        assertNotNull(conflict, "忙工作时说在休息应判冲突");
        assertTrue(conflict.contains("矛盾"));
    }

    @Test
    void leisureActivityConflictsWithWorkClaim() {
        // 现实在休闲 → 回复说在开会 → 冲突
        String conflict = checker.check("我刚才一直在开会", companionId,
                "现在是周六 20:00,小满在悠闲地享受自己的时间。", LocalDateTime.now());
        assertNotNull(conflict);
    }

    @Test
    void consistentReplyPasses() {
        String ok = checker.check("刚忙完一阵, 你现在说", companionId,
                "现在是周二 15:00,小满正在忙工作。", LocalDateTime.now());
        assertNull(ok, "与现状一致的回复应通过");
    }

    @Test
    void interruptedPlanClaimIsConflict() {
        // 计划"周末去爬山"被打断后, 回复仍说"还想去爬山" → 冲突
        var p = planService.create(companionId, "ACTIVITY", "周末去爬山", 0.8, 0.4,
                LocalDateTime.now().plusDays(1), null, null);
        planService.interrupt(companionId, p.getId(), "friend_dinner", "朋友约饭");

        String conflict = checker.check("我还准备去爬山呢", companionId, "休闲", LocalDateTime.now());
        assertNotNull(conflict, "被打断的计划不应被宣称还要去");
        assertTrue(conflict.contains("爬山"));
    }
}
