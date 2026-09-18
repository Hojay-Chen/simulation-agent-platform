package com.luxera.companion.human.mind.decision;

import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>她为什么这么决定</b>。进日志, 也进 LLM context。
 *
 * <h2>为什么是"一个机读码 + 一句人话", 而不是一个枚举, 也不是一句自由文本</h2>
 * 这两种做法都很常见, 而它们各自的失败方式都是具体的:
 *
 * <table border="1">
 *   <tr><th>做法</th><th>失败方式</th></tr>
 *   <tr>
 *     <td>一个枚举 {@code DecisionReasonCode}</td>
 *     <td>它会被写进数据库, 而<b>第三方应用决定做什么是开放的</b> ——
 *         接入一个新应用就要给平台加一档枚举, 这正是 §1.3 P4 要消灭的东西。
 *         退一步做成字符串枚举呢? 那就回到了下面那一行</td>
 *   </tr>
 *   <tr>
 *     <td>只有一句自由文本</td>
 *     <td><b>没法统计</b>。"她最近有多少次决定是被打扰的"这个问题需要
 *         按成因分组, 而自由文本的分组只能靠子串匹配 —— 它在文案改一个字之后
 *         静默地把一批记录归到别的组里, 且看不出来</td>
 *   </tr>
 * </table>
 *
 * <p>所以两边都要: {@link #code()} 是<b>稳定的、可分组</b>的机读键(与
 * {@code PlanOrigin.kind}、{@code BindReason.kind} 同一个形态),
 * {@link #narrative()} 是给人的那句话。平台只固定自己会用到的那几个 code
 * (见下面的常量), 第三方写自己的, 不需要改这里的任何一行。
 *
 * <h2>它们不能互相替代 —— 这一条特别值得写下来</h2>
 * 用 narrative 当 code 的实现会在第一次改文案时把历史数据分组搞乱;
 * 用 code 当 narrative 的实现会让她在 LLM context 里说 {@code "keep-current"} ——
 * <b>而那正是"把枚举搬回来"的症状</b>: 一个 agent 用内部代号说话。
 */
public record DecisionReason(String code, String narrative) {

    /** 选了一个候选意图去做。 */
    public static final String CODE_CHOSE = "chose-intention";

    /** 有候选, 但一个都做不了。 */
    public static final String CODE_NOTHING_FEASIBLE = "nothing-feasible";

    /** 压根没有候选 —— 她没什么要想的。 */
    public static final String CODE_NOTHING_TO_DO = "nothing-to-do";

    /** 决定继续当前正在做的事({@code KeepActive}) —— "收到消息但继续写作业"就是它。 */
    public static final String CODE_KEPT_CURRENT = "kept-current";

    public DecisionReason {
        Objects.requireNonNull(code, "决定理由必须有分类码 —— 见本类关于统计的说明");
        Objects.requireNonNull(narrative, "决定理由必须有一句人话 —— 它会进日志与 LLM context");
        code = code.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException(
                    "决定理由的分类码不能是空白 —— 一条无法分组的理由会让"
                            + "「她最近有多少次是被打扰的」这个问题无法回答");
        }
        narrative = narrative.trim();
    }

    public static DecisionReason of(String code, String narrative) {
        return new DecisionReason(code, narrative);
    }

    public static DecisionReason chose(String narrative) {
        return new DecisionReason(CODE_CHOSE, narrative);
    }

    public static DecisionReason nothingFeasible(String narrative) {
        return new DecisionReason(CODE_NOTHING_FEASIBLE, narrative);
    }

    public static DecisionReason nothingToDo(String narrative) {
        return new DecisionReason(CODE_NOTHING_TO_DO, narrative);
    }

    public static DecisionReason keptCurrent(String narrative) {
        return new DecisionReason(CODE_KEPT_CURRENT, narrative);
    }

    /** 是不是"她选择不做任何事"这一类 —— 决定本身仍然成立, 只是没有动作。 */
    public boolean isNoAction() {
        return CODE_NOTHING_TO_DO.equals(code) || CODE_NOTHING_FEASIBLE.equals(code);
    }

    public String describe() {
        return "[" + code + "] " + narrative;
    }

    @Override
    public String toString() {
        return describe();
    }
}
