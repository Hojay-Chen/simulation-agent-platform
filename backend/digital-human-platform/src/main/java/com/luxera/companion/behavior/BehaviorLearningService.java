package com.luxera.companion.behavior;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * §45/§46 Behavior Pattern 学习服务: 从真实互动中观测行为模式。
 * 每次用户消息到达时, 根据时间/情境观测一次模式(支持或反例)。
 *
 * 例如:
 * - 晚上(22点后)发消息 → 观测 night_late_reply(她深夜回复慢)
 * - 工作时段发消息 → 观测 work_hours_low_response
 * - 用户分享好事 → 观测 positive_emotion_proactive(她更愿意主动分享)
 */
@Service
public class BehaviorLearningService {

    private final BehaviorPatternService patternService;

    public BehaviorLearningService(BehaviorPatternService patternService) {
        this.patternService = patternService;
    }

    /** 用户消息到达时观测行为模式 */
    public void onUserMessage(String companionId, LocalDateTime now, String emotion) {
        int hour = now.getHour();

        // 深夜(22-6点)用户发消息 → 支持"深夜回复慢"模式(她多半睡着了/在休息)
        boolean night = hour >= 22 || hour < 6;
        patternService.observe(companionId, BehaviorPatternService.NIGHT_LATE_REPLY,
                "深夜收到消息时, 她通常不会立即回复, 多半第二天才回",
                "reduce_response", night);

        // 工作时段(9-12, 14-18 周内)用户发消息 → 支持"工作时回复慢"
        boolean workHours = !isWeekend(now) && ((hour >= 9 && hour < 12) || (hour >= 14 && hour < 18));
        patternService.observe(companionId, BehaviorPatternService.WORK_HOURS_LOW_RESPONSE,
                "工作时段收到消息时, 她通常先忙完手头的事再回复",
                "reduce_response", workHours);

        // 用户分享正面情绪 → 支持"她更容易主动分享"
        boolean positive = emotion != null && List.of("happy", "excited", "joy", "grateful")
                .contains(emotion.toLowerCase());
        patternService.observe(companionId, BehaviorPatternService.POSITIVE_EMOTION_PROACTIVE,
                "用户开心时, 她更愿意主动分享和接话",
                "boost_proactive", positive);
    }

    private static boolean isWeekend(LocalDateTime now) {
        return now.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || now.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
    }
}
