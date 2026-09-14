package com.luxera.companion.appraisal;

import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 消息评估 Runtime(§十/§十一/§十三): 消息被读到后, 先判断"这对我意味着什么", 改变 Agent 内部状态。
 * 零额外 LLM 调用 —— 基于现有感知(意图/情绪) + 关键词规则扩展。
 * 产出 message_appraisals 记录 + 更新 AgentState(hurt/anger/emotionalCloseness) + 微调关系。
 */
@Slf4j
@Service
public class AppraisalService {

    private static final Pattern APOLOGY = Pattern.compile("(对不起|抱歉|不好意思|我错了|是我不好|语气不好|不该那样)");
    // 指责她: 明确指向"你"的才触发, 避免"我今天烦死了/生活没意思"这类自我倾诉被误判
    private static final Pattern ACCUSATION = Pattern.compile(
            "(你怎么这么|你真|太冷漠|敷衍|讨厌你|你.*不理我|你.*不在乎|你.*说话.*没意思|跟你.*聊天.*没意思|你说话真没意思|你烦死了|烦不烦|你妈的|傻逼|你傻|你滚|滚蛋|废物|去死|贱人)");
    private static final Pattern AFFECTION = Pattern.compile("(想你|好想|舍不得|在乎|喜欢你|爱你|依赖)");
    // 自我负面情绪: "烦死/好烦"是说自己烦, 不是指责她
    private static final Pattern DISTRESS = Pattern.compile(
            "(很难过|好难过|难受|痛苦|委屈|崩溃|撑不住|撑不下去|想哭|压力好大|好累|没意义|不知道该怎么办|烦死|好烦|烦透了|烦)");
    private static final Pattern URGENT = Pattern.compile("(怎么办|救命|出事了|紧急|快来|帮帮我|在医院|出车祸)");
    private static final Pattern SHARE_JOY = Pattern.compile("(太好了|成功了|升职|拿到|通过了|中了|赢了|好开心)");

    private final MessageAppraisalRepository appraisalRepo;
    private final AgentStateService agentStateService;
    private final RelationshipService relationshipService;

    public AppraisalService(MessageAppraisalRepository appraisalRepo,
                            AgentStateService agentStateService,
                            RelationshipService relationshipService) {
        this.appraisalRepo = appraisalRepo;
        this.agentStateService = agentStateService;
        this.relationshipService = relationshipService;
    }

    /** 评估一条用户消息: 记录 + 更新 Agent 内部状态 + 关系微调 */
    @Transactional
    public AppraisalResult appraise(String companionId, String userId, String messageId,
                                    String text, PerceptionEngine.Perception perception) {
        AppraisalResult result = computeAppraisal(companionId, userId, messageId, text, perception);
        applyToState(companionId, result.appraisal());
        applyToRelationship(userId, companionId, result.appraisal());
        return result;
    }

    /**
     * 仅计算评估并落记录, 不修改状态。
     * 供 EmotionAgent 使用 —— 关键词只作为 cheap signal, 状态变更统一走 StateReducer。
     */
    @Transactional
    public AppraisalResult computeAppraisal(String companionId, String userId, String messageId,
                                            String text, PerceptionEngine.Perception perception) {
        if (text == null) text = "";
        String t = text;

        MessageAppraisal a = new MessageAppraisal();
        a.setMessageId(messageId);
        a.setCompanionId(companionId);
        a.setContext(t.length() > 200 ? t.substring(0, 200) : t);

        // 1. 关键词维度(cheap signal)
        if (APOLOGY.matcher(t).find()) {
            a.setWarmth(0.5); a.setHurt(0); a.setAnger(0); a.setRelationshipImpact(0.25);
        }
        if (ACCUSATION.matcher(t).find()) {
            a.setHurt(0.35); a.setAnger(0.4); a.setRelationshipImpact(-0.3); a.setEmotionalImpact(0.7);
        }
        if (AFFECTION.matcher(t).find()) {
            a.setWarmth(Math.max(a.getWarmth(), 0.6)); a.setRelationshipImpact(Math.max(a.getRelationshipImpact(), 0.3));
        }
        if (DISTRESS.matcher(t).find()) {
            a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.8)); a.setUrgency(Math.max(a.getUrgency(), 0.6));
        }
        if (URGENT.matcher(t).find()) {
            a.setUrgency(0.9); a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.7));
        }
        if (SHARE_JOY.matcher(t).find()) {
            a.setWarmth(Math.max(a.getWarmth(), 0.5)); a.setRelationshipImpact(Math.max(a.getRelationshipImpact(), 0.2));
        }

        // 2. 感知维度(已有 LLM 精炼, 不额外调用)
        String emotion = perception != null ? perception.emotion() : null;
        if (emotion != null) {
            switch (emotion) {
                case "sad", "anxious", "lonely" -> {
                    a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.65));
                    a.setUrgency(Math.max(a.getUrgency(), 0.4));
                    a.setWarmth(Math.max(a.getWarmth(), 0.3));
                }
                case "angry", "frustrated" -> {
                    a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.6));
                    if (a.getHurt() == 0) a.setHurt(0.2);
                    if (a.getAnger() == 0) a.setAnger(0.25);
                    a.setRelationshipImpact(Math.min(a.getRelationshipImpact(), -0.1));
                }
                case "happy", "excited" -> {
                    a.setWarmth(Math.max(a.getWarmth(), 0.5));
                    a.setRelationshipImpact(Math.max(a.getRelationshipImpact(), 0.15));
                }
                case "tired" -> {
                    a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.5));
                    a.setWarmth(Math.max(a.getWarmth(), 0.2));
                }
                default -> { }
            }
        }
        String intent = perception != null ? perception.intent() : null;
        if ("share_upset".equals(intent)) {
            a.setEmotionalImpact(Math.max(a.getEmotionalImpact(), 0.7));
            a.setUrgency(Math.max(a.getUrgency(), 0.5));
        }
        if ("share_joy".equals(intent)) {
            a.setWarmth(Math.max(a.getWarmth(), 0.5));
            a.setRelationshipImpact(Math.max(a.getRelationshipImpact(), 0.2));
        }
        if ("question".equals(intent) || "planning".equals(intent)) {
            a.setPersonalRelevance(Math.max(a.getPersonalRelevance(), 0.6));
        }

        // 3. 落库(只记录, 不改状态)
        a.setEmotionalImpact(clamp(a.getEmotionalImpact()));
        a.setRelationshipImpact(clamp(a.getRelationshipImpact(), -1, 1));
        a.setUrgency(clamp(a.getUrgency()));
        a.setWarmth(clamp(a.getWarmth()));
        a.setHurt(clamp(a.getHurt()));
        a.setAnger(clamp(a.getAnger()));
        a.setPersonalRelevance(clamp(a.getPersonalRelevance()));
        appraisalRepo.save(a);

        return new AppraisalResult(a);
    }

    private void applyToState(String companionId, MessageAppraisal a) {
        AgentState state = agentStateService.getOrCreate(companionId);
        agentStateService.applyAppraisal(companionId, a.getHurt(), a.getAnger(), a.getWarmth());
    }

    /** 供 EmotionAgent 使用 —— 关系微调独立暴露(信任/亲密度是慢变量) */
    @Transactional
    public void applyRelationshipImpact(String userId, String companionId, AppraisalResult result) {
        applyToRelationship(userId, companionId, result.appraisal());
    }

    private void applyToRelationship(String userId, String companionId, MessageAppraisal a) {
        Relationship rel = relationshipService.find(userId, companionId);
        if (rel == null) return;
        double trust = rel.getTrust();
        double intimacy = rel.getIntimacy();
        if (a.getRelationshipImpact() < -0.05) {
            relationshipService.updateMetrics(userId, companionId,
                    trust - 0.004, intimacy - 0.001);
        } else if (a.getRelationshipImpact() > 0.05) {
            relationshipService.updateMetrics(userId, companionId,
                    trust + 0.004, intimacy + 0.003);
        }
    }

    public record AppraisalResult(MessageAppraisal appraisal) {
        public double hurt() { return appraisal.getHurt(); }
        public double anger() { return appraisal.getAnger(); }
        public double warmth() { return appraisal.getWarmth(); }
        public double urgency() { return appraisal.getUrgency(); }
        public double relationshipImpact() { return appraisal.getRelationshipImpact(); }
        public double emotionalImpact() { return appraisal.getEmotionalImpact(); }

        /** 从 Emotion Agent 的评估值构建(不落库), 供 DrivesService/基线决策使用 */
        public static AppraisalResult fromValues(double hurt, double anger, double warmth,
                                                 double urgency, double relationshipImpact, double emotionalImpact) {
            MessageAppraisal a = new MessageAppraisal();
            a.setHurt(clamp0(hurt));
            a.setAnger(clamp0(anger));
            a.setWarmth(clamp0(warmth));
            a.setUrgency(clamp0(urgency));
            a.setRelationshipImpact(Math.max(-1, Math.min(1, relationshipImpact)));
            a.setEmotionalImpact(clamp0(emotionalImpact));
            return new AppraisalResult(a);
        }

        private static double clamp0(double v) {
            return Math.max(0, Math.min(1, v));
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
