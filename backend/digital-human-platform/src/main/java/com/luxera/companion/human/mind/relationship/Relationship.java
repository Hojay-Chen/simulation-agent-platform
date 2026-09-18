package com.luxera.companion.human.mind.relationship;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.6 —— <b>她与某个 {@link PersonObject} 的关系</b>(同学/好友/导师……)。
 *
 * <h2>它答的问题只有一个: "这个人对我来说是什么"</h2>
 * 它与 {@link PersonObject} 的分工是"关系"与"人"的分工, 两者必须能各自变化:
 * 一个她认识很久的人可以因为一件事变得疏远(关系变, 人不变);
 * 一个陌生人的名字可以被她改掉(人变, 关系不变)。
 * 把两者合成一个对象, 会让上面两件事都变成"改那个人", 于是行为分析里
 * "关系为什么变了"与"她为什么改口了"再也分不开。
 *
 * <h2>{@code kind} 为什么是字符串而不是枚举 —— 这条要用 §1.3 P4 认真回答</h2>
 * 判据是: 取值集合由<b>本设计的内部逻辑</b>决定, 还是由<b>外部世界的多样性</b>决定?
 * 这是<b>后者</b>, 而且这个结论有具体证据:
 *
 * <ul>
 *   <li>§3.4.6 的初始化流程写着"relation = 由 persona 指定(朋友 / 恋人 / 同学……)"
 *       —— <b>省略号是文档自己写的</b>。关系种类的清单不在这份设计里, 它由用户
 *       创建 agent 时填的那句话决定;</li>
 *   <li>真实世界里还会出现: 网友、房东、病友、前任、师父、债主……
 *       每出现一个, 枚举就要加一档, 加档意味着<b>平台发一次版</b>;</li>
 *   <li>枚举在这里的失败方式很具体: 第三方只能把它塞进最像的那一档,
 *       于是"前任"被记成"朋友", 而关系分析从此是错的, 且错得看不出来。</li>
 * </ul>
 *
 * <p>所以这里用与 {@code PlanOrigin} 同样的形态: <b>字符串 + 几个具名常量</b>。
 * 常量不是封闭的取值集合, 它们只是"本设计自己会用到的那几个"的拼写便利,
 * 第三方写自己的字符串不需要改这里的任何一行。
 *
 * <h2>{@code closeness} 与 {@code trust} 为什么是两个数</h2>
 * 因为它们可以朝相反方向走, 而合成一个"好感度"会把这件事抹平:
 * 一个人可以很亲近但不再被信任(朝夕相处却刚骗过她), 也可以很信任但并不亲近
 * (一位只见过两次的医生)。合成之后, 上面两种状态在她心里是同一个值,
 * 而"她为什么突然开始防着他"这个问题就失去了答案。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不记录变化</b>。当前值在这里, 变迁史在 {@code RelationshipMemory} 里。
 *       两处都能写的话, 迟早不一致;</li>
 *   <li><b>不解释自己</b>。没有 {@code reason} 字段 —— 一个关系当前值没有"为什么",
 *       只有"怎么变成的"。要理由去看关系记忆。</li>
 * </ul>
 */
public record Relationship(
        PersonId personId,
        String kind,
        double closeness,
        double trust,
        Instant since,
        Instant updatedAt) {

    /** 还没建立起来的认识 —— 她只知道有这么个账号。 */
    public static final String KIND_STRANGER = "stranger";

    /** 认识, 但还不算朋友。 */
    public static final String KIND_ACQUAINTANCE = "acquaintance";

    /** 朋友。 */
    public static final String KIND_FRIEND = "friend";

    /** 同学 / 同事 —— 同一场景里认识的人。 */
    public static final String KIND_PEER = "peer";

    /** 家里人。 */
    public static final String KIND_FAMILY = "family";

    /** 指导者 / 被指导者。 */
    public static final String KIND_MENTOR = "mentor";

    /**
     * 她的主人 —— 由 persona 在创建时指定, 见 {@link RelationshipGraph#bootstrap}。
     *
     * <p>它<b>不是</b>一档更高的关系, 只是"这个关系不是她自己长出来的, 是配的"这件事的标记。
     * 这很重要: 一个把 owner 写成特殊档位的实现, 会让"她与主人的关系后来变差了"
     * 这件事在数据上无处安放。
     */
    public static final String KIND_OWNER = "owner";

    public Relationship {
        Objects.requireNonNull(personId, "关系必须挂在某个人身上 —— 见 PersonId");
        Objects.requireNonNull(kind, "关系必须说明是什么关系 —— 见本类关于 kind 的论证");
        Objects.requireNonNull(since, "关系必须有起点时刻 —— 不许读系统时钟");
        Objects.requireNonNull(updatedAt, "关系必须有更新时刻 —— 不许读系统时钟");
        kind = kind.trim();
        if (kind.isEmpty()) {
            throw new IllegalArgumentException(
                    "关系种类不能是空白 —— 空白的关系会让" + "她对这个人是什么人"
                            + "这个问题没有答案, 而它是有答案的: 至少是陌生人");
        }
        requireUnit(closeness, "亲密程度");
        requireUnit(trust, "信任程度");
        if (updatedAt.isBefore(since)) {
            throw new IllegalArgumentException(
                    "更新时刻 " + updatedAt + " 早于关系起点 " + since
                            + " —— 一条时间倒流的关系会让变迁史无法按时间排序");
        }
    }

    /** 一段刚建立起来的、还不算认识的关系。 */
    public static Relationship stranger(PersonId personId, Instant at) {
        return new Relationship(personId, KIND_STRANGER, 0.0, 0.0, at, at);
    }

    public static Relationship of(PersonId personId, String kind, double closeness, double trust,
                                  Instant since, Instant updatedAt) {
        return new Relationship(personId, kind, closeness, trust, since, updatedAt);
    }

    /**
     * 关系动了一下 —— 亲密度与信任各走一段。
     *
     * <p>它是<b>增量</b>而不是"设成某个值", 因为关系的每一次变化都是一个事件
     * ("那次对话之后她更信任他了"), 而事件给的是增量。给绝对值等于要求调用方
     * 先读一次当前值 —— 那会引入一个"读-改-写"的窗口, 而这一层没有并发保护, 也
     * 不需要它: 增量是幂等的输入, 绝对值不是。
     *
     * <p>两个值都被夹在 {@code [0, 1]}。夹紧发生在<b>这里</b>, 而不是在一堆调用点 ——
     * 后者迟早会漏一处, 而漏掉的那处会让亲密度变成 -3.7 这种东西,
     * 它不会报错, 只会让注意力折扣算出一个荒谬的数字。
     */
    public Relationship adjusted(double closenessDelta, double trustDelta, Instant at) {
        return new Relationship(personId, kind, clamp01(closeness + closenessDelta),
                clamp01(trust + trustDelta), since, at);
    }

    /** 她对这个人关系的<b>说法</b>变了 —— 比如从"同学"变成"朋友"。 */
    public Relationship reclassified(String newKind, Instant at) {
        return new Relationship(personId, newKind, closeness, trust, since, at);
    }

    public boolean isStranger() {
        return KIND_STRANGER.equals(kind);
    }

    /**
     * 亲密度的一档说法 —— <b>只用于让她把话说得像人话</b>
     * ({@code "一个很亲近的人"} / {@code "不太熟的人"})。
     *
     * <p>它<b>不是</b>用来做判断的: 任何 {@code if (tier == CLOSE)} 这样的逻辑
     * 都应该直接比较 {@link #closeness()}。一个既做判断又做措辞的分档,
     * 会在有人改了分档边界时同时改掉行为 —— 而那时没人会想到去查这里。
     */
    public String closenessLabel() {
        if (closeness >= 0.75) {
            return "很亲近的人";
        }
        if (closeness >= 0.45) {
            return "关系不错的人";
        }
        if (closeness >= 0.15) {
            return "认识的人";
        }
        return "不太熟的人";
    }

    public String describe() {
        return personId.value() + " 是" + closenessLabel() + "(" + kind
                + ", 亲密 " + round(closeness) + ", 信任 " + round(trust) + ")";
    }

    @Override
    public String toString() {
        return describe();
    }

    private static void requireUnit(double value, String what) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    what + "必须归一化到 [0, 1], 收到 " + value
                            + " —— 未归一化的关系数值会让注意力折扣算出 [0,1] 之外的分数, "
                            + "而那个分数不会报错, 只会让" + "她注意到什么" + "变得无法解释");
        }
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
