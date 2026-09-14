package com.luxera.companion.digitalhuman.relationship;

import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.relationship.RelationshipTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §17 Relationship Projection 集成测试:
 * Reality Ledger 事件 → 投影 → reconcile 修正关系事实字段(账本为准, 幂等)。
 */
@ActiveProfiles("test")
@SpringBootTest
class RelationshipProjectionServiceTest {

    @Autowired
    RelationshipProjectionService projectionService;
    @Autowired
    RealityLedger realityLedger;
    @Autowired
    RelationshipRepository relationshipRepository;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;
    private String userId;
    private Relationship relationship;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        userId = "proj-user-" + UUID.randomUUID().toString().substring(0, 6);
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);

        Relationship rel = new Relationship();
        rel.setUserId(userId);
        rel.setCompanionId(companionId);
        rel.setUserPersonId(userId);
        rel.setAgentPersonId(companionId);
        RelationshipTypes.applyInitial(rel, RelationshipTypes.LOVER);
        relationship = relationshipRepository.save(rel);
    }

    @AfterEach
    void tearDown() {
        relationshipRepository.deleteById(relationship.getId());
        companionRepository.deleteById(companionId);
    }

    @Test
    void projectionReturnsLedgerSummary() {
        // 账本还没有互动事件
        InteractionSummary empty = projectionService.projection(companionId);
        assertTrue(empty.noInteraction());

        // 写入 3 条真实经历
        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m1"));
        realityLedger.append(companionId, RealityEventType.MESSAGE_SENT, Map.of("messageId", "a1"));
        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m2"));

        InteractionSummary summary = projectionService.projection(companionId);
        assertEquals(3, summary.totalEvents());
        assertEquals(2, summary.messagesRead());
        assertEquals(1, summary.messagesSentByPerson());
        assertEquals(0.5, summary.replyRate(), 0.001);
    }

    @Test
    void reconcileCorrectsMessageCountFromLedger() {
        // 关系实体错误计数(模拟增量更新丢失)
        relationship.setMessageCount(99);
        relationshipRepository.save(relationship);

        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m1"));
        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m2"));
        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m3"));

        boolean changed = projectionService.reconcile(companionId, userId);
        assertTrue(changed, "计数不一致应被修正");

        Relationship after = relationshipRepository.findById(relationship.getId()).orElseThrow();
        assertEquals(3, after.getMessageCount(), "messageCount 以账本为准");
        assertNotNull(after.getLastInteractionAt(), "最近互动时间由账本投影");
    }

    @Test
    void reconcileIsIdempotent() {
        realityLedger.append(companionId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m1"));

        assertTrue(projectionService.reconcile(companionId, userId), "第一次修正");
        assertFalse(projectionService.reconcile(companionId, userId), "第二次无变化(幂等)");
        assertEquals(1, relationshipRepository.findById(relationship.getId()).orElseThrow().getMessageCount());
    }

    @Test
    void reconcileWithoutInteractionDoesNothing() {
        assertFalse(projectionService.reconcile(companionId, userId), "无互动事件不修正");
    }
}
