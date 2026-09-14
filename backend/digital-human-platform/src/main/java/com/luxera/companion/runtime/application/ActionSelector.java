package com.luxera.companion.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 反应路径的决策段: <b>此刻允许做的几件事 → 做哪一件, 以及填什么参数</b>。
 *
 * <h2>通用行为准则归数字人, 领域知识归应用</h2>
 * <p>「像真人一样下棋: 会赢就赢, 但不要解释算法」这类规则是<b>通用</b>的 —— 它说的是"怎么像
 * 一个真人那样行动", 对任何回合制对弈都成立。<b>具体打法</b>(「能三连就三连」)不在这里,
 * 它在应用 manifest 的 {@code agentHint} 里, 由 {@link ActionSpec#agentHint()} 带进来。
 * 这两者的分界就是这个类的全部要点: 这一段提示词里<b>没有一个具体应用的词汇</b>, 而模型仍然
 * 能做出符合那个应用规则的决策, 因为规则是应用自己写给它看的。
 *
 * <h2>候选是全部 pending, 不是第一个</h2>
 * <p>{@code pending.get(0)} 与"随便挑一个"只是换了件衣服 —— 而应用完全可以同时允许"落子"
 * 和"认输"。
 */
@Slf4j
public class ActionSelector {

    private final ApplicationRuntimePort port;
    private final LlmRouter llmRouter;
    private final PersonaService personaService;

    public ActionSelector(ApplicationRuntimePort port, LlmRouter llmRouter, PersonaService personaService) {
        this.port = port;
        this.llmRouter = llmRouter;
        this.personaService = personaService;
    }

    /** LLM 的答复: 做哪一件事, 以及填什么 input。 */
    public record Decision(ActionSpec action, JsonNode input) {}

    /**
     * 问 LLM 该怎么走。返回空表示"不行动" —— 那是它明确说的, 或者它答得不能采信。
     *
     * <p>"答得不能采信"有三种, 见 {@link #pickAction}。它们与"它说不行动"在这个方法的返回值上
     * 是同一件事(空), 但在日志里是三种不同的 WARN —— 一个把幻觉也算成"它不想动"的系统会让人
     * 永远查不出为什么数字人不下棋。
     */
    public Optional<Decision> select(List<ActionSpec> pending, ResourceView resource, String companionId) {
        if (pending == null || pending.isEmpty()) {
            return Optional.empty();
        }
        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderSystem(pending, resource, companionId))
                    .user("当前状态:\n" + pretty(resource.state()))
                    .task("application-action-selection")
                    // 例子里的动作 id 故意写成一个占位符: 写死一个真实动作名会让模型倾向选它
                    .schemaHint("{\"actionId\":\"<候选里的某个动作 id>\",\"input\":{},\"reason\":\"…\"}")
                    .temperature(0.3)
                    // LlmCallService.record 在 companionId 为空时静默跳过 —— 不设它这条调用就不落库
                    .metadata(CapabilityResolver.meta(companionId, "application"))
                    .build());
            JsonNode json = result.getJson();
            if (json == null) {
                return Optional.empty();
            }
            JsonNode input = json.path("input");
            if (input.isMissingNode() || input.isNull()) {
                return Optional.empty();   // 明确表示不行动
            }
            ActionSpec action = pickAction(pending, json.path("actionId").asText(null));
            return action == null ? Optional.empty() : Optional.of(new Decision(action, input));
        } catch (Exception e) {
            log.warn("[ActionSelector] 动作选择失败 resource={}: {}", resource.uri(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * LLM 说它要执行哪个动作 —— 但它说的是不是真的, 由这里说了算。
     *
     * <p>三种情况:
     * <ul>
     *   <li>它给了一个候选里的 actionId → 用它。</li>
     *   <li>它没给 actionId, 而候选只有一个 → 用它。这时"选哪个"本来就没有信息量,
     *       LLM 的活儿是填 input。</li>
     *   <li>它给了个不在候选里的名字, 或者候选不止一个却没说是哪个 → <b>不行动</b>。
     *       编造出来的动作不能顺手执行, 含糊的回答也不能替它补一个 —— 补的那个就是启发式。</li>
     * </ul>
     */
    ActionSpec pickAction(List<ActionSpec> pending, String actionId) {
        if (actionId != null && !actionId.isBlank()) {
            for (ActionSpec spec : pending) {
                if (spec.actionId().equals(actionId)) {
                    return spec;
                }
            }
            log.warn("[ActionSelector] LLM 选了一个不在候选里的动作, 不行动: {}", actionId);
            return null;
        }
        if (pending.size() == 1) {
            return pending.get(0);
        }
        log.warn("[ActionSelector] 有 {} 个候选动作但 LLM 没说选哪个, 不行动", pending.size());
        return null;
    }

    /**
     * 动作选择的 system。
     *
     * <p>四段: 我是谁(人格) → 我在哪儿(应用与资源) → 我能做什么(候选动作 + 应用写的行为提示)
     * → 通用规则。第三段里的"怎么打"与 input schema 完全来自应用, 数字人只是把它读出来。
     */
    String renderSystem(List<ActionSpec> pending, ResourceView resource, String companionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个正在与真人互动的数字人。此刻有一个应用里的事情轮到你做。\n\n");
        String persona = personaLine(companionId);
        if (persona != null) {
            sb.append("你是谁: ").append(persona).append('\n');
        }
        sb.append("应用: ").append(applicationLabel(resource.applicationId(), pending)).append('\n');
        sb.append("你正在操作的东西: ").append(resource.uri()).append('\n');
        if (resource.agentHint() != null && !resource.agentHint().isBlank()) {
            sb.append("这个东西怎么读:\n").append(resource.agentHint()).append('\n');
        }
        sb.append("\n你现在可以做的事(只能从里面挑一个):\n");
        for (ActionSpec action : pending) {
            sb.append("- ").append(action.actionId()).append(" —— ").append(action.description()).append('\n');
            if (action.agentHint() != null && !action.agentHint().isBlank()) {
                sb.append("  怎么打:\n");
                for (String line : action.agentHint().split("\n")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
            if (action.inputSchema() != null) {
                sb.append("  这个动作的 input 必须满足:\n")
                        .append(indent(action.inputSchema().toPrettyString())).append('\n');
            }
        }
        sb.append("\n规则:\n");
        sb.append("- 只在<b>轮到你</b>的时候行动 —— 候选里没有你的位置就说明不该你动。\n");
        sb.append("- input 必须满足上面那个 JSON Schema, 字段名一个字都不能改。\n");
        sb.append("- 像真人一样下棋: 会赢就赢, 但不要解释算法, 也不要在 input 里夹带解说。\n");
        sb.append("- 你不知道规则细节时, 以「怎么打」那段为准。\n");
        sb.append("\n只输出 JSON: {\"actionId\": \"<上面某一个动作 id>\", \"input\": <满足 schema 的对象>, "
                + "\"reason\": \"<一句话理由>\"}。\n");
        sb.append("如果你判断现在不该行动, 输出 {\"actionId\": null, \"input\": null, \"reason\": \"<理由>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /**
     * 应用名与版本 —— 只是给提示词一点上下文, 拿不到就退回 id。
     *
     * <p>不缓存: 它跟着一次 LLM 调用一起发生, 而那次调用本身就是这个类里最慢的一步。
     * 缓存会引入"应用改名之后提示词里还是旧名字"这种只有重启才能修好的 bug。
     */
    private String applicationLabel(String applicationId, List<ActionSpec> pending) {
        if (applicationId == null) {
            return "(未知应用)";
        }
        try {
            String capabilityId = pending.get(0).capabilityId();
            if (capabilityId == null) {
                return applicationId;
            }
            for (ApplicationView view : port.applicationsFor(capabilityId)) {
                if (applicationId.equals(view.applicationId())) {
                    return view.name() + " v" + view.version();
                }
            }
        } catch (Exception e) {
            log.debug("[ActionSelector] 读应用名失败: {}", e.getMessage());
        }
        return applicationId;
    }

    /** 人格只取名字与一句性格概述 —— 决策要的是"这个人会怎么下", 不是完整人设。 */
    private String personaLine(String companionId) {
        try {
            Persona persona = personaService.getActive(companionId);
            if (persona == null) {
                return null;
            }
            String name = persona.getIdentity() == null ? null : persona.getIdentity().getName();
            String summary = persona.getPersonality() == null ? null : persona.getPersonality().getSummary();
            if (name == null && summary == null) {
                return null;
            }
            return ((name == null ? "" : name + "。") + (summary == null ? "" : summary)).trim();
        } catch (Exception e) {
            log.debug("[ActionSelector] 读人格失败: {}", e.getMessage());
            return null;
        }
    }

    static String pretty(JsonNode state) {
        return state == null ? "(无状态)" : state.toPrettyString();
    }

    static String indent(String text) {
        return "    " + text.replace("\n", "\n    ");
    }
}
