package com.luxera.companion.selfmodel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SelfModelService {

    private final SelfModelRepository repo;

    public SelfModelService(SelfModelRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public SelfModel getOrCreate(String companionId) {
        return repo.findByCompanionId(companionId).orElseGet(() -> {
            SelfModel m = new SelfModel();
            m.setCompanionId(companionId);
            return repo.save(m);
        });
    }

    /** 合并更新(只覆盖非空字段), 版本+1, 记录原因 */
    @Transactional
    public SelfModel update(String companionId, SelfModelUpdate patch, String changeReason) {
        SelfModel m = getOrCreate(companionId);
        if (patch.facts != null && !patch.facts.isEmpty()) m.setFacts(patch.facts);
        if (patch.preferences != null && !patch.preferences.isEmpty()) m.setPreferences(patch.preferences);
        if (patch.patterns != null && !patch.patterns.isEmpty()) m.setPatterns(patch.patterns);
        if (patch.beliefs != null && !patch.beliefs.isEmpty()) m.setBeliefs(patch.beliefs);
        if (patch.goals != null && !patch.goals.isEmpty()) m.setGoals(patch.goals);
        if (patch.concerns != null && !patch.concerns.isEmpty()) m.setConcerns(patch.concerns);
        if (patch.plans != null && !patch.plans.isEmpty()) m.setPlans(patch.plans);
        if (patch.narrative != null && !patch.narrative.isBlank()) m.setNarrative(patch.narrative);
        if (patch.facts != null || patch.preferences != null || patch.patterns != null
                || patch.beliefs != null || patch.goals != null || patch.concerns != null
                || patch.plans != null || (patch.narrative != null && !patch.narrative.isBlank())) {
            m.setVersion(m.getVersion() + 1);
            m.setChangeReason(changeReason);
        }
        return repo.save(m);
    }

    @Transactional(readOnly = true)
    public SelfModel get(String companionId) {
        return repo.findByCompanionId(companionId).orElse(null);
    }

    /** 部分更新载体 */
    public record SelfModelUpdate(List<String> facts, List<String> preferences, List<String> patterns,
                                  List<String> beliefs, List<String> goals, List<String> concerns,
                                  List<String> plans, String narrative) {}
}
