package com.luxera.companion.interaction;

import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.behavior.Drives;
import com.luxera.companion.behavior.DrivesService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 交互策略引擎(§三~十一 + §十五~十七):
 * 是规则树; 引入 Appraisal + Drives 竞争 —— 消息先 Appraisal(改变内部状态),
 * 再由行为倾向(desire_to_reply vs desire_to_avoid)评分竞争决定行为。
 * 保留明确情境规则(离开/回来/求助/低落), 默认路径用 Drives 竞争(避免纯 if/else 堆叠)。
 */
@Component
public class InteractionPolicyEngine {

    private static final Pattern TRIVIAL_ACK = Pattern.compile(
            "^(嗯|哦|噢|好|行|好的|哦哦|嗯嗯|哈哈|哈哈哈|哈哈哈哈|啊|哦好|知道了|收到|ok|OK|嗯嗯嗯|呵呵|唉|\\.\\.\\.\\.*|……)$"
                    + "|^(真的|是吗|是嘛|这样啊|原来如此|好吧|那好吧)$");

    private static final Pattern LEAVE_PATTERN = Pattern.compile(
            "(去忙|去洗澡|去吃饭|去开会|走了|拜拜|先不聊|不聊了|我下了|睡了|要睡了|先睡了|我先去|忙去了|下了|走啦|我走)");

    private static final Pattern RETURN_PATTERN = Pattern.compile(
            "(我回来了|忙完了|洗完|好了|回来了|刚忙完|我回来)");

    private static final Pattern ADVICE_ASK = Pattern.compile(
            "(怎么办|该怎么做|怎么处理|帮我想想|给点建议|你觉得我该)");

    private final DrivesService drivesService;

    public InteractionPolicyEngine(DrivesService drivesService) {
        this.drivesService = drivesService;
    }

    /** 决策入口: Appraisal → Drives 竞争 → 明确规则 → 状态调节 */
    public InteractionDecision decide(InteractionInput in) {
        // 0. Drives 竞争(基于 Appraisal + 状态 + 可用状态)
        Drives drives = null;
        if (in.appraisal != null) {
            drives = drivesService.compute(in.appraisal, in.energy, in.stress, in.closeness,
                    in.familiarity, in.intimacy, in.availability, in.userText,
                    in.userText == null ? 0 : in.userText.length());
        }

        // 1. 明确情境规则
        InteractionDecision base = decideBase(in);

        // 2. 若回避欲显著高于回复欲 → DEFER(已读不回), 即使规则倾向回复
        if (drives != null && base.action == InteractionAction.REPLY_NOW) {
            double gap = drives.desireToAvoid() - drives.desireToReply();
            if (gap > 0.15) {
                return new InteractionDecision(InteractionAction.DEFER, ResponseCommitment.ACK,
                        0, false, false, false,
                        "看到了但这次不想回(回避" + round(drives.desireToAvoid())
                                + ">回复" + round(drives.desireToReply()) + ")", 0.8,
                        ResponseBudget.forCommitment(ResponseCommitment.ACK, in.intimate));
            }
            // 琐碎且回复欲低 → 未读忽略
            boolean trivial = TRIVIAL_ACK.matcher(in.userText == null ? "" : in.userText.trim()).find()
                    || (in.userText != null && in.userText.trim().length() <= 2);
            if (trivial && drives.desireToReply() < 0.35) {
                return new InteractionDecision(InteractionAction.IGNORE, ResponseCommitment.ACK,
                        0, false, false, false,
                        "琐碎且回复欲低, 未读忽略", 0.7,
                        ResponseBudget.forCommitment(ResponseCommitment.ACK, in.intimate));
            }
        }

        // 3. 状态调节
        return tuned(base, in.energy, in.stress, in.relationshipStage, in.availability);
    }

    private InteractionDecision decideBase(InteractionInput in) {
        String text = in.userText == null ? "" : in.userText.trim();
        String intent = in.intent;
        String emotion = in.emotion;

        boolean intimate = in.intimate;
        boolean busy = in.busy;

        // 1. 离开 → 结束对话, 短应, 不再续聊
        if (LEAVE_PATTERN.matcher(text).find()) {
            return new InteractionDecision(InteractionAction.END_CONVERSATION, ResponseCommitment.ACK,
                    500, false, false, false, "对方要离开, 自然收尾", 0.9,
                    ResponseBudget.forCommitment(ResponseCommitment.ACK, intimate));
        }

        // 2. 回来 → 自然重开(而非"欢迎回来")
        if (RETURN_PATTERN.matcher(text).find()) {
            return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.CASUAL,
                    1200, true, true, false, "对方回来了, 自然接上", 0.9,
                    ResponseBudget.forCommitment(ResponseCommitment.CASUAL, intimate));
        }

        // 3. 琐碎应和 → 极短回复(可能忽略, 若忙则忽略)
        boolean trivial = TRIVIAL_ACK.matcher(text).find() || text.length() <= 2;
        if (trivial) {
            if (busy) {
                return new InteractionDecision(InteractionAction.IGNORE, ResponseCommitment.ACK,
                        0, false, false, false, "在忙, 琐碎消息忽略", 0.7,
                        ResponseBudget.forCommitment(ResponseCommitment.ACK, intimate));
            }
            return new InteractionDecision(InteractionAction.SHORT_ACK, ResponseCommitment.ACK,
                    700, true, false, false, "琐碎应和, 极短回复", 0.9,
                    ResponseBudget.forCommitment(ResponseCommitment.ACK, intimate));
        }

        // 4. 明显情绪低落 → 深入陪伴, 少问少建议
        if (isLow(emotion)) {
            boolean deep = text.length() > 30 || "sad".equals(emotion) || "anxious".equals(emotion);
            ResponseCommitment c = deep ? ResponseCommitment.DEEP : ResponseCommitment.ENGAGED;
            return new InteractionDecision(InteractionAction.REPLY_NOW, c,
                    deep ? 2600 : 1600, true, true, false, "情绪低落, 先陪伴", 0.9,
                    ResponseBudget.forCommitment(c, intimate));
        }

        // 5. 明确求助 → 投入 + 允许建议
        if (ADVICE_ASK.matcher(text).find()) {
            return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.ENGAGED,
                    2000, true, true, false, "对方求助, 进入建议模式", 0.9,
                    budget(120, 3, 1, 1, 0.6, intimate));
        }

        // 6. 提问 → 正常回 + 最多一个问题
        if ("question".equals(intent)) {
            return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.ENGAGED,
                    1600, true, true, false, "回答问题并自然追问", 0.85,
                    ResponseBudget.forCommitment(ResponseCommitment.ENGAGED, intimate));
        }

        // 7. 分享喜悦 → 一起开心, 可调侃
        if ("share_joy".equals(intent) || "happy".equals(emotion)) {
            return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.ENGAGED,
                    1400, true, false, true, "一起开心, 可以调侃和分享", 0.9,
                    ResponseBudget.forCommitment(ResponseCommitment.ENGAGED, intimate));
        }

        // 8. 默认: 按长度/情绪给 CASUAL~ENGAGED
        if (text.length() > 60 || "share_upset".equals(intent)) {
            return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.ENGAGED,
                    1800, true, true, true, "内容较多, 投入回应", 0.8,
                    ResponseBudget.forCommitment(ResponseCommitment.ENGAGED, intimate));
        }
        return new InteractionDecision(InteractionAction.REPLY_NOW, ResponseCommitment.CASUAL,
                1200, true, false, false, "日常闲聊, 自然回应", 0.85,
                ResponseBudget.forCommitment(ResponseCommitment.CASUAL, intimate));
    }

    private static boolean isLow(String emotion) {
        return emotion != null && List.of("sad", "tired", "anxious", "lonely", "angry").contains(emotion);
    }

    /** 状态调节(§六/十三): 低精力/高压力/忙 → 更短更克制; 睡觉 → 忽略 */
    private static InteractionDecision tuned(InteractionDecision d, double energy, double stress,
                                             String relationshipStage,
                                             com.luxera.companion.state.CompanionAvailability availability) {
        ResponseBudget b = d.budget;
        ResponseCommitment c = d.commitment;
        boolean adjusted = false;

        if (energy < 0.35) {
            c = downgrade(c);
            b = ResponseBudget.forCommitment(c, b.allowSelfDisclose);
            adjusted = true;
        }
        if (stress > 0.7) {
            b = new ResponseBudget(
                    Math.max(12, (int) (b.maxCharacters * 0.6)),
                    Math.max(1, b.maxSentences - 1),
                    0, 0, b.emotionalIntensity, b.allowSelfDisclose);
            adjusted = true;
        }
        if (availability != null) {
            switch (availability) {
                case SLEEPING -> {
                    return new InteractionDecision(d.action == InteractionAction.END_CONVERSATION
                                    ? d.action : InteractionAction.IGNORE,
                            ResponseCommitment.ACK, d.delayMs, false, false, false,
                            "她在睡觉, 不打扰", 0.9,
                            ResponseBudget.forCommitment(ResponseCommitment.ACK, b.allowSelfDisclose));
                }
                case BUSY, RESTING -> {
                    c = downgrade(c);
                    b = new ResponseBudget(
                            Math.max(8, (int) (b.maxCharacters * 0.6)),
                            Math.max(1, b.maxSentences - 1),
                            Math.min(b.questionBudget, 1), 0, b.emotionalIntensity, b.allowSelfDisclose);
                    adjusted = true;
                }
                case DISTRACTED -> {
                    b = new ResponseBudget(b.maxCharacters, Math.max(1, b.maxSentences - 1),
                            0, 0, b.emotionalIntensity, b.allowSelfDisclose);
                    adjusted = true;
                }
                default -> { }
            }
        }
        boolean newRel = relationshipStage == null
                || "new".equals(relationshipStage) || "familiar".equals(relationshipStage);
        if (newRel && (b.allowSelfDisclose || d.askQuestion)) {
            b = new ResponseBudget(b.maxCharacters, b.maxSentences, 0, b.adviceBudget,
                    b.emotionalIntensity, false);
            adjusted = true;
        }

        if (!adjusted) return d;
        return new InteractionDecision(d.action, c, d.delayMs, d.continueConversation,
                d.askQuestion && b.questionBudget > 0,
                d.selfDisclose && b.allowSelfDisclose,
                d.reason + "(状态: 精力" + pct(energy) + " 压力" + pct(stress)
                        + (availability != null ? " " + availability.name() : "") + ")", d.confidence, b);
    }

    private static ResponseCommitment downgrade(ResponseCommitment c) {
        return switch (c) {
            case DEEP -> ResponseCommitment.ENGAGED;
            case ENGAGED -> ResponseCommitment.CASUAL;
            default -> ResponseCommitment.ACK;
        };
    }

    private static String pct(double v) {
        return (int) (Math.round(Math.max(0, Math.min(1, v)) * 100)) + "%";
    }

    private static String round(double v) {
        return String.format("%.2f", v);
    }

    private static ResponseBudget budget(int chars, int sentences, int q, int adv, double intensity, boolean intimate) {
        return new ResponseBudget(chars, sentences, q, adv, intensity, intimate);
    }

    /** 决策输入(含 Appraisal 维度与关系标量) */
    public record InteractionInput(String userText, String intent, String emotion,
                                   double energy, double stress, String relationshipStage,
                                   boolean intimate, boolean busy,
                                   com.luxera.companion.state.CompanionAvailability availability,
                                   AppraisalService.AppraisalResult appraisal,
                                   double closeness, double familiarity, double intimacy) {
        /** 兼容: 无 Appraisal/关系标量 */
        public InteractionInput(String userText, String intent, String emotion,
                                double energy, double stress, String relationshipStage,
                                boolean intimate, boolean busy) {
            this(userText, intent, emotion, energy, stress, relationshipStage, intimate, busy,
                    null, null, 0.3, 0, 0);
        }
    }
}
