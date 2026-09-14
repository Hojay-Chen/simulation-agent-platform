package com.luxera.companion.digitalhuman.state;

import com.luxera.companion.cognitive.CognitiveSession;
import com.luxera.companion.cognitive.CognitiveSessionRepository;
import com.luxera.companion.cognitive.CognitiveSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §21.1 StateVersionGate 测试:
 * - LLM 返回时版本未变 → 提交成功;
 * - LLM 期间版本已变(其他状态变更) → 提交失败, 旧结果作废(MVP 验收 12)。
 */
@ActiveProfiles("test")
@SpringBootTest
class StateVersionGateTest {

    @Autowired
    StateVersionGate gate;
    @Autowired
    CognitiveSessionService sessionService;
    @Autowired
    CognitiveSessionRepository sessionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = "gate-test-" + UUID.randomUUID().toString().substring(0, 8);
        sessionService.getOrCreate(companionId);
    }

    @AfterEach
    void tearDown() {
        sessionRepository.findByCompanionId(companionId).ifPresent(sessionRepository::delete);
    }

    @Test
    void commitSucceedsWhenVersionUnchanged() {
        long v = gate.snapshot(companionId);
        assertTrue(gate.tryCommit(companionId, v), "版本未变 → 提交成功");
        assertTrue(gate.snapshot(companionId) > v, "提交后版本递增");
    }

    @Test
    void commitFailsWhenVersionChanged() {
        long v = gate.snapshot(companionId);
        // LLM 调用期间, 其他状态变更方 bump 了版本
        sessionService.touchOnMessage(companionId, "他刚说起工作", "在想他说的那件事", null);
        assertFalse(gate.tryCommit(companionId, v), "版本已变 → 旧结果不得提交(作废)");
    }

    @Test
    void repeatedCommitsIncrementVersion() {
        long v1 = gate.snapshot(companionId);
        assertTrue(gate.tryCommit(companionId, v1));
        long v2 = gate.snapshot(companionId);
        assertTrue(gate.tryCommit(companionId, v2));
        assertTrue(gate.snapshot(companionId) == v2 + 1, "版本单调递增");
    }

    @Test
    void staleCommitRejectedAfterMultipleBumps() {
        long v = gate.snapshot(companionId);
        sessionService.touchOnMessage(companionId, "焦点A", "想法A", null);
        sessionService.touchOnMessage(companionId, "焦点B", "想法B", null);
        assertFalse(gate.tryCommit(companionId, v), "多次变更后旧快照仍不可提交");
        // 但用最新版本可以提交
        assertTrue(gate.tryCommit(companionId, gate.snapshot(companionId)));
    }

    @Test
    void snapshotOfMissingPersonReturnsZero() {
        assertTrue(gate.snapshot("no-such-person") >= 0);
        assertFalse(gate.tryCommit("no-such-person", 0), "不存在的 Person 不可提交");
    }
}
