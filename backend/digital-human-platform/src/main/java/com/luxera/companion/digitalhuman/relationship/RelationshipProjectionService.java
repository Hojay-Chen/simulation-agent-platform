package com.luxera.companion.digitalhuman.relationship;

import com.luxera.companion.digitalhuman.reality.RealityEvent;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * V10 §17 RelationshipProjectionService: 关系事实与 Reality Ledger 的核对(Reconcile)。
 *
 * 从账本投影互动摘要 → 更新 Relationship 的**事实字段**(messageCount/lastInteractionAt),
 * 保证关系状态与真实经历一致; 投影可重复执行(幂等), 不覆盖情感/人格层字段。
 */
@Slf4j
@Service
public class RelationshipProjectionService {

    private final RealityLedger realityLedger;
    private final RelationshipProjector projector;
    private final RelationshipRepository relationshipRepository;

    public RelationshipProjectionService(RealityLedger realityLedger,
                                         RelationshipProjector projector,
                                         RelationshipRepository relationshipRepository) {
        this.realityLedger = realityLedger;
        this.projector = projector;
        this.relationshipRepository = relationshipRepository;
    }

    /** 从账本投影该伴侣的互动摘要(只读) */
    @Transactional(readOnly = true)
    public InteractionSummary projection(String companionId) {
        List<RealityEvent> events = realityLedger.all(companionId);
        return projector.project(events);
    }

    /**
     * 核对: 投影 → 修正 Relationship 事实字段。
     * @return 是否有修正发生
     */
    @Transactional
    public boolean reconcile(String companionId, String userId) {
        Relationship relationship = relationshipRepository
                .findByUserIdAndCompanionId(userId, companionId).orElse(null);
        if (relationship == null) {
            return false;
        }
        List<RealityEvent> events = realityLedger.all(companionId).stream()
                .filter(e -> RelationshipProjector.isInteractionEvent(e.type()))
                .toList();
        InteractionSummary summary = projector.project(events);
        if (summary.noInteraction()) {
            return false;
        }

        boolean changed = false;
        // 事实字段: 她读到的消息数 ≈ 用户消息数(账本为准)
        long ledgerMessageCount = summary.messagesRead();
        if (ledgerMessageCount != relationship.getMessageCount()) {
            log.info("[RelationshipProjection] {} 消息计数修正 {} → {} (账本为准)",
                    companionId, relationship.getMessageCount(), ledgerMessageCount);
            relationship.setMessageCount((int) ledgerMessageCount);
            changed = true;
        }
        // 最近互动时间(账本为准)
        if (summary.lastInteractionAt() != null) {
            LocalDateTime ledgerLast = LocalDateTime.ofInstant(
                    summary.lastInteractionAt(), ZoneId.systemDefault());
            if (relationship.getLastInteractionAt() == null
                    || ledgerLast.isAfter(relationship.getLastInteractionAt())) {
                relationship.setLastInteractionAt(ledgerLast);
                changed = true;
            }
        }
        if (changed) {
            relationshipRepository.save(relationship);
        }
        return changed;
    }
}
