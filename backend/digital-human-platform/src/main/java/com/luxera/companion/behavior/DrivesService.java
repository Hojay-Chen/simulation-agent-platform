package com.luxera.companion.behavior;

import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.state.CompanionAvailability;
import org.springframework.stereotype.Component;

/**
 * 驱动力计算(§十五/§十六): 根据 Appraisal + 内部状态 + 关系 + 可用状态 + 消息特征,
 * 实时计算各行为倾向强度。确定性 World State(时间/活动/注意力)不交给 LLM。
 * 接收标量(由 InteractionPolicyEngine 从上下文传入), 供评分竞争。
 */
@Component
public class DrivesService {

    public Drives compute(AppraisalService.AppraisalResult appraisal,
                          double energy, double stress, double closeness,
                          double familiarity, double intimacy,
                          CompanionAvailability availability, String text, double messageLength) {
        double warmth = appraisal != null ? appraisal.warmth() : 0;
        double hurt = appraisal != null ? appraisal.hurt() : 0;
        double anger = appraisal != null ? appraisal.anger() : 0;
        double urgency = appraisal != null ? appraisal.urgency() : 0;
        double relImpact = appraisal != null ? appraisal.relationshipImpact() : 0;

        // desire_to_reply: 温暖/亲密/紧迫/求助 → 高; 太长太琐碎 → 略低
        double reply = 0.25
                + warmth * 0.5
                + urgency * 0.5
                + closeness * 0.2
                + familiarity * 0.15
                + (relImpact > 0 ? relImpact * 0.4 : 0);
        if (messageLength <= 2) reply -= 0.25;   // 琐碎
        if (messageLength > 100) reply += 0.1;   // 长篇倾诉值得回

        // desire_to_avoid: 受伤/生气/高压/低电量 → 高(想躲开)
        double avoid = hurt * 0.7 + anger * 0.8 + stress * 0.3 + (1 - energy) * 0.25;

        // desire_to_share: 温暖/亲密/分享欲(她也有想说的)
        double share = warmth * 0.4 + closeness * 0.3 + intimacy * 0.3;

        // desire_to_reconnect: 冲突后想找回来(避免伤害关系)
        double reconnect = (hurt + anger) > 0.5 ? 0.25 : 0.1;

        // desire_to_rest: 低电量/高压力 → 想休息(导致更不想回)
        double rest = (1 - energy) * 0.4 + stress * 0.2;

        // Availability 修正: 忙/休息/睡觉 → 回复欲降, 回避欲升
        if (availability != null) {
            switch (availability) {
                case BUSY -> { reply -= 0.3; rest += 0.2; }
                case RESTING -> { reply -= 0.2; rest += 0.3; }
                case DISTRACTED -> reply -= 0.15;
                case SLEEPING -> { reply -= 0.5; avoid += 0.3; }
                case SOCIALIZING, TRAVELING -> reply -= 0.2;
                default -> { }
            }
        }

        return new Drives(
                clamp(reply), clamp(avoid), clamp(share), clamp(reconnect), clamp(rest));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
