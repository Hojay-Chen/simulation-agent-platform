package com.luxera.companion.experience;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 经历处理器: 统一把对话/生活/情绪/想法写成 Experience(设计文档 §11.1) */
@Component
public class ExperienceProcessor {

    private final ExperienceService experienceService;
    private final MemoryConsolidator consolidator;

    public ExperienceProcessor(ExperienceService experienceService, MemoryConsolidator consolidator) {
        this.experienceService = experienceService;
        this.consolidator = consolidator;
    }

    /** 记录一次对话交流为经历(聊天后异步调用) */
    @Transactional
    public Experience recordConversationExchange(String companionId, String conversationId,
                                                 String userText, String reply,
                                                 double importance, double emotionalWeight,
                                                 double relationshipWeight) {
        String content = "用户说: " + userText + "\n伴侣回: " + reply;
        return experienceService.record(companionId, "CONVERSATION", "conversation", conversationId,
                content, userText.length() > 80 ? userText.substring(0, 80) : userText,
                importance, emotionalWeight, relationshipWeight, LocalDateTime.now());
    }

    /** 记录一条生活事件为经历 */
    @Transactional
    public Experience recordLifeEvent(String companionId, String title, String description,
                                      double importance, double emotionalWeight) {
        return experienceService.record(companionId, "LIFE_EVENT", "life", null,
                title + (description != null ? " - " + description : ""), title,
                importance, emotionalWeight, 0.3, LocalDateTime.now());
    }

    /** 记录一条情绪事件为经历 */
    @Transactional
    public Experience recordEmotionalEvent(String companionId, String trigger, String emotion,
                                           double intensity, String cause) {
        return experienceService.record(companionId, "EMOTIONAL_EVENT", "emotion", null,
                trigger + " → " + emotion, cause != null ? cause : trigger,
                0.5, clamp(intensity), 0.4, LocalDateTime.now());
    }

    @Transactional
    public int consolidate(String companionId) {
        return consolidator.consolidate(companionId);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
