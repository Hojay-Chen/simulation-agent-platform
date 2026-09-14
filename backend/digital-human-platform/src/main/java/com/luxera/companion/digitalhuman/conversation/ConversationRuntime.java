package com.luxera.companion.digitalhuman.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.digitalhuman.expression.EmotionContinuityFilter;
import com.luxera.companion.digitalhuman.expression.HesitationEngine;
import com.luxera.companion.digitalhuman.expression.HumanLikenessProperties;
import com.luxera.companion.digitalhuman.expression.PhysioExpressionFilter;
import com.luxera.companion.digitalhuman.persona.PersonaFingerprint;
import com.luxera.companion.digitalhuman.persona.PersonaVoiceCompiler;
import com.luxera.companion.digitalhuman.persona.PersonaVoiceProfile;
import com.luxera.companion.digitalhuman.expression.TypingRhythmEngine;
import com.luxera.companion.digitalhuman.expression.TypoEngine;
import com.luxera.companion.digitalhuman.memory.MemoryDriftPolicy;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.ChatResult;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.LlmMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V10 §15 Conversation Runtime: **系统唯一的聊天文本生产入口**。
 *
 * 其他模块禁止直接生成最终聊天文本 —— 一切聊天文本必须经本管道:
 * 分层 Prompt(稳定层经缓存) → LLM → 输出契约解析 → 质量验证 → 失败重生成(≤2 次)。
 *
 * 输出契约(V10 §15.3): {"messages":[{"text":"..."}]}; 禁止旁白/舞台动作/AI 腔,
 * 由 OutputValidationChain 强制; 全部失败 → 不生成(像真人没说出口)。
 */
@Slf4j
@Component
public class ConversationRuntime {

    /** 最大生成尝试次数(首次 + 2 次修正重试) */
    public static final int MAX_ATTEMPTS = 3;

    private final LlmRouter llm;
    private final OutputValidationChain validator;
    private final PromptLayerCache promptCache;
    private final ObjectMapper mapper = new ObjectMapper();

    // V10 §5 拟人化层(可逐项开关)
    private final HumanLikenessProperties humanLikeness;
    private final PhysioExpressionFilter physioFilter;
    private final EmotionContinuityFilter emotionFilter;
    private final PersonaVoiceCompiler voiceCompiler;
    private final PersonaFingerprint fingerprint;
    private final TypoEngine typoEngine;
    private final HesitationEngine hesitationEngine;

    public ConversationRuntime(LlmRouter llm, OutputValidationChain validator,
                               PromptLayerCache promptCache,
                               HumanLikenessProperties humanLikeness,
                               PhysioExpressionFilter physioFilter,
                               EmotionContinuityFilter emotionFilter,
                               PersonaVoiceCompiler voiceCompiler,
                               PersonaFingerprint fingerprint,
                               TypoEngine typoEngine,
                               HesitationEngine hesitationEngine) {
        this.llm = llm;
        this.validator = validator;
        this.promptCache = promptCache;
        this.humanLikeness = humanLikeness;
        this.physioFilter = physioFilter;
        this.emotionFilter = emotionFilter;
        this.voiceCompiler = voiceCompiler;
        this.fingerprint = fingerprint;
        this.typoEngine = typoEngine;
        this.hesitationEngine = hesitationEngine;
    }

    /**
     * 生成一条(或连发多条)聊天消息草稿 —— 全部草稿通过质量闸门。
     * 失败(LLM 异常/验证始终不过)返回空列表, 调用方应回退到"不说"或模板。
     */
    public List<ChatMessageDraft> generateDrafts(ConversationRequest request) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String raw;
            try {
                raw = callLlm(request);
            } catch (Exception e) {
                log.warn("[ConversationRuntime] LLM 调用失败 attempt={}: {}", attempt, e.getMessage());
                return List.of();
            }
            List<String> texts = parseOutputContract(raw);
            if (texts.isEmpty()) {
                // 契约解析失败: 结构问题, 重试无意义
                log.warn("[ConversationRuntime] 输出契约解析失败: {}", truncate(raw, 80));
                return List.of();
            }
            List<ChatMessageDraft> drafts = indexDrafts(texts);
            List<ConversationOutputValidator.Issue> issues = validator.validateAll(drafts);
            if (issues.isEmpty()) {
                // V10 §5 拟人化后处理: 错字/犹豫(只改最终输出, 不进 prompt 避免污染缓存)
                return postProcessHumanLikeness(request, drafts);
            }
            log.info("[ConversationRuntime] 验证未通过, 重新生成({}/{}): {}",
                    attempt + 1, MAX_ATTEMPTS, issues.get(0).reason());
            request = request.withCorrection(describeIssues(issues));
        }
        // 重试后仍不过关 → 不说(像真人一样把话咽回去)
        log.info("[ConversationRuntime] 重试后仍未通过质量闸门, 放弃生成");
        return List.of();
    }

    /** 分层组装 + 稳定层缓存 → LLM 调用 */
    private String callLlm(ConversationRequest request) {
        String stable = promptCache.stableLayers(request.stablePrefix(), request.semiStable());
        StringBuilder system = new StringBuilder(stable);

        // V10 §5 拟人化: 稳定层追加(生理/情绪/人设口音), 缓存 hash 参与计算
        system.append("\n");
        appendHumanLikenessStable(system, request);

        system.append("\n—— 当前动态 ——\n");
        for (String line : request.dynamicSuffix()) {
            if (line != null && !line.isBlank()) {
                system.append(line).append('\n');
            }
        }
        if (request.correctionHint() != null && !request.correctionHint().isBlank()) {
            system.append("\n—— 上一轮生成未通过检查, 请修正 ——\n").append(request.correctionHint()).append('\n');
        }

        // V10 §5 PersonaFingerprint: 隐式区分不同 persona(userPrompt 首行)
        String userPrompt = request.userPrompt();
        if (humanLikeness.isPersonaFingerprint()) {
            var profile = voiceCompiler.compile(request.companionId(), null);
            String fp = fingerprint.compute(profile);
            userPrompt = fingerprint.injectFingerprint(userPrompt, fp);
        }

        ChatResult result = llm.chat(ChatRequest.builder()
                .messages(List.of(
                        LlmMessage.system(system.toString()),
                        LlmMessage.user(userPrompt)))
                .temperature(request.temperature())
                .maxTokens(Math.max(64, request.maxLength() * 2))
                .metadata(Map.of("companionName", request.companionName(),
                        "purpose", "conversation"))
                .build());
        return result.getContent() == null ? "" : result.getContent();
    }

    /** V10 §5 拟人化稳定层元素(生理预算 + 情绪连贯 + 人设口音) */
    private void appendHumanLikenessStable(StringBuilder system, ConversationRequest request) {
        // 1. 生理/时态(精力/压力/睡眠压力/周末)
        if (humanLikeness.isPhysioFilter() && request.getPhysioState() != null) {
            var physio = request.getPhysioState();
            var budget = physioFilter.computeBudget(
                    new PhysioExpressionFilter.PhysioContext(
                            physio.energy(), physio.stress(), physio.sleepPressure(),
                            physio.illness(), physio.moodShift(), null));
            system.append("[生理/时态] maxChars=").append(budget.maxChars)
                  .append(", typoAdd=").append(String.format("%.3f", budget.typoRateAdd))
                  .append(", 周末=").append(budget.isWeekend).append("\n");
        }
        // 2. 情绪连贯(近 5 分钟情绪基调)
        if (humanLikeness.isPhysioFilter()) {
            var hint = emotionFilter.computeHint(request.companionId(),
                    request.getCurrentEmotion(), request.getCurrentEmotionIntensity());
            if (hint.isWarning() && hint.getMessage() != null) {
                system.append("[情绪连贯] ").append(hint.getMessage()).append("\n");
            }
        }
        // 3. 人设口音/口头禅(per persona)
        if (humanLikeness.isPersonaVoice()) {
            var profile = voiceCompiler.compile(request.companionId(), null);
            String fragment = voiceCompiler.buildVoiceFragment(profile);
            if (!fragment.isBlank()) {
                system.append(fragment);
            }
        }
    }

    /** V10 §5 拟人化后处理(错字/犹豫, 只改最终输出) */
    private List<ChatMessageDraft> postProcessHumanLikeness(ConversationRequest request,
                                                            List<ChatMessageDraft> drafts) {
        boolean applyTypos = humanLikeness.isTypos();
        boolean applyHesitation = humanLikeness.isHesitation();
        if (!applyTypos && !applyHesitation) return drafts;

        List<ChatMessageDraft> processed = new ArrayList<>();
        for (ChatMessageDraft d : drafts) {
            String text = d.text();
            // 错字: 受生理状态影响
            if (applyTypos && request.getPhysioState() != null) {
                var p = request.getPhysioState();
                text = typoEngine.applyTypos(text, new TypoEngine.TypoContext(
                        0.02, p.energy(), p.stress(), p.sleepPressure()));
            }
            // 犹豫: 低 notice level 时句尾加 ...
            if (applyHesitation) {
                var markers = hesitationEngine.computeHesitation(
                        new HesitationEngine.HesitationContext(0.5, 0.5, request.companionName()));
                if (markers.sentenceEnd != null && !text.isEmpty()) {
                    text = text + markers.sentenceEnd;
                }
            }
            processed.add(ChatMessageDraft.of(d.index(), text));
        }
        return processed;
    }

    /** 输出契约解析: {"messages":[{"text":"..."}]}; 容错: 非 JSON 时整段视为一条消息 */
    private List<String> parseOutputContract(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = mapper.readTree(trimmed);
            JsonNode messages = node.path("messages");
            if (messages.isArray() && !messages.isEmpty()) {
                for (JsonNode m : messages) {
                    String text = m.path("text").asText("").trim();
                    if (!text.isEmpty()) {
                        out.add(text);
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // 非 JSON → 按纯文本处理
        }
        out.add(trimmed);
        return out;
    }

    private static List<ChatMessageDraft> indexDrafts(List<String> texts) {
        List<ChatMessageDraft> drafts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            drafts.add(ChatMessageDraft.of(i, texts.get(i)));
        }
        return drafts;
    }

    private static String describeIssues(List<ConversationOutputValidator.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (ConversationOutputValidator.Issue issue : issues) {
            sb.append("第").append(issue.draftIndex() + 1).append("条: ").append(issue.reason()).append("; ");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
