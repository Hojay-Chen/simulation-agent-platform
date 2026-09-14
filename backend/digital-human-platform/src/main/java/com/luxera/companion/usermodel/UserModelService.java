package com.luxera.companion.usermodel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserModelService {

    private final UserFactRepository facts;
    private final UserPreferenceRepository preferences;
    private final UserPatternRepository patterns;
    private final UserHypothesisRepository hypotheses;

    public UserModelService(UserFactRepository facts, UserPreferenceRepository preferences,
                            UserPatternRepository patterns, UserHypothesisRepository hypotheses) {
        this.facts = facts;
        this.preferences = preferences;
        this.patterns = patterns;
        this.hypotheses = hypotheses;
    }

    @Transactional
    public UserFact saveFact(String userId, String companionId, UserFact f) {
        f.setUserId(userId);
        f.setCompanionId(companionId);
        return facts.findTopByUserIdAndCompanionIdAndPredicateAndObjectAndStatus(
                        userId, companionId, f.getPredicate(), f.getObject(), "active")
                .map(existing -> {
                    existing.setConfidence(Math.max(existing.getConfidence(), f.getConfidence()));
                    existing.setLastObservedAt(LocalDateTime.now());
                    if ("explicit".equals(f.getSourceType())) {
                        existing.setSourceType("explicit");
                    }
                    if (f.getValue() != null) existing.setValue(f.getValue());
                    return facts.save(existing);
                })
                .orElseGet(() -> facts.save(f));
    }

    @Transactional
    public UserPreference savePreference(String userId, String companionId, UserPreference p) {
        p.setUserId(userId);
        p.setCompanionId(companionId);
        return preferences.findTopByUserIdAndCompanionIdAndCategoryAndPreferenceAndStatus(
                        userId, companionId, p.getCategory(), p.getPreference(), "active")
                .map(existing -> {
                    existing.setConfidence(Math.max(existing.getConfidence(), p.getConfidence()));
                    existing.setObservedAt(LocalDateTime.now());
                    if ("explicit".equals(p.getSourceType())) {
                        existing.setSourceType("explicit");
                    }
                    return preferences.save(existing);
                })
                .orElseGet(() -> preferences.save(p));
    }

    @Transactional
    public UserPattern savePattern(String userId, String companionId, UserPattern p) {
        p.setUserId(userId);
        p.setCompanionId(companionId);
        return patterns.findTopByUserIdAndCompanionIdAndPatternAndStatus(
                        userId, companionId, p.getPattern(), "active")
                .map(existing -> {
                    existing.setEvidenceCount(existing.getEvidenceCount() + 1);
                    existing.setConfidence(Math.max(existing.getConfidence(), p.getConfidence()));
                    existing.setLastObservedAt(LocalDateTime.now());
                    if (p.getEvidence() != null && !p.getEvidence().isEmpty()) {
                        List<Object> ev = new ArrayList<>(existing.getEvidence());
                        ev.addAll(p.getEvidence());
                        existing.setEvidence(ev);
                    }
                    return patterns.save(existing);
                })
                .orElseGet(() -> {
                    p.setEvidenceCount(Math.max(1, p.getEvidenceCount()));
                    return patterns.save(p);
                });
    }

    @Transactional
    public UserHypothesis saveHypothesis(String userId, String companionId, UserHypothesis h) {
        h.setUserId(userId);
        h.setCompanionId(companionId);
        return hypotheses.findTopByUserIdAndCompanionIdAndHypothesisAndStatus(
                        userId, companionId, h.getHypothesis(), "active")
                .map(existing -> {
                    existing.setConfidence(Math.max(existing.getConfidence(), h.getConfidence()));
                    if (h.getEvidence() != null && !h.getEvidence().isEmpty()) {
                        List<Object> ev = new ArrayList<>(existing.getEvidence());
                        ev.addAll(h.getEvidence());
                        existing.setEvidence(ev);
                    }
                    return hypotheses.save(existing);
                })
                .orElseGet(() -> hypotheses.save(h));
    }

    /** 用户纠正: 降低相关推测的置信度(设计文档 22 节) */
    @Transactional
    public void correct(String userId, String companionId) {
        for (UserHypothesis h : hypotheses.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")) {
            h.setConfidence(Math.max(0.1, h.getConfidence() - 0.25));
            hypotheses.save(h);
        }
    }

    /** 供 Prompt 注入的高置信用户模型摘要 */
    @Transactional(readOnly = true)
    public UserModelSummary summary(String userId, String companionId) {
        List<String> factLines = facts.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")
                .stream().filter(f -> f.getConfidence() >= 0.7)
                .map(f -> "用户" + zhPredicate(f.getPredicate()) + f.getObject() + "(置信" + (int) (f.getConfidence() * 100) + "%)")
                .limit(12).toList();
        List<String> prefLines = preferences.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")
                .stream().filter(p -> p.getConfidence() >= 0.7)
                .map(p -> p.getPreference() + "(置信" + (int) (p.getConfidence() * 100) + "%)")
                .limit(8).toList();
        List<String> patternLines = patterns.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")
                .stream().filter(p -> p.getConfidence() >= 0.7)
                .map(p -> p.getDescription() != null ? p.getDescription() : p.getPattern())
                .limit(6).toList();
        List<String> hypothesisLines = hypotheses.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")
                .stream().filter(h -> h.getConfidence() >= 0.55)
                .map(h -> h.getDescription() + "(可能是,置信" + (int) (h.getConfidence() * 100) + "%)")
                .limit(6).toList();
        return new UserModelSummary(factLines, prefLines, patternLines, hypothesisLines);
    }

    @Transactional(readOnly = true)
    public List<UserFact> listFacts(String userId, String companionId) {
        return facts.findByUserIdAndCompanionIdAndStatus(userId, companionId, "active");
    }

    @Transactional(readOnly = true)
    public List<UserPreference> listPreferences(String userId, String companionId) {
        return preferences.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active");
    }

    @Transactional(readOnly = true)
    public List<UserPattern> listPatterns(String userId, String companionId) {
        return patterns.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active");
    }

    @Transactional(readOnly = true)
    public List<UserHypothesis> listHypotheses(String userId, String companionId) {
        return hypotheses.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active");
    }

    @Transactional
    public void clearAll(String userId, String companionId) {
        for (UserFact f : facts.findByUserIdAndCompanionIdAndStatus(userId, companionId, "active")) {
            f.setStatus("forgotten");
            facts.save(f);
        }
        for (UserPreference p : preferences.findByUserIdAndCompanionIdAndStatus(userId, companionId, "active")) {
            p.setStatus("forgotten");
            preferences.save(p);
        }
        for (UserPattern p : patterns.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")) {
            p.setStatus("forgotten");
            patterns.save(p);
        }
        for (UserHypothesis h : hypotheses.findByUserIdAndCompanionIdAndStatusOrderByConfidenceDesc(userId, companionId, "active")) {
            h.setStatus("forgotten");
            hypotheses.save(h);
        }
    }

    private static String zhPredicate(String predicate) {
        if (predicate == null) return "";
        switch (predicate) {
            case "likes": return "喜欢";
            case "prefers": return "更喜欢";
            case "works_as": return "工作是";
            case "studies": return "在学";
            case "has": return "拥有";
            case "wants": return "想要";
            case "dislikes": return "不喜欢";
            case "lives_in": return "住在";
            case "works_at": return "在";
            default: return predicate;
        }
    }

    /** 用户模型摘要(用于 Prompt 注入) */
    public record UserModelSummary(List<String> facts, List<String> preferences,
                                   List<String> patterns, List<String> hypotheses) {}
}
