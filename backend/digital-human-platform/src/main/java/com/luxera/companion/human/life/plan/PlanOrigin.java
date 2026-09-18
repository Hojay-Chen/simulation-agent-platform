package com.luxera.companion.human.life.plan;

import java.util.Objects;

/**
 * V2.2 §3.5.2 —— 这个计划项<b>是谁安排进来的</b>。
 *
 * <h2>为什么这个字段是必需的, 而不是"nice to have"</h2>
 * 因为它决定了两件在行为分析里必须能分开的事:
 * <ol>
 *   <li><b>她能不能改它。</b>用户安排的事和系统安排的事, 她能动的程度不同。
 *       一个 agent 随手删掉"用户要求她今晚 8 点打电话"这件事, 与删掉自己
 *       临时起意安排的"看会儿视频", 是完全不同性质的行为;</li>
 *   <li><b>"她为什么没做这件事"的答案不同。</b>如果是她自己安排的, 答案在她的决策里;
 *       如果是用户安排的, 答案可能在"她忘了"或"她选择不做"—— 而这两者对研究的意义
 *       天差地别。</li>
 * </ol>
 *
 * <h2>为什么是 record 而不是枚举</h2>
 * 三种来源（她自己 / 用户 / 系统）看起来是封闭的。但用户要求的这个架构里,
 * 还有第四种会真实出现的来源: <b>聊天平台</b>。她在聊天里答应了对方"我下午三点过去",
 * 于是产生一个计划项 —— 它既不是"她自己安排的"（是对别人的承诺），
 * 也不是"用户安排的"（用户是另一个人）。
 *
 * <p>枚举在这里的失败方式很典型: 第三方只能把它塞进 {@code SELF} 或 {@code SYSTEM},
 * 于是要么丢掉"这是对别人的承诺"这个语义, 要么让平台加一档。
 *
 * <h2>{@code kind} 与 {@code detail} 的分工</h2>
 * {@code kind} 用于分组与统计（"她今天有多少事是别人安排的"）;
 * {@code detail} 用于回答"是谁"（{@code "chat:account-7"}、{@code "user:1001"}）。
 * <b>不要把它们合成一个字符串</b>: 合成之后, 按来源分组就要靠字符串前缀匹配,
 * 而那在 detail 里恰好含冒号时会出错。
 */
public record PlanOrigin(String kind, String detail) {

    /** 她自己安排的 —— 她自己的主意、她自己的作息。 */
    public static final String KIND_SELF = "self";

    /** 用户（真人）安排的 —— 在关系网里, 用户是她的对话对象之一。 */
    public static final String KIND_USER = "user";

    /** 系统安排的 —— 由平台/规则注入, 比如"每天 8 点起床"。 */
    public static final String KIND_SYSTEM = "system";

    /** 别人（关系网里的第三方）促成的 —— 比如她在聊天里答应了什么。 */
    public static final String KIND_OTHER = "other";

    public static final PlanOrigin SELF = new PlanOrigin(KIND_SELF, "");
    public static final PlanOrigin SYSTEM = new PlanOrigin(KIND_SYSTEM, "");

    public PlanOrigin {
        Objects.requireNonNull(kind, "来源类型不能为空");
        Objects.requireNonNull(detail, "来源细节不能为 null —— 没有细节时请用空字符串, "
                + "而不是 null: 这样按来源分组时不需要额外判空");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("来源类型不能是空白");
        }
    }

    public static PlanOrigin self() {
        return SELF;
    }

    public static PlanOrigin system() {
        return SYSTEM;
    }

    public static PlanOrigin user(String userDetail) {
        return new PlanOrigin(KIND_USER, userDetail);
    }

    public static PlanOrigin other(String detail) {
        return new PlanOrigin(KIND_OTHER, detail);
    }

    public boolean isSelf() {
        return KIND_SELF.equals(kind);
    }

    /**
     * 她自己能不能自由改动这一项。
     *
     * <p>用户安排的事她仍然可以改 —— 真人也会。但重排器在改动它时需要更谨慎
     * （见 {@code PlanReplanner}），并且改动本身值得被记下来。
     */
    public boolean freelyEditable() {
        return isSelf() || KIND_SYSTEM.equals(kind);
    }

    public String describe() {
        return detail.isEmpty() ? kind : kind + ":" + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
