package com.luxera.companion.experience;

import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆固话(设计文档 §11.3 / §12): 不是每条 Experience 都进入 Memory。
 * memory_score = importance × emotional_weight × relationship_weight × repetition × future_relevance
 * 高于阈值才 consolidation 为长期 Memory。
 */
@Slf4j
@Component
public class MemoryConsolidator {

    private static final double THRESHOLD = 0.45;

    private final ExperienceRepository expRepo;
    private final MemoryService memoryService;
    private final CompanionRepository companionRepo;

    public MemoryConsolidator(ExperienceRepository expRepo, MemoryService memoryService,
                              CompanionRepository companionRepo) {
        this.expRepo = expRepo;
        this.memoryService = memoryService;
        this.companionRepo = companionRepo;
    }

    @Transactional
    public int consolidate(String companionId) {
        List<Experience> pending = expRepo.findByCompanionIdAndStatusOrderByOccurredAtDesc(companionId, "PENDING");
        int consolidated = 0;
        for (Experience e : pending) {
            double score = memoryScore(e);
            e.setMemoryScore(score);
            if (score >= THRESHOLD) {
                Companion companion = companionRepo.findById(companionId).orElse(null);
                if (companion != null) {
                    Memory m = new Memory();
                    m.setUserId(companion.getUserId());
                    m.setCompanionId(companionId);
                    m.setType(mapType(e.getType()));
                    m.setContent(e.getSummary() != null ? e.getSummary() : e.getContent());
                    m.setImportance(clamp(e.getImportance()));
                    m.setEmotionalWeight(clamp(e.getEmotionalWeight()));
                    m.setRelationshipWeight(clamp(e.getRelationshipWeight()));
                    m.setOccurredAt(e.getOccurredAt() != null ? e.getOccurredAt() : LocalDateTime.now());
                    m.setSourceType("experience");
                    m.setSourceId(e.getId());
                    m.setConsolidationSource(e.getId());
                    if (e.getRelationshipWeight() >= 0.9 && e.getImportance() >= 0.8) {
                        m.setNarrativeRole("INSIDE_JOKE");
                    }
                    memoryService.save(m);
                    e.setStatus("CONSOLIDATED");
                    consolidated++;
                } else {
                    e.setStatus("DISCARDED");
                }
            } else {
                e.setStatus("DISCARDED");
            }
            expRepo.save(e);
        }
        if (consolidated > 0) {
            log.info("记忆固话: companion={} 固话 {} 条", companionId, consolidated);
        }
        return consolidated;
    }

    /** 计算 memory_score */
    public double memoryScore(Experience e) {
        // repetition: 未来增强——按内容相似计数; 当前用保守 1.0
        double repetition = 1.0;
        LocalDateTime now = LocalDateTime.now();
        double futureRelevance = e.getOccurredAt() != null && e.getOccurredAt().isAfter(now.minusDays(7)) ? 0.9 : 0.5;
        return e.getImportance() * e.getEmotionalWeight() * e.getRelationshipWeight()
                * repetition * futureRelevance;
    }

    private static String mapType(String experienceType) {
        if (experienceType == null) return "episodic";
        return switch (experienceType) {
            case "RELATIONSHIP_EVENT" -> "relational";
            case "SHARED_EXPERIENCE" -> "shared";
            case "THOUGHT" -> "self";
            case "EMOTIONAL_EVENT" -> "semantic";
            default -> "episodic";   // CONVERSATION / LIFE_EVENT
        };
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
