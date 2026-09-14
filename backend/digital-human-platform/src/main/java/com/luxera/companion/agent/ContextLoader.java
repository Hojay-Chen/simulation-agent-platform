package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.life.CompanionLifeService;
import com.luxera.companion.life.LifeContextProvider;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.selfmodel.SelfModelService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.thought.ThoughtService;
import com.luxera.companion.emotion.EmotionService;
import com.luxera.companion.usermodel.UserChatStyleService;
import com.luxera.companion.usermodel.UserModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 上下文加载器(设计文档 §28/§29): 加载 Runtime 完整上下文 */
@Component
public class ContextLoader {

    private final CompanionService companionService;
    private final CompanionLifeService lifeService;
    private final AgentStateService agentStateService;
    private final AvailabilityService availabilityService;
    private final EmotionService emotionService;
    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;
    private final SelfModelService selfModelService;
    private final UserModelService userModelService;
    private final UserChatStyleService userChatStyleService;
    private final com.luxera.companion.memory.MemoryEntityService entityService;
    private final RelationshipService relationshipService;
    private final MemoryService memoryService;
    private final WorkingMemory workingMemory;
    private final LifeContextProvider lifeContextProvider;
    private final CompanionSchedule schedule;
    private final com.luxera.companion.config.AppProperties props;
    private final com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService;
    private final com.luxera.companion.plan.PlanService planService;
    private final com.luxera.companion.digitalhuman.summary.SessionSummaryRepository sessionSummaryRepository;

    public ContextLoader(CompanionService companionService, CompanionLifeService lifeService,
                         AgentStateService agentStateService, AvailabilityService availabilityService,
                         EmotionService emotionService, ThoughtService thoughtService,
                         OpenLoopService openLoopService, SelfModelService selfModelService,
                         UserModelService userModelService, UserChatStyleService userChatStyleService,
                         com.luxera.companion.memory.MemoryEntityService entityService,
                         RelationshipService relationshipService, MemoryService memoryService,
                         WorkingMemory workingMemory, LifeContextProvider lifeContextProvider,
                         CompanionSchedule schedule, com.luxera.companion.config.AppProperties props,
                         com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService,
                         com.luxera.companion.plan.PlanService planService,
                         com.luxera.companion.digitalhuman.summary.SessionSummaryRepository sessionSummaryRepository) {
        this.companionService = companionService;
        this.lifeService = lifeService;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.emotionService = emotionService;
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
        this.selfModelService = selfModelService;
        this.userModelService = userModelService;
        this.userChatStyleService = userChatStyleService;
        this.entityService = entityService;
        this.relationshipService = relationshipService;
        this.memoryService = memoryService;
        this.workingMemory = workingMemory;
        this.lifeContextProvider = lifeContextProvider;
        this.schedule = schedule;
        this.props = props;
        this.cognitiveSessionService = cognitiveSessionService;
        this.planService = planService;
        this.sessionSummaryRepository = sessionSummaryRepository;
    }

    public CompanionContext load(String userId, String companionId, String conversationId,
                                 List<MessageView> recentMessages,
                                 PerceptionEngine.Perception perception, String toolResult) {
        return load(userId, companionId, conversationId, recentMessages, perception, toolResult, true);
    }

    /** V9 §9/§14: 分级检索 —— deep=false(FAST 路径)跳过记忆/用户模型/实体的重检索, 降低延迟与成本 */
    public CompanionContext load(String userId, String companionId, String conversationId,
                                 List<MessageView> recentMessages,
                                 PerceptionEngine.Perception perception, String toolResult,
                                 boolean deep) {
        var companion = companionService.requireOwned(userId, companionId);
        var persona = companionService.getPersona(companionId);
        var life = lifeService.getOrCreate(companionId);
        var state = agentStateService.get(companionId);
        LocalDateTime now = LocalDateTime.now();
        var availability = availabilityService.current(companionId, now, state);
        var episodes = emotionService.activeEpisodes(companionId);
        var thoughts = thoughtService.activeThoughts(companionId);
        var loops = openLoopService.activeLoops(companionId);
        var selfModel = selfModelService.get(companionId);
        // V9: FAST 路径只加载轻量上下文(关系/风格), 不检索记忆/用户模型/实体
        var userModel = deep ? userModelService.summary(userId, companionId) : null;
        var userChatStyle = userChatStyleService.get(companionId);
        var entities = deep ? entityService.recent(userId, companionId, 8)
                : java.util.List.<com.luxera.companion.memory.MemoryEntity>of();
        var relationship = relationshipService.find(userId, companionId);
        String query = recentMessages.isEmpty() ? "" : recentMessages.get(recentMessages.size() - 1).getContent();
        var memories = deep
                ? memoryService.retrieve(userId, companionId, query, props.getAgent().getMemoryTopN())
                : java.util.List.<Memory>of();
        var wm = conversationId != null ? workingMemory.get(companionId, conversationId) : null;
        String scheduleDesc = schedule.describe(companionId, companion.getName(), now);

        // V9: 认知摘要 + 进行中的计划 + 用户追问旧计划时的因果解释(Reality Ledger)
        String cognitiveDesc = null;
        try {
            cognitiveDesc = cognitiveSessionService.describe(companionId);
        } catch (Exception ignored) { }
        java.util.List<java.util.Map<String, Object>> activePlans = null;
        try {
            activePlans = planService.planBriefs(companionId);
        } catch (Exception ignored) { }
        String planExplain = null;
        try {
            if (asksAboutPlan(query)) {
                planExplain = planService.recentExplanation(companionId, now);
            }
        } catch (Exception ignored) { }

        // V9 §20: 会话滚动摘要(早期事实)
        String sessionSummary = null;
        try {
            if (conversationId != null) {
                var summary = sessionSummaryRepository.findByConversationId(conversationId).orElse(null);
                if (summary != null && summary.getSummaryText() != null && !summary.getSummaryText().isBlank()) {
                    sessionSummary = summary.getSummaryText();
                }
            }
        } catch (Exception ignored) { }

        return new CompanionContext(companion, persona, life, state, availability, episodes, thoughts, loops,
                selfModel, userModel, userChatStyle, entities, relationship, memories, recentMessages, wm, perception,
                scheduleDesc, toolResult, now, sessionSummary, cognitiveDesc, activePlans, planExplain);
    }

    /** 用户消息是否在追问旧计划("你不是说…吗/不是要…吗/说好的…呢") */
    private static boolean asksAboutPlan(String query) {
        if (query == null || query.isBlank()) return false;
        return query.contains("不是说") || query.contains("不是要") || query.contains("说好")
                || query.contains("答应") || query.contains("计划") || query.contains("打算")
                || query.contains("怎么没") || query.contains("不是说好");
    }

    /** 加载学习上下文(设计文档 §29): 供反思/记忆/人格学习使用 */
    public LearningContext loadLearning(String userId, String companionId, List<String> recentExperienceSummary) {
        var companion = companionService.requireOwned(userId, companionId);
        var userModel = userModelService.summary(userId, companionId);
        String lifeSummary = lifeContextProvider.describeToday(companionId, companion.getName());
        return new LearningContext(companionId, companion.getName(), recentExperienceSummary,
                userModel, lifeSummary, LocalDateTime.now());
    }
}
