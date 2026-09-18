package com.luxera.companion.human.mind.relationship;

import java.util.Objects;

/**
 * V2.2 §3.4.6 —— <b>"账号 → 人物"这条绑定是怎么来的</b>。
 *
 * <h2>为什么这个字段是必需的</h2>
 * 因为 §3.4.6 最核心的那句话是一个<b>过程</b>要求, 而不是一个状态要求:
 *
 * <blockquote>
 * "她的通讯录是她自己长出来的, 不是从聊天平台同步下来的。"
 * </blockquote>
 *
 * <p>这句话如果没有 {@code BindReason}, 就<b>无法被验证</b>。她心里的
 * {@link PersonObject} 表无论怎么来的, 长出来都是那个样子 —— 从平台同步下来的是一张
 * "小明/小红/老王"的表, 她自己长出来的也是一张表。两者的区别<b>只在来源上</b>,
 * 而来源必须被记下来, 否则:
 *
 * <ul>
 *   <li>跑三个月后无法回答一个本该很容易的问题: "<b>这张通讯录里有多少人是我自己认识的?</b>"
 *       —— 而这正是这个设计相对于"直接同步平台好友列表"的唯一卖点;</li>
 *   <li>无法回放。回放时若不知道某个绑定是"创建时预置"还是"她在第 370 次对话里建立的",
 *       就无法验证那一次对话确实发生了作用。</li>
 * </ul>
 *
 * <h2>{@code kind} 与 {@code detail} 的分工</h2>
 * 与 {@code PlanOrigin} 同形: {@code kind} 用于分组统计, {@code detail} 用于回答"是谁/哪一次"。
 * <b>不要合成一个字符串</b> —— 合并之后按来源分组要靠前缀匹配, 而 detail 里
 * 只要出现分隔符就会分错。
 *
 * <h2>它为什么不封闭 —— 用 §1.3 P4 回答</h2>
 * 判据: 取值集合由本设计的内部逻辑决定, 还是由外部世界的多样性决定?
 * 是<b>后者</b>。写这份设计时能想到的绑定路径至少有五条(创建时预置、她自己问出来的、
 * 别人介绍的、她推断出来的、对方自报的), 而真实场景里还会出现"从一次共同经历里认出来的"、
 * "从她自己的记忆检索里对上的"等等。每一条都是一次<b>认识方式的创新</b>,
 * 而创新的发生地应该在实现里, 不应该逼平台改这里的枚举。
 * 所以用字符串 + 具名常量, 与 {@link Relationship#kind}、{@code PlanOrigin} 一致。
 */
public record BindReason(String kind, String detail) {

    /** 创建 agent 时的预置 —— 就是"用户(她的主人)"那一条, 见 {@code RelationshipGraph.bootstrap}。 */
    public static final String KIND_BOOTSTRAP = "bootstrap";

    /** 她自己在聊天过程中建立起来的 —— <b>这是 §3.4.6 真正想要那一类</b>。 */
    public static final String KIND_SELF_MET = "self-met";

    /** 别人介绍的 —— "这是我朋友, 你加一下"。 */
    public static final String KIND_INTRODUCED = "introduced";

    /** 对方自报的 —— 他在对话里说了自己是谁。 */
    public static final String KIND_TOLD = "told";

    /** 她推断出来的 —— 从已有记忆里对上的(两次出现的是同一个人)。 */
    public static final String KIND_INFERRED = "inferred";

    public BindReason {
        Objects.requireNonNull(kind, "绑定来源不能为空");
        Objects.requireNonNull(detail, "绑定细节不能为 null —— 没有细节时请用空字符串, "
                + "而不是 null: 这样按来源分组时不需要额外判空");
        kind = kind.trim();
        if (kind.isEmpty()) {
            throw new IllegalArgumentException(
                    "绑定来源不能是空白 —— 一条说不出来源的绑定, 会让"
                            + "「她的通讯录里有多少人是自己长出来的」这个问题无法回答");
        }
        detail = detail.trim();
    }

    public static BindReason bootstrap() {
        return new BindReason(KIND_BOOTSTRAP, "");
    }

    public static BindReason selfMet(String detail) {
        return new BindReason(KIND_SELF_MET, detail);
    }

    public static BindReason introduced(String detail) {
        return new BindReason(KIND_INTRODUCED, detail);
    }

    public static BindReason told(String detail) {
        return new BindReason(KIND_TOLD, detail);
    }

    public static BindReason inferred(String detail) {
        return new BindReason(KIND_INFERRED, detail);
    }

    /**
     * 这条绑定是<b>她自己</b>建立的吗。
     *
     * <p>预置的那一条(主人)不算 —— 那不是她认识的, 是别人替她认识的。
     * 这不是吹毛求疵: 统计"她自己长出来的通讯录有多大"时把预置那条算进去,
     * 就会让一个从没主动认识过任何人的 agent 看起来也有收获。
     */
    public boolean selfMade() {
        return KIND_SELF_MET.equals(kind) || KIND_TOLD.equals(kind) || KIND_INFERRED.equals(kind);
    }

    public String describe() {
        return detail.isEmpty() ? kind : kind + ":" + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
