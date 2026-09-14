package com.luxera.companion.experience;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 原始经历记录(设计文档 §11.2) */
@Service
public class ExperienceService {

    private final ExperienceRepository repo;

    public ExperienceService(ExperienceRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Experience record(String companionId, String type, String sourceType, String sourceId,
                             String content, String summary,
                             double importance, double emotionalWeight, double relationshipWeight,
                             LocalDateTime occurredAt) {
        Experience e = new Experience();
        e.setCompanionId(companionId);
        e.setType(type);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setContent(content);
        e.setSummary(summary);
        e.setImportance(importance);
        e.setEmotionalWeight(emotionalWeight);
        e.setRelationshipWeight(relationshipWeight);
        e.setOccurredAt(occurredAt != null ? occurredAt : LocalDateTime.now());
        e.setStatus("PENDING");
        return repo.save(e);
    }

    @Transactional(readOnly = true)
    public List<Experience> list(String companionId) {
        return repo.findByCompanionIdOrderByOccurredAtDesc(companionId);
    }
}
