package com.luxera.companion.human.mind.decision;

import com.luxera.companion.human.life.plan.PlanIntent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>她决定要做的<b>一个动作</b></b>。
 *
 * <h2>它为什么与 {@code PlanIntent.ActionIntent} 是两个类型</h2>
 * 两者的字段几乎相同(capability key + 参数 + 一句描述), 所以这一条必须说清楚,
 * 否则它就是一次没有理由的重复:
 *
 * <table border="1">
 *   <tr><th></th><th>本类型</th><th>{@code PlanIntent.ActionIntent}</th></tr>
 *   <tr>
 *     <td>在哪一侧</td>
 *     <td><b>决定之后</b> —— 是 {@link Decision} 的一部分</td>
 *     <td><b>排定之前</b> —— 是一个意图的拆解步骤</td>
 *   </tr>
 *   <tr>
 *     <td>谁会读它</td>
 *     <td>装配成 {@code ActionCommand} 发给世界的那一方</td>
 *     <td>重排器与计划校验器 —— 它们要判断"这几步排不排得下"</td>
 *   </tr>
 *   <tr>
 *     <td>额外带什么</td>
 *     <td>{@link #reason()} 与 {@link #optional()}</td>
 *     <td>只有 capability 与参数</td>
 *   </tr>
 * </table>
 *
 * <p>两个额外字段不是装饰, 它们各自对应一种真实处境:
 *
 * <ul>
 *   <li>{@link #reason()} —— <b>为什么做这个动作</b>。计划侧不需要它, 因为那时
 *       "为什么"由整个意图回答; 而到了执行这一侧, 一个动作可能只是顺手的
 *       ("顺便把屏幕点亮"), 它的理由与整次决定的理由不同。日志里
 *       没有这一句时, "她为什么按了那个键"只能靠人去读代码;</li>
 *   <li>{@link #optional()} —— <b>做不成也不影响这次决定是否成立</b>。
 *       没有它, 一个失败的可选动作会把整次决定标成失败, 于是
 *       "她今天有 40% 的决定失败了"这个统计里混进了一堆"没把屏幕点亮"。</li>
 * </ul>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不是 {@code ActionCommand}</b>。它没有 id、没有发出时刻、没有幂等键 ——
 *       那三样是<b>发出</b>这件事的属性, 由装配它的一方给。
 *       把命令造在这里, 会让"她决定要做"与"这件事已经发出去了"变成同一个瞬间,
 *       而中间的失败与重试就没有地方待了;</li>
 *   <li><b>不校验 capability 是否存在</b>。校验发生在
 *       {@code Intention#evaluate} 与计划侧的 {@code PlanValidator} ——
 *       两处都做过之后, 这里再做一次只会多一个可能彼此矛盾的答案。</li>
 * </ul>
 *
 * @param capabilityKey 要做的那件事的能力键
 * @param arguments     参数。key 的命名由提供这个能力的应用决定 —— 平台不认识它们
 * @param description   一句人话("给她回一条消息"), 进日志与 LLM context
 * @param reason        为什么做它
 * @param optional      做不成是否影响整次决定
 */
public record ActionIntent(
        String capabilityKey,
        Map<String, Object> arguments,
        String description,
        String reason,
        boolean optional) {

    public ActionIntent {
        Objects.requireNonNull(capabilityKey, "动作意图必须有能力键 —— 没有它就无法被执行");
        Objects.requireNonNull(description, "动作意图必须有一句人话 —— 它会进日志");
        capabilityKey = capabilityKey.trim();
        if (capabilityKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "能力键不能是空白 —— 一个没有能力键的动作意图会一路走到执行处才失败, "
                            + "而那时已经离出错的地方很远了");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        description = description.trim();
        reason = reason == null ? "" : reason.trim();
    }

    public static ActionIntent of(String capabilityKey, String description) {
        return new ActionIntent(capabilityKey, Map.of(), description, "", false);
    }

    public static ActionIntent of(String capabilityKey, Map<String, Object> arguments,
                                  String description) {
        return new ActionIntent(capabilityKey, arguments, description, "", false);
    }

    /** 一次"顺手的"动作 —— 做不成不影响这次决定是否成立。 */
    public static ActionIntent optionalOf(String capabilityKey, String description, String reason) {
        return new ActionIntent(capabilityKey, Map.of(), description, reason, true);
    }

    /**
     * 从计划侧的拆解步骤转过来 —— <b>两个类型之间唯一的转换点</b>。
     *
     * <p>它在 {@code DecisionEngine} 里被调用: 她选定一个意图之后,
     * 那个意图的拆解步骤就变成了这次决定要执行的动作, 同时被补上"为什么"。
     * 转换只在这一处发生, 于是"计划侧的动作与执行侧的动作什么时候会不一致"
     * 这个问题只有一个答案。
     */
    public static ActionIntent fromPlanAction(PlanIntent.ActionIntent action, String reason) {
        Objects.requireNonNull(action, "要转换的动作不能为空");
        return new ActionIntent(action.capabilityKey(), action.arguments(),
                action.description(), reason, false);
    }

    /** 转回计划侧 —— 用于"这次决定附带排进一个计划项"的场景。 */
    public PlanIntent.ActionIntent toPlanAction() {
        return PlanIntent.ActionIntent.of(capabilityKey, arguments, description);
    }

    public ActionIntent withArgument(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(arguments);
        merged.put(key, value);
        return new ActionIntent(capabilityKey, merged, description, reason, optional);
    }

    public ActionIntent asOptional(String why) {
        return new ActionIntent(capabilityKey, arguments, description, why, true);
    }

    public String describe() {
        return description + " [" + capabilityKey + (optional ? ", 可选" : "") + "]"
                + (reason.isEmpty() ? "" : " —— " + reason);
    }

    @Override
    public String toString() {
        return describe();
    }
}
