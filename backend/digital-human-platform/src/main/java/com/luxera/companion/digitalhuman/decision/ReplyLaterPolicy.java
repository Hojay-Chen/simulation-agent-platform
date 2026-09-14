package com.luxera.companion.digitalhuman.decision;

import com.luxera.companion.digitalhuman.perception.PerceptionLevel;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * ReplyLaterPolicy: 延迟回复 —— 看到了但暂时不回(V10 MVP 验收 8)。
 *
 * 情境: 感知到但正在忙/疲惫, 或重要但不到立即回复的程度。
 * 真人会说"等会儿回你" —— 延迟而非忽略, 之后由复查机制补回。
 */
@Component
@Order(2)
public class ReplyLaterPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "reply-later";
    }

    @Override
    public boolean supports(DecisionContext context) {
        if (context.perceptionLevel() != PerceptionLevel.FOCUSED) {
            return false;
        }
        // 紧急事件(importance >= 0.8, 如催问/情绪强烈)即使忙也立即回;
        // 忙(高注意力活动)或疲惫 → 先记下, 稍后回
        return context.importance() < 0.8
                && ((context.life() != null && context.life().isBusy())
                    || (context.mind() != null && context.mind().exhausted()));
    }

    @Override
    public PersonDecision decide(DecisionContext context) {
        // 延迟时长: 重要 → 30min; 普通 → 60~120min
        int delay = context.importance() >= 0.5 ? 30 : 90;
        String reason = context.life() != null && context.life().isBusy()
                ? "正在忙手头的事, 等忙完再回"
                : "现在没精力, 等缓过来再回";
        return new PersonDecision.DelayReplyDecision(reason, delay);
    }
}
