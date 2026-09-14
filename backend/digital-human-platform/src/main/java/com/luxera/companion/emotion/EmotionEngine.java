package com.luxera.companion.emotion;

import com.luxera.companion.state.AgentStateService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 情绪引擎(设计文档 §7): 由事件/对话推导 EmotionalEpisode, 并影响 AgentState 与行为倾向。
 * 不允许随机制造情绪 —— 情绪必须来自 State/Emotion/Persona/Relationship/Context。
 */
@Component
public class EmotionEngine {

    private final EmotionService emotionService;
    private final AgentStateService agentStateService;

    public EmotionEngine(EmotionService emotionService, AgentStateService agentStateService) {
        this.emotionService = emotionService;
        this.agentStateService = agentStateService;
    }

    /** 从一次对话推导情绪事件(在异步后处理中调用) */
    @Transactional
    public void fromConversation(String companionId, String emotion, String intent, String userText) {
        if (emotion == null || "neutral".equals(emotion)) return;
        double intensity = intensityFor(emotion, intent);
        if (intensity < 0.45) return;

        // 短窗口内同情绪不重复建 episode
        List<EmotionalEpisode> active = emotionService.activeEpisodes(companionId);
        boolean dup = active.stream().anyMatch(e -> e.getEmotion().equals(emotion)
                && e.getStartedAt().isAfter(LocalDateTime.now().minusHours(2)));
        if (dup) return;

        String cause = userText != null && userText.length() > 40 ? userText.substring(0, 40) : (userText == null ? emotion : userText);
        EmotionalEpisode ep = emotionService.record(companionId, cause, emotion, intensity, cause,
                thoughtFor(emotion), behaviorTendencyFor(emotion), "conversation", null);
        agentStateService.applyEvent(companionId, "negative".equals(episodeKind(emotion)) ? "negative" : "positive", intensity * 0.4);
    }

    private static double intensityFor(String emotion, String intent) {
        return switch (emotion) {
            case "sad", "angry", "anxious", "lonely" -> 0.7;
            case "tired", "frustrated" -> 0.55;
            case "happy", "grateful", "excited" -> 0.65;
            default -> 0.4;
        };
    }

    private static String episodeKind(String emotion) {
        return switch (emotion) {
            case "happy", "grateful", "excited" -> "positive";
            default -> "negative";
        };
    }

    private static String thoughtFor(String emotion) {
        return switch (emotion) {
            case "sad" -> "心里有点难过";
            case "tired" -> "今天有点累了";
            case "anxious" -> "有点担心";
            case "angry" -> "有点气不过";
            case "lonely" -> "有点孤单";
            case "happy" -> "今天心情不错";
            default -> "心情有些起伏";
        };
    }

    private static String behaviorTendencyFor(String emotion) {
        return switch (emotion) {
            case "sad", "tired", "anxious", "lonely" -> "verbosity_down,initiative_down,humor_down,social_energy_down";
            case "happy", "grateful" -> "humor_up,initiative_up,verbosity_up";
            default -> "";
        };
    }
}
