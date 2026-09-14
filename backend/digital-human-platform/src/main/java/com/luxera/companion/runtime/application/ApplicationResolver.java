package com.luxera.companion.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 主动路径第二级: <b>能力 → 具体应用</b>。
 *
 * <h2>只有一个候选时不问 LLM</h2>
 * <p>没有第二个选项的"选择"只是在花钱听模型复述一遍输入。这一条不只是省钱: 它同时消除了
 * 一次模型可以在"只有一个正确答案"的问题上答错的机会。候选唯一时那个唯一候选直接胜出,
 * 连置信度都不需要 —— 没有任何别的东西可以选。
 *
 * <h2>多个候选时, 它选的必须在候选里</h2>
 * <p>让模型挑一个并不存在的应用, 后面整条链都会在权限校验那里撞墙(那个应用没有会话、没有
 * 参与者行), 而调用方拿到的会是一个语义完全不同的错误。不如在这里就判它不合格。
 *
 * <p>这条守卫与 {@link CapabilityResolver} 的那条是同一句话的两个位置 ——
 * <b>平台的校验说了算, 模型的回答只是输入</b>。分散写两遍不是重复: 一级的能力目录与二级的
 * 应用候选是两份不同的数据, 谁也不该假设另一级已经校验过了。
 */
@Slf4j
public class ApplicationResolver {

    private final ApplicationRuntimePort port;
    private final LlmRouter llmRouter;

    public ApplicationResolver(ApplicationRuntimePort port, LlmRouter llmRouter) {
        this.port = port;
        this.llmRouter = llmRouter;
    }

    /** 该能力下的具体应用。没有候选时返回空 —— 空不是错误, 是"这类事现在没有应用能做"。 */
    public Optional<String> select(String companionId, String capabilityId, String userText) {
        List<ApplicationView> candidates;
        try {
            candidates = port.applicationsFor(capabilityId);
        } catch (Exception e) {
            log.warn("[ApplicationResolver] 读能力 {} 的候选应用失败: {}", capabilityId, e.getMessage());
            return Optional.empty();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0).applicationId());
        }

        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderSystem(capabilityId, candidates))
                    .user(userText == null ? "" : userText)
                    .task("application-selection")
                    .schemaHint("{\"applicationId\":\"" + candidates.get(0).applicationId() + "\",\"reason\":\"…\"}")
                    .temperature(0.2)
                    .metadata(CapabilityResolver.meta(companionId, "application-selection",
                            "candidates", candidates.size()))
                    .build());
            JsonNode json = result.getJson();
            String picked = json == null ? null : json.path("applicationId").asText(null);
            for (ApplicationView view : candidates) {
                if (view.applicationId().equals(picked)) {
                    return Optional.of(picked);
                }
            }
            log.warn("[ApplicationResolver] LLM 选了一个不在候选里的应用, 不行动: picked={}, candidates={}",
                    picked, candidates.stream().map(ApplicationView::applicationId).toList());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[ApplicationResolver] 应用选择失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 应用候选 —— 只有同一能力下有多个应用时才会渲染到这里。 */
    private static String renderSystem(String capabilityId, List<ApplicationView> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户想做的事属于「").append(capabilityId).append("」, 平台上有这几个应用都能做:\n");
        for (ApplicationView view : candidates) {
            sb.append("- ").append(view.applicationId()).append(" —— ").append(view.name());
            if (view.description() != null && !view.description().isBlank()) {
                sb.append(": ").append(view.description());
            }
            sb.append('\n');
        }
        sb.append("\n规则:\n");
        sb.append("- 只能从上面这几个里选, 不要编造应用 id。\n");
        sb.append("- 按用户这句话更贴合哪一个来选; 看不出区别就选第一个。\n");
        sb.append("\n只输出 JSON: {\"applicationId\": \"<上面的某个 id>\", \"reason\": \"<一句话>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }
}
