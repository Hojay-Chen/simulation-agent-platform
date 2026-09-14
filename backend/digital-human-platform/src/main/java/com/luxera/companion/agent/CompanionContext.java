package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.emotion.EmotionalEpisode;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.selfmodel.SelfModel;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.usermodel.UserChatStyle;
import com.luxera.companion.usermodel.UserModelService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一运行时上下文(设计文档 §28): Runtime 用完整系统数据, Prompt 只取需要部分。
 */
public class CompanionContext {

    public final Companion companion;
    public final Persona persona;
    public final com.luxera.companion.life.CompanionLife life;
    public final AgentState state;
    public final CompanionAvailability availability;
    public final List<EmotionalEpisode> emotionalEpisodes;
    public final List<Thought> activeThoughts;
    public final List<OpenLoop> openLoops;
    public final SelfModel selfModel;
    public final UserModelService.UserModelSummary userModel;
    public final UserChatStyle userChatStyle;
    public final List<com.luxera.companion.memory.MemoryEntity> entities;
    public final Relationship relationship;
    public final List<Memory> memories;
    public final List<MessageView> recentMessages;
    public final WorkingMemory.WorkingContext workingMemory;
    public final PerceptionEngine.Perception perception;
    public final String scheduleDesc;
    public final String toolResult;
    public final LocalDateTime now;

    /** V9: 会话滚动摘要(早期事实, 远期摘要化) */
    public final String sessionSummary;
    /** V9: 认知摘要(正关注什么/心里想什么) */
    public final String cognitiveDesc;
    /** V9: 进行中的计划摘要 */
    public final java.util.List<java.util.Map<String, Object>> activePlans;
    /** V9: 用户追问旧计划时的因果解释(可空) */
    public final String planExplain;

    public CompanionContext(Companion companion, Persona persona,
                            com.luxera.companion.life.CompanionLife life, AgentState state,
                            CompanionAvailability availability,
                            List<EmotionalEpisode> emotionalEpisodes, List<Thought> activeThoughts,
                            List<OpenLoop> openLoops, SelfModel selfModel,
                            UserModelService.UserModelSummary userModel, UserChatStyle userChatStyle,
                            List<com.luxera.companion.memory.MemoryEntity> entities,
                            Relationship relationship,
                            List<Memory> memories, List<MessageView> recentMessages,
                            WorkingMemory.WorkingContext workingMemory,
                            PerceptionEngine.Perception perception, String scheduleDesc,
                            String toolResult, LocalDateTime now,
                            String sessionSummary,
                            String cognitiveDesc,
                            java.util.List<java.util.Map<String, Object>> activePlans,
                            String planExplain) {
        this.companion = companion;
        this.persona = persona;
        this.life = life;
        this.state = state;
        this.availability = availability;
        this.emotionalEpisodes = emotionalEpisodes;
        this.activeThoughts = activeThoughts;
        this.openLoops = openLoops;
        this.selfModel = selfModel;
        this.userModel = userModel;
        this.userChatStyle = userChatStyle;
        this.entities = entities;
        this.relationship = relationship;
        this.memories = memories;
        this.recentMessages = recentMessages;
        this.workingMemory = workingMemory;
        this.perception = perception;
        this.scheduleDesc = scheduleDesc;
        this.toolResult = toolResult;
        this.now = now;
        this.sessionSummary = sessionSummary;
        this.cognitiveDesc = cognitiveDesc;
        this.activePlans = activePlans;
        this.planExplain = planExplain;
    }
}

