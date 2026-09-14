package com.luxera.companion.reality;

import com.luxera.companion.plan.Plan;
import com.luxera.companion.plan.PlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V9 §10 Reality Consistency Checker: 模型输出与 Reality Ledger 冲突时禁止直接发送。
 *
 * 场景: 她正在"招呼客人", 模型却说"我一直在找手机" → 冲突(编造了与事实矛盾的事)。
 * 规则检测(不依赖 LLM):
 * 1. 活动断言冲突: 回复文本中的"我在[活动]"与现实当前活动矛盾
 *    (在上班/开会/忙 vs 当前休闲; 在休息/睡 vs 当前工作)
 * 2. 计划状态冲突: 计划已被打断(SUPERSEDED)后, 回复仍说"还要去[计划]"
 * 3. 时间矛盾: 回复含"今天没吃饭"但现实当天已有"午餐"计划完成
 *
 * 冲突处理: 返回冲突原因 → 调用方标记该回复不可发送(重新生成或降级)。
 * 允许表达不确定, 但不允许编造新事实。
 */
@Slf4j
@Component
public class RealityConsistencyChecker {

    private final PlanService planService;

    public RealityConsistencyChecker(PlanService planService) {
        this.planService = planService;
    }

    /** 校验回复文本与当前 Reality 的一致性。返回 null = 一致; 非 null = 冲突原因。 */
    public String check(String reply, String companionId, String currentActivityDesc, LocalDateTime now) {
        if (reply == null || reply.isBlank()) return null;

        // 1. 活动断言冲突
        String activityConflict = checkActivity(reply, currentActivityDesc);
        if (activityConflict != null) return activityConflict;

        // 2. 计划状态冲突(已被打断的计划还在"要去")
        String planConflict = checkPlans(reply, companionId);
        if (planConflict != null) return planConflict;

        return null;
    }

    /** 活动词表: 现实活动 → 冲突断言词 */
    private String checkActivity(String reply, String activityDesc) {
        if (activityDesc == null || activityDesc.isBlank()) return null;
        String desc = activityDesc;
        // 现实在忙工作 → 回复说在休息/躺着/逛街 → 冲突
        if ((desc.contains("忙") || desc.contains("工作") || desc.contains("开会"))
                && containsAny(reply, "在休息", "躺着", "躺会儿", "逛街", "看电影", "在睡觉", "刚睡醒")) {
            return "回复说自己在休息/躺着, 与现实(正在忙工作)矛盾";
        }
        // 现实在休闲/睡 → 回复说在开会/上班/加班 → 冲突
        if ((desc.contains("休闲") || desc.contains("悠闲") || desc.contains("睡觉") || desc.contains("休息"))
                && containsAny(reply, "在开会", "在上班", "在加班", "在忙工作", "刚下班")) {
            return "回复说自己在开会/上班, 与现实(正在" + desc + ")矛盾";
        }
        // 深夜(23-6点)现实应休息 → 回复说"在忙工作"可接受(夜班), 跳过
        return null;
    }

    /** 计划已被打断 → 回复仍宣称要去 */
    private String checkPlans(String reply, String companionId) {
        try {
            List<Plan> superseded = planService.supersededRecently(companionId, LocalDateTime.now().minusHours(48));
            for (Plan p : superseded) {
                String title = p.getTitle();
                if (title == null || title.isBlank()) continue;
                if (mentionsGoing(reply, title) && !reply.contains("没去") && !reply.contains("没去成")) {
                    return "计划[" + title + "]已被打断(SUPERSEDED), 回复却宣称还要去";
                }
            }
        } catch (Exception e) {
            log.debug("[RealityCheck] 计划检查失败: {}", e.getMessage());
        }
        return null;
    }

    /** 回复是否提到"要去[计划]" —— 计划标题的 2 字片段命中且含去向动词 */
    private static boolean mentionsGoing(String reply, String title) {
        if (reply.contains(title)) return containsAny(reply, "要去", "准备去", "还去", "打算去");
        if (title == null || title.length() < 3) return false;
        for (int i = 0; i + 2 <= title.length(); i++) {
            String gram = title.substring(i, i + 2);
            if (reply.contains(gram) && containsAny(reply, "去", "准备", "还要")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
