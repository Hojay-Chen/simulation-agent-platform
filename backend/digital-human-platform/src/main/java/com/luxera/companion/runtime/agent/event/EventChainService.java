package com.luxera.companion.runtime.agent.event;

import com.luxera.companion.experience.ExperienceProcessor;
import com.luxera.companion.runtime.WorldEvent;
import com.luxera.companion.runtime.WorldEventLogService;
import com.luxera.companion.runtime.WorldEventType;
import com.luxera.companion.thought.ThoughtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * §21 Event Chain: 事件因果链 + 深度限制。
 * 事件可以继续影响后续状态(出门吃饭→下雨→忘带伞→淋雨→心情下降→回忆→主动告诉用户),
 * 但必须控制 maxDepth = 3, 避免模型生成无限剧情。
 *
 * 每条后果被逐层应用: 记录链上事件 + 产生经历/想法, 供后续主动行为使用。
 */
@Service
public class EventChainService {

    /** 事件链最大深度(§21): 超过则不再延续, 防止无限剧情 */
    public static final int MAX_DEPTH = 3;

    private static final Logger log = LoggerFactory.getLogger(EventChainService.class);

    private final WorldEventLogService worldEventLogService;
    private final ExperienceProcessor experienceProcessor;
    private final ThoughtService thoughtService;

    public EventChainService(WorldEventLogService worldEventLogService,
                             ExperienceProcessor experienceProcessor,
                             ThoughtService thoughtService) {
        this.worldEventLogService = worldEventLogService;
        this.experienceProcessor = experienceProcessor;
        this.thoughtService = thoughtService;
    }

    /**
     * 应用事件的后果链(因果链)。从主事件开始, 逐层应用 consequences, 深度 ≤ MAX_DEPTH。
     *
     * 语义: 事件 → 若干直接后果(同一层, 并行) → 后果的后果(下一层)。
     * 深度 = 链条长度(事件 → 后果 → 后果的后果), 而不是后果数量。
     *
     * @param companionId 伴侣
     * @param eventType   主事件类型
     * @param consequences 主事件的直接后果列表(LLM 提出)
     * @param now         时间
     */
    @Transactional
    public void applyChain(String companionId, String eventType, List<String> consequences, LocalDateTime now) {
        applyLevel(companionId, eventType, consequences, now, 1);
    }

    private void applyLevel(String companionId, String eventType, List<String> consequences,
                            LocalDateTime now, int depth) {
        // 记录当前层事件
        worldEventLogService.record(WorldEvent.of(WorldEventType.WORLD_EVENT_OCCURRED,
                companionId, Map.of("event", eventType, "chainDepth", depth, "at", now.toString())));

        // 当前层影响: 经历 + 可能的想法(供后续主动行为)
        applyImpact(companionId, eventType, now);

        // 深度限制: 达到 MAX_DEPTH 或没有后果 → 停止
        if (depth >= MAX_DEPTH || consequences == null || consequences.isEmpty()) {
            if (depth >= MAX_DEPTH) {
                log.debug("[事件链] {} 达到最大深度 {} 停止", eventType, MAX_DEPTH);
            }
            return;
        }

        // 直接后果: 同一层(深度不变)逐一作为下一层事件记录;
        // 只有当后果自身仍携带"子后果"时, 才进入更深的链(这里后果是文本, 无子后果 → 只记录一层)。
        // 为了让因果链真正体现"逐层影响", 我们把"直接后果"记为下一层(深度+1)。
        for (String consequence : consequences) {
            if (consequence == null || consequence.isBlank()) continue;
            applyLevel(companionId, consequence, null, now, depth + 1);
        }
    }

    /** 当前层事件对内部状态的影响(经历记录 + 想法生成) */
    private void applyImpact(String companionId, String eventType, LocalDateTime now) {
        String title = titleFor(eventType);
        if (title == null) return;
        try {
            experienceProcessor.recordLifeEvent(companionId, title, descriptionFor(eventType), 0.45, 0.35);
        } catch (Exception e) {
            log.warn("[事件链] 经历记录失败: {}", e.getMessage());
        }
        // 负面后果(淋雨/忘记) → 产生情绪化想法(后续可能主动告诉用户)
        if (isEmotionallySignificant(eventType)) {
            try {
                thoughtService.create(companionId,
                        "今天" + title + ",有点" + (isNegative(eventType) ? "低落" : "想跟他分享") + "。",
                        isNegative(eventType) ? "WORRY" : "CURIOSITY",
                        "EVENT", eventType, 0.5, 0.5, 0.3, 0.7, 0.6);
            } catch (Exception e) {
                log.warn("[事件链] 想法创建失败: {}", e.getMessage());
            }
        }
    }

    private static boolean isNegative(String eventType) {
        return eventType != null && (eventType.contains("忘") || eventType.contains("淋")
                || eventType.contains("失败") || eventType.contains("被打断")
                || eventType.contains("低落") || eventType.contains("麻烦"));
    }

    private static boolean isEmotionallySignificant(String eventType) {
        if (eventType == null) return false;
        return eventType.contains("淋") || eventType.contains("忘") || eventType.contains("开心")
                || eventType.contains("好事") || eventType.contains("雨") || eventType.contains("低落");
    }

    private static String titleFor(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case EventSimulationResult.FORGOT_UMBRELLA -> "出门忘带伞";
            case EventSimulationResult.MEET_ACQUAINTANCE -> "路上遇到熟人";
            case EventSimulationResult.SUDDEN_PLAN_CHANGE -> "临时改变计划";
            case EventSimulationResult.WORK_INTERRUPTION -> "工作被打断";
            case EventSimulationResult.GOOD_NEWS -> "遇到一件开心的小事";
            default -> guessTitle(eventType);
        };
    }

    /** 后果描述通常是自然语言(如 "淋了点雨"), 直接用事件类型本身作为标题 */
    private static String guessTitle(String eventType) {
        if (eventType.length() <= 20) return eventType;
        return eventType.substring(0, 20);
    }

    private static String descriptionFor(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case EventSimulationResult.FORGOT_UMBRELLA -> "出门忘带伞,淋了点雨,有点狼狈";
            case EventSimulationResult.MEET_ACQUAINTANCE -> "路上遇到熟人,聊了几句";
            case EventSimulationResult.SUDDEN_PLAN_CHANGE -> "本来安排好的事临时有变,重新调整了";
            case EventSimulationResult.WORK_INTERRUPTION -> "正在忙的时候被打断,多处理了一件小事";
            case EventSimulationResult.GOOD_NEWS -> "遇到一件让人开心的小事";
            default -> eventType;
        };
    }
}
