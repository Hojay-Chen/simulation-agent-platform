package com.luxera.companion.intention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * §35-§36 Intention Runtime: 意图记忆生命周期。
 * - 创建意图("想告诉他一件事" / "该回复他了")
 * - 激活概率随时间演化(到 expected_time 附近升高, 过期衰减)
 * - 过期 → 遗忘(FORGOTTEN), 真人会忘
 * - 执行 → ACTED
 *
 * 关键: "忙完忘了回复"不是 bug, 而是"意图被其他事打断 → 激活概率下降 → 后来突然想起"。
 */
@Service
public class IntentionService {

    private final IntentionRepository repo;

    public IntentionService(IntentionRepository repo) {
        this.repo = repo;
    }

    /** 创建意图 */
    @Transactional
    public Intention create(String companionId, String userId, String content,
                            double importance, String emotion, String target,
                            LocalDateTime expectedTime, int validHours) {
        Intention i = new Intention();
        i.setCompanionId(companionId);
        i.setUserId(userId);
        i.setContent(content);
        i.setImportance(clamp(importance));
        i.setEmotion(emotion);
        i.setTarget(target == null ? "user" : target);
        i.setExpectedTime(expectedTime);
        i.setExpiryTime(expectedTime != null ? expectedTime.plusHours(validHours)
                : LocalDateTime.now().plusHours(validHours));
        i.setActivationProbability(clamp(0.4 + importance * 0.3));
        i.setStatus("ACTIVE");
        return repo.save(i);
    }

    /**
     * 更新激活概率: 随时间演化。
     * - 未到 expected_time: 越接近越高
     * - 已过 expected_time: 逐渐衰减(但要执行)
     * - 超过 expiry_time: 遗忘(FORGOTTEN)
     */
    @Transactional
    public List<Intention> decay(String companionId, LocalDateTime now) {
        List<Intention> actives = repo.findByCompanionIdAndStatusIn(companionId, List.of("ACTIVE"));
        for (Intention i : actives) {
            if (i.getExpiryTime() != null && i.getExpiryTime().isBefore(now)) {
                i.setStatus("FORGOTTEN");   // 真人会忘
                repo.save(i);
                continue;
            }
            double prob = evolveProbability(i, now);
            i.setActivationProbability(prob);
            repo.save(i);
        }
        return actives;
    }

    /** 意图激活概率演化(0-1) */
    private static double evolveProbability(Intention i, LocalDateTime now) {
        if (i.getExpectedTime() == null) {
            // 无明确时间: 基础概率随时间轻微衰减
            return clamp(i.getActivationProbability() - 0.002);
        }
        long minsToExpected = java.time.Duration.between(now, i.getExpectedTime()).toMinutes();
        if (minsToExpected > 0) {
            // 还没到点: 越接近越高(120 分钟内线性升)
            double approach = Math.max(0, Math.min(1, 1 - minsToExpected / 120.0));
            return clamp(0.3 + approach * 0.4 + i.getImportance() * 0.3);
        }
        // 已过点: 刚过时最高, 之后衰减(但意图仍可能被想起)
        long minsAfter = -minsToExpected;
        double decay = Math.max(0, 1 - minsAfter / (24 * 60.0));
        return clamp(0.5 + i.getImportance() * 0.3 + decay * 0.2);
    }

    /** 标记已执行 */
    @Transactional
    public void markActed(String intentionId) {
        repo.findById(intentionId).ifPresent(i -> {
            i.setStatus("ACTED");
            i.setActivationProbability(0);
            repo.save(i);
        });
    }

    /** 当前活跃的高概率意图(≥ 阈值, 可能被想起) */
    @Transactional(readOnly = true)
    public List<Intention> activatable(String companionId, double threshold) {
        return repo.findByCompanionIdAndStatusIn(companionId, List.of("ACTIVE")).stream()
                .filter(i -> i.getActivationProbability() >= threshold)
                .toList();
    }

    /** 标记"突然想起某意图"(激活概率提升, 供 Proactive 使用) */
    @Transactional
    public Intention remind(String intentionId) {
        Intention i = repo.findById(intentionId).orElse(null);
        if (i == null) return null;
        i.setActivationProbability(Math.min(1.0, i.getActivationProbability() + 0.3));
        return repo.save(i);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
