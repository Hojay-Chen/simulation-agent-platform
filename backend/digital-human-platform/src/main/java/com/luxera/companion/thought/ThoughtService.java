package com.luxera.companion.thought;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ThoughtService {

    private final ThoughtRepository repo;
    private final ThoughtScorer scorer;

    public ThoughtService(ThoughtRepository repo, ThoughtScorer scorer) {
        this.repo = repo;
        this.scorer = scorer;
    }

    @Transactional
    public Thought create(String companionId, String content, String type, String triggerType, String triggerRef,
                          double importance, double emotionalWeight, double relationshipWeight,
                          double timeliness, double confidence) {
        Thought t = new Thought();
        t.setCompanionId(companionId);
        t.setContent(content);
        t.setType(type);
        t.setTriggerType(triggerType);
        t.setTriggerRef(triggerRef);
        t.setImportance(clamp(importance));
        t.setEmotionalWeight(clamp(emotionalWeight));
        t.setRelationshipWeight(clamp(relationshipWeight));
        t.setConfidence(clamp(confidence));
        t.setStrength(scorer.score(t.getImportance(), t.getEmotionalWeight(), t.getRelationshipWeight(),
                clamp(timeliness)));
        t.setExpiresAt(LocalDateTime.now().plusHours(72));
        t.setStatus("ACTIVE");
        return repo.save(t);
    }

    /**
     * §31 Unfinished Thought: 创建"想说但没说完 / 想问但忘了 / 回复被打断"的未完成想法。
     * 这类想法不会自动展示, 而是被 Runtime 在未来重新激活(由激活 Job 决定时机)。
     *
     * @param priority   想法优先级 0-1(决定是否值得在未来重新激活)
     * @param expiresAt  过期时间(超过则遗忘)
     */
    @Transactional
    public Thought createUnfinished(String companionId, String content, String triggerType, String triggerRef,
                                    double priority, LocalDateTime expiresAt) {
        Thought t = new Thought();
        t.setCompanionId(companionId);
        t.setContent(content);
        t.setType("UNFINISHED");
        t.setTriggerType(triggerType);
        t.setTriggerRef(triggerRef);
        t.setImportance(clamp(priority));
        t.setEmotionalWeight(clamp(priority));
        t.setRelationshipWeight(clamp(priority));
        t.setConfidence(clamp(priority));
        // 未完成想法的强度由优先级 + 时效性决定(刚发生时最强烈, 随时间衰减)
        double timeliness = expiresAt == null ? 1.0 : clamp01(
                java.time.Duration.between(LocalDateTime.now(), expiresAt).toHours() / 72.0);
        t.setStrength(scorer.score(t.getImportance(), t.getEmotionalWeight(), t.getRelationshipWeight(),
                Math.max(0.1, timeliness)));
        t.setExpiresAt(expiresAt != null ? expiresAt : LocalDateTime.now().plusHours(24));
        t.setStatus("ACTIVE");
        return repo.save(t);
    }

    /**
     * §31 激活未完成想法: 冷却期过后 + 优先级达标时, 把 ACTIVE 的 UNFINISHED 想法提升为
     * 可被 ProactiveEngine 采样的强度(通过 boost strength)。由激活 Job 调用。
     * 激活门槛用优先级(importance)而非 strength —— 因为 strength 受时效衰减影响,
     * 而"是否值得回来补一句"主要由想法本身的重要性决定。
     *
     * @return 被重新激活的想法
     */
    @Transactional
    public List<Thought> activateUnfinished(String companionId, LocalDateTime now) {
        return activateUnfinished(companionId, now, 30);
    }

    /** 可指定冷却时间(分钟): 0 表示创建后立即可激活(测试/模拟用) */
    @Transactional
    public List<Thought> activateUnfinished(String companionId, LocalDateTime now, int cooldownMinutes) {
        List<Thought> reactivated = new java.util.ArrayList<>();
        for (Thought t : repo.findByCompanionIdAndStatusOrderByCreatedAtDesc(companionId, "ACTIVE")) {
            if (!"UNFINISHED".equals(t.getType())) continue;
            if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(now)) {
                // 过期 → 遗忘(真人确实会忘)
                t.setStatus("EXPIRED");
                repo.save(t);
                continue;
            }
            // 激活窗口: 创建后 ≥ cooldownMinutes(给"被打断"冷却时间) 且 优先级足够
            long minsSinceCreated = java.time.Duration.between(t.getCreatedAt(), now).toMinutes();
            if (minsSinceCreated >= cooldownMinutes && t.getImportance() >= 0.5) {
                // 提升强度, 让 ProactiveEngine 更可能采样到
                t.setStrength(Math.min(1.0, t.getStrength() + 0.2));
                t.setStatus("ACTIVE");
                repo.save(t);
                reactivated.add(t);
            }
        }
        return reactivated;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Transactional(readOnly = true)
    public List<Thought> activeThoughts(String companionId) {
        return repo.findByCompanionIdAndStatusInOrderByStrengthDesc(companionId, List.of("ACTIVE", "SUPPRESSED"));
    }

    @Transactional
    public void suppress(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("SUPPRESSED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    @Transactional
    public void act(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("ACTED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    @Transactional
    public void resolve(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("RESOLVED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
