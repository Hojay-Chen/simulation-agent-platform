package com.luxera.companion.behavior;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * §45/§46 Behavior Pattern 服务: 行为模式学习。
 * 人物会逐渐形成习惯(用户晚上发消息→第二天回复, 工作时→不爱看手机)。
 * 这些模式随互动更新, 置信度随观察次数增长。
 *
 * 不迎合(§47): 模式只是"行为倾向影响", 不是硬约束 —— Brain 仍可打破习惯。
 */
@Service
public class BehaviorPatternService {

    /** 内置模式 */
    public static final String WORK_HOURS_LOW_RESPONSE = "work_hours_low_response";
    public static final String NIGHT_LATE_REPLY = "night_late_reply";
    public static final String POSITIVE_EMOTION_PROACTIVE = "positive_emotion_proactive";

    private final BehaviorPatternRepository repo;

    public BehaviorPatternService(BehaviorPatternRepository repo) {
        this.repo = repo;
    }

    /**
     * 观测一次行为, 更新/创建模式。
     *
     * @param pattern     模式名(见常量)
     * @param description 描述
     * @param influence   影响方向(boost/reduce response/proactive)
     * @param supported   本次观测是否支持该模式(true=支持, false=反例)
     */
    @Transactional
    public BehaviorPattern observe(String companionId, String pattern, String description,
                                   String influence, boolean supported) {
        BehaviorPattern p = repo.findByCompanionIdAndPattern(companionId, pattern).orElseGet(() -> {
            BehaviorPattern np = new BehaviorPattern();
            np.setCompanionId(companionId);
            np.setPattern(pattern);
            np.setDescription(description);
            np.setInfluence(influence);
            np.setConfidence(0.5);
            np.setObservations(0);
            np.setStrength(0.4);
            return np;
        });

        p.setObservations(p.getObservations() + 1);
        // 置信度随观察次数增长(渐进逼近 1, 反例则下降)
        double step = 1.0 / (p.getObservations() + 3);
        p.setConfidence(clamp(p.getConfidence() + (supported ? step : -step)));
        // 强度 = 置信度 × 观察饱和度
        p.setStrength(clamp(p.getConfidence() * saturation(p.getObservations())));
        p.setLastObservedAt(LocalDateTime.now());
        return repo.save(p);
    }

    @Transactional(readOnly = true)
    public List<BehaviorPattern> patterns(String companionId) {
        return repo.findByCompanionIdOrderByStrengthDesc(companionId);
    }

    @Transactional(readOnly = true)
    public BehaviorPattern get(String companionId, String pattern) {
        return repo.findByCompanionIdAndPattern(companionId, pattern).orElse(null);
    }

    /** 观察饱和: 越到后面, 新增观察对模式的影响越小 */
    private static double saturation(int observations) {
        return Math.min(1, observations / 20.0);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
