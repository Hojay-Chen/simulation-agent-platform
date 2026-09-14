package com.luxera.companion.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主动路径第一级: <b>用户的一句话 → 平台能力目录里的一个能力</b>。
 *
 * <p>这是"50000 个 action 不塞给 LLM"那句话的落点。给模型看的是一张几十行的<em>能力目录</em>,
 * 不是动作列表。它认出来的东西只有一样: 这份目录里的一个 id。
 *
 * <h2>LLM 可以答错, 但不可以答出不存在的东西</h2>
 * <p>三条守卫, 缺一条这个类就变成了"把用户的话转述给一个语言模型然后照它说的做":
 * <ol>
 *   <li>它给的能力不在目录里 → 当没选。编造的能力没有对应应用, 硬走下去只会在下一级得到空,
 *       而那条路会多花一次调用、并在日志里留下一条看不出所以然的记录。</li>
 *   <li>置信度低于门槛 → 当没选。<b>这个数字是"大多数日常聊天不需要任何应用"的可测试版本</b> ——
 *       调低会让闲聊被当成应用请求, 调高会让明确的请求被漏掉, 所以它是配置项而不是常量。</li>
 *   <li>目录为空 → 根本不问。没有选项的选择没有信息量, 花的是钱, 换来的是一句"我选不出来"。</li>
 * </ol>
 *
 * <h2>为什么指纹跟着这次调用一起落库</h2>
 * <p>{@link #fingerprint} 是这份目录的 SHA-256。事后翻 {@code llm_calls} 能回答一个否则永远答不
 * 出来的问题: "<em>模型当时看到的是哪一版目录</em>"。目录变了指纹就变 —— 而按 id 排序再拼,
 * 保证"内容没变、顺序变了"不会让指纹变, 否则这个字段就只是一串随机数。
 */
@Slf4j
public class CapabilityResolver {

    private final ApplicationRuntimePort port;
    private final LlmRouter llmRouter;
    private final double threshold;

    public CapabilityResolver(ApplicationRuntimePort port, LlmRouter llmRouter, double threshold) {
        this.port = port;
        this.llmRouter = llmRouter;
        this.threshold = threshold;
    }

    /** 能力选择的结果。{@code confidence} 已过门槛 —— 没过门槛的不存在于这个类型。 */
    public record Choice(String capabilityId, String title, double confidence, String reason) {}

    /**
     * 用户的一句话 → 一个能力。没有任何能力该被牵扯进来时返回空。
     *
     * <p>这个方法只回答"这句话属于哪一类事", 不回答"用哪个应用"(那是
     * {@link ApplicationResolver} 的活)。切成两级是因为它们的输入规模差两个数量级:
     * 能力是几十行, 应用是几千行。放在一次调用里, 模型要在几千行里找一个,
     * 而它连"该找哪一类"都还没想清楚。
     */
    public Optional<Choice> select(String companionId, String userText) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }
        List<CapabilityView> catalogue;
        try {
            catalogue = port.capabilities();
        } catch (Exception e) {
            log.warn("[CapabilityResolver] 读能力目录失败: {}", e.getMessage());
            return Optional.empty();
        }
        if (catalogue == null || catalogue.isEmpty()) {
            return Optional.empty();
        }

        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderSystem(catalogue))
                    .user(userText)
                    .task("application-capability-selection")
                    // 例子里的能力 id 用占位符, 不写死某一个 —— 那会让模型倾向选它
                    .schemaHint("{\"capability\":\"<目录里的某个能力 id>\",\"confidence\":0.8,\"reason\":\"…\"}")
                    .temperature(0.2)
                    .metadata(meta(companionId, "application-capability",
                            "stableHash", fingerprint(catalogue)))
                    .build());
            JsonNode json = result.getJson();
            if (json == null) {
                return Optional.empty();
            }

            String capabilityId = json.path("capability").asText(null);
            if (capabilityId == null || capabilityId.isBlank()) {
                return Optional.empty();
            }

            CapabilityView matched = null;
            for (CapabilityView view : catalogue) {
                if (view.capabilityId().equals(capabilityId)) {
                    matched = view;
                    break;
                }
            }
            if (matched == null) {
                log.warn("[CapabilityResolver] LLM 选了一个不在能力目录里的能力, 当没选: {}", capabilityId);
                return Optional.empty();
            }

            double confidence = json.path("confidence").asDouble(0);
            if (confidence < threshold) {
                log.info("[CapabilityResolver] 能力选择置信度不足({} < {}), 不动用应用: capability={}",
                        confidence, threshold, capabilityId);
                return Optional.empty();
            }
            return Optional.of(new Choice(matched.capabilityId(), matched.title(), confidence,
                    json.path("reason").asText("")));
        } catch (Exception e) {
            log.warn("[CapabilityResolver] 能力选择失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 能力目录的 system。这是 LLM 在"要不要用应用"这一层能看到的全部东西。
     *
     * <p>"大多数日常聊天不需要任何应用"那句写在提示词里, 而不是靠后处理猜出来 ——
     * 它是一个<em>取向</em>, 不是一个阈值。模型不知道这件事, 它会把"我今天有点累"也归到
     * 某个能做事的能力上, 因为它的训练目标里"选一个"永远比"不选"得分高。
     */
    private static String renderSystem(List<CapabilityView> catalogue) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数字人。用户在跟你说话。你所在的平台上有一些应用, 可以帮用户做事。\n\n");
        sb.append("你现在能想到的能力只有下面这些(这是完整目录):\n");
        for (CapabilityView view : catalogue) {
            sb.append("- ").append(view.capabilityId()).append(" —— ").append(view.title());
            if (view.description() != null && !view.description().isBlank()) {
                sb.append(": ").append(view.description());
            }
            if (view.category() != null && !view.category().isBlank()) {
                sb.append("(分类: ").append(view.category()).append(')');
            }
            sb.append('\n');
        }
        sb.append("\n规则:\n");
        sb.append("- 只能从上面这份目录里选, 不要编造能力。\n");
        sb.append("- <b>大多数日常聊天不需要任何应用</b> —— 闲聊、倾诉、提问、说情绪, 都不是要用一个应用。\n");
        sb.append("- 只有用户明确想让某件事被真的做掉(而不是想聊它), 才选一个能力。\n");
        sb.append("- 不确定就不要选。选错会让数字人去做一件用户没要的事, 比不做更糟。\n");
        sb.append("- confidence 是你对自己判断的信心, 0 到 1 之间的小数。\n");
        sb.append("\n只输出 JSON: {\"capability\": \"<能力 id>\", \"confidence\": 0.8, \"reason\": \"<一句话>\"}。\n");
        sb.append("不需要任何应用时输出 {\"capability\": null, \"confidence\": 0, \"reason\": \"<一句话>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /**
     * 能力目录的指纹 —— 按 id 排序再拼, 于是"内容没变、顺序变了"不改变它。
     *
     * <p>平台给出的顺序不必是稳定的。而"目录没变、指纹却变了"会让这个字段失去全部意义:
     * 它会从"模型当时看到的是哪一版"退化成"模型当时是什么时候被调用的"。
     */
    public static String fingerprint(List<CapabilityView> catalogue) {
        List<CapabilityView> sorted = new ArrayList<>(catalogue);
        sorted.sort((a, b) -> a.capabilityId().compareTo(b.capabilityId()));
        StringBuilder sb = new StringBuilder();
        for (CapabilityView view : sorted) {
            sb.append(view.capabilityId()).append('\n')
                    .append(view.title()).append('\n')
                    .append(view.description()).append('\n')
                    .append(view.category()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /** {@code Map.of} 遇到 null value 直接 NPE —— 而 companionId 允许为空, 所以自己拼。 */
    static Map<String, String> meta(String companionId, String purpose) {
        Map<String, String> map = new LinkedHashMap<>();
        if (companionId != null) {
            map.put("companionId", companionId);
        }
        map.put("purpose", purpose);
        return map;
    }

    static Map<String, String> meta(String companionId, String purpose, String key, Object value) {
        Map<String, String> map = meta(companionId, purpose);
        if (value != null) {
            map.put(key, String.valueOf(value));
        }
        return map;
    }
}
