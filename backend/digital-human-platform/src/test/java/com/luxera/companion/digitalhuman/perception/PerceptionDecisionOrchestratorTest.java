package com.luxera.companion.digitalhuman.perception;

import com.luxera.companion.digitalhuman.decision.PersonDecision;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * V10 完整链路集成测试: ExternalEvent → Perception(规则评分) → Decision(策略决策)。
 * SnapshotFactory 以固定快照桩替换, 保证测试确定性(与时段/DB 状态无关)。
 */
@ActiveProfiles("test")
@SpringBootTest
class PerceptionDecisionOrchestratorTest {

    @Autowired
    PerceptionDecisionOrchestrator orchestrator;

    @MockBean
    SnapshotFactory snapshotFactory;

    private static final String COMPANION_ID = "orch-test-companion";
    private static final String USER_ID = "orch-user";

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 19, 20, 0);

    @BeforeEach
    void setUp() {
        // 默认: 休闲 + 精力充沛 + 手机声音在手边 + 环境安静
        when(snapshotFactory.life(eq(COMPANION_ID), eq(now)))
                .thenReturn(LifeSnapshot.of("LEISURE", 0.25, false, "悠闲地待着"));
        when(snapshotFactory.mind(anyString()))
                .thenReturn(MindSnapshot.of(0.5, 0.2, 0.8));
        when(snapshotFactory.device(eq(COMPANION_ID), eq(now)))
                .thenReturn(DeviceSnapshot.of("sound", false, 0.1, "hand"));
        when(snapshotFactory.environment())
                .thenReturn(EnvironmentSnapshot.of(0.2));
    }

    private ExternalEvent notification() {
        return ExternalEvent.of(COMPANION_ID, ExternalEventType.DEVICE_NOTIFICATION,
                Map.of("source", "chat-platform"));
    }

    @Test
    void evaluatesFullChainWithRealState() {
        PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome =
                orchestrator.evaluate(notification(), USER_ID, COMPANION_ID, 0.6, now);
        assertNotNull(outcome.perception());
        assertNotNull(outcome.decision());
        assertNotNull(outcome.context());
        assertTrue(outcome.decision().decision() instanceof PersonDecision,
                "决策类型非法: " + outcome.decision().decision());
        assertFalse(outcome.decision().decision().reason().isBlank());
    }

    @Test
    void dndModeLeadsToIgnore() {
        when(snapshotFactory.device(eq(COMPANION_ID), eq(now)))
                .thenReturn(DeviceSnapshot.of("sound", true, 0.1, "hand"));

        PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome =
                orchestrator.evaluate(notification(), USER_ID, COMPANION_ID, 0.6, now);
        assertEquals(PerceptionLevel.NONE, outcome.perception().level(), "勿扰 → 完全感知不到");
        assertTrue(outcome.decision().decision() instanceof PersonDecision.IgnoreDecision,
                "没感知到 → 忽略, 实际 " + outcome.decision().decision());
    }

    @Test
    void normalStateFocusedAndImportantReplies() {
        // 声音+在手+休闲+重要 → FOCUSED → 立即回复
        PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome =
                orchestrator.evaluate(notification(), USER_ID, COMPANION_ID, 0.7, now);
        assertEquals(PerceptionLevel.FOCUSED, outcome.perception().level());
        assertTrue(outcome.decision().decision() instanceof PersonDecision.ReplyDecision,
                "FOCUSED+重要 → 立即回, 实际 " + outcome.decision().decision());
    }

    @Test
    void lowImportanceNeverRepliesImmediately() {
        PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome =
                orchestrator.evaluate(notification(), USER_ID, COMPANION_ID, 0.15, now);
        assertTrue(!(outcome.decision().decision() instanceof PersonDecision.ReplyDecision),
                "低重要性不应立即回复, 实际 " + outcome.decision().decision());
    }
}
