package com.luxera.companion.relationship;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class RelationshipService {

    private final RelationshipRepository repo;

    public RelationshipService(RelationshipRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Relationship require(String userId, String companionId) {
        return repo.findByUserIdAndCompanionId(userId, companionId)
                .orElseThrow(() -> new EntityNotFoundException("关系不存在"));
    }

    @Transactional(readOnly = true)
    public Relationship find(String userId, String companionId) {
        return repo.findByUserIdAndCompanionId(userId, companionId).orElse(null);
    }

    /** 创建伴侣时按关系类型初始化维度(真实关系状态, 非 Prompt) */
    @Transactional
    public void initByType(Relationship r, String type) {
        RelationshipTypes.applyInitial(r, type);
        repo.save(r);
    }

    /** Appraisal: 微调信任/亲密度(不越过边界) */
    @Transactional
    public void updateMetrics(String userId, String companionId, double trustDelta, double intimacyDelta) {
        repo.findByUserIdAndCompanionId(userId, companionId).ifPresent(r -> {
            r.setTrust(clamp(r.getTrust() + trustDelta));
            r.setIntimacy(clamp(r.getIntimacy() + intimacyDelta));
            repo.save(r);
        });
    }

    /**
     * §39: 关系维护压力。沉默越久, connectionPressure 越高(驱动主动联系);
     * 互动后由 {@link com.luxera.companion.relationship.RelationshipEngine#onMessage} 归零。
     * 亲密关系压力增长更快(更想念), 但"刚认识"增长更慢(不打扰)。
     */
    @Transactional
    public void decayConnectionPressure(String userId, String companionId, LocalDateTime now) {
        repo.findByUserIdAndCompanionId(userId, companionId).ifPresent(r -> {
            LocalDateTime last = r.getLastInteractionAt();
            if (last == null) return;
            long hours = Duration.between(last, now).toHours();
            if (hours <= 0) return;
            // 压力: 0 → 1, 时间常数按关系亲密程度(亲密 ~36h, 普通 ~96h)
            double tauHours = 96 - (r.getIntimacy() + r.getAffection()) * 0.5 * 120;
            tauHours = Math.max(24, Math.min(120, tauHours));
            double target = 1 - Math.exp(-hours / tauHours);
            if (target > r.getConnectionPressure()) {
                r.setConnectionPressure(clamp(target));
                repo.save(r);
            }
        });
    }

    /** 用户可调整关系类型(关系状态真实变更) */
    @Transactional
    public Relationship changeType(String userId, String companionId, String type) {
        Relationship r = require(userId, companionId);
        if (RelationshipTypes.isValid(type)) {
            RelationshipTypes.applyInitial(r, type);
        } else {
            r.setRelationshipType(type);
        }
        return repo.save(r);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
