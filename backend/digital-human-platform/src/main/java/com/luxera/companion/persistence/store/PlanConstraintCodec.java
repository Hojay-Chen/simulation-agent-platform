package com.luxera.companion.persistence.store;

import com.luxera.companion.human.life.plan.PlanConstraint;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.PlanConstraintRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §7.3 —— {@code PlanConstraint} ⇄ {@code plan_constraint} 那一行的编解码。
 *
 * <h2>为什么约束需要<b>手写</b>这一层, 而 {@code PolymorphicSerializer} 不够</h2>
 * 三个具体理由, 每一个都会在"直接用序列化器"的版本上表现出来:
 * <ol>
 *   <li><b>{@code PlanConstraint} 没有 {@code typeId()} 方法。</b>
 *       {@code WorldObject} 与 {@code WorldEvent} 都有（"实现类通常写
 *       {@code return TYPE;} 一个 static final 常量"）, 而 {@code PlanConstraint}
 *       只有 {@code id()} / {@code evaluate()} / {@code describe()}。
 *       于是类型名只能由 {@code DomainTypeRegistry.typeIdOf(obj)} 从
 *       {@code @DomainType} 注解反推 —— 这没问题, 但意味着"没标注解的实现"
 *       会被写成 {@code _untyped}。下面 {@link #fromRecord} 因此必须
 *       <b>显式失败</b>而不是返回空;</li>
 *   <li><b>约束要落到自己的表, 而不是只活在 {@code plan_item.constraints} 的 JSON 里。</b>
 *       §7.2 给了 {@code plan_constraint} 一张表, 而它服务的查询是
 *       "这一版被哪些约束管着"（诊断面板与行为分析）。若约束只活在 JSON 里,
 *       那个查询就只能全表读 + 内存过滤;</li>
 *   <li><b>同一个约束会出现在两处</b>: 表里一行（{@code plan_constraint}）,
 *       以及 {@code PlanMutation.Insert} 嵌着的那一整项里的一个列表元素。
 *       两处的编码必须<b>一模一样</b>, 否则"从 mutation 重放出来的计划"与
 *       "从表里读出来的计划"会给出不同的约束集合。本类因此把两种形态
 *       （{@link #toRecord} / {@link #toEmbedded}）放在同一个类里,
 *       让它们共用同一份"哪些字段要写"的定义。</li>
 * </ol>
 *
 * <h2>为什么 {@link #fromRecord} 用会抛的 {@code read} 而不是 {@code tryRead}</h2>
 * 见 {@code DomainPayloadCodec.tryRead} 的对照表, 其中第一行完全适用于这里:
 * <pre>
 *   恢复"她此刻的计划受哪些约束管着"时读不出来  →  读出来的计划会在
 *   "她以为有约束、实际上没有"的状态下被排程, 于是她会去做一件本该被挡住的事。
 *   而一个"少了几条约束"的 agent 看起来完全正常 —— 她只是偶尔做出不该做的事。
 * </pre>
 * 卸载一个三方插件之后历史约束读不回来, 是一个需要人处理的处境,
 * 而不是一个可以被静默跳过的处境。
 */
public final class PlanConstraintCodec {

    /** 嵌入形态里 {@code _type} 之外的字段名 —— 集中在这里, 免得写两遍写岔。 */
    private static final String FIELD_ID = "id";
    private static final String FIELD_CONSTRAINT_JSON = "constraintJson";

    private final DomainPayloadCodec codec;

    public PlanConstraintCodec(DomainPayloadCodec codec) {
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
    }

    // ─────────────────────────── 实体形态 ───────────────────────────

    /**
     * 一条约束 → 一行 {@code plan_constraint}。
     *
     * @param revisionId 属于哪一版。全局约束与项约束都必须有它（见
     *                   {@link PlanConstraintRecord} 的类注释）
     * @param planItemId 挂在哪一项上。<b>传 {@code null} 表示这是这一版的全局约束</b>
     */
    public PlanConstraintRecord toRecord(PlanConstraint constraint, String revisionId,
                                        String planItemId) {
        Objects.requireNonNull(constraint, "要落库的约束不能为空");
        Objects.requireNonNull(revisionId, "约束必须属于某一版计划 —— 没有版本的约束找不回来");

        DomainPayloadCodec.PersistedForm form = codec.write(constraint);
        PlanConstraintRecord record = new PlanConstraintRecord();
        // 复合主键 —— 同一条全局约束会出现在它之后每一版的行里（{@code PlanRevision.next}
        // 把 constraints 原样传给下一版）, 所以 {@code ConstraintId} 不能当行键。
        // 拼法只定义在 PlanConstraintRecord.rowKeyOf 一处
        record.setId(PlanConstraintRecord.rowKeyOf(revisionId, constraint.id().value()));
        record.setConstraintId(constraint.id().value());
        record.setRevisionId(revisionId);
        record.setPlanItemId(planItemId);
        record.setConstraintNamespace(form.typeNamespace());
        record.setConstraintName(form.typeName());
        record.setConstraintVersion(form.majorVersion());
        record.setConstraintJson(form.payload());
        return record;
    }

    /**
     * 一行 → 一条约束。
     *
     * @throws com.luxera.companion.registry.PolymorphicSerializer.UnknownDomainTypeException
     *         这个约束的类型没有任何类声明过。见类注释"为什么用会抛的 read"
     */
    public PlanConstraint fromRecord(PlanConstraintRecord record) {
        Objects.requireNonNull(record, "要恢复的行不能为空");
        return codec.read(record.getConstraintNamespace(), record.getConstraintName(),
                record.getConstraintVersion(), record.getConstraintJson(),
                PlanConstraint.class, true);
    }

    // ─────────────────────────── 嵌入形态 ───────────────────────────

    /**
     * 一条约束 → 可嵌进 {@code mutations_json} 的 Map。
     *
     * <p>{@code id} 与载荷里的类型名都在（后者由 {@code codec.write} 放进
     * {@code _type}）—— 于是嵌入形态与实体形态承载<b>同样的信息</b>,
     * 只是前者没有关系型列可用。
     */
    public Map<String, Object> toEmbedded(PlanConstraint constraint) {
        Objects.requireNonNull(constraint, "要编码的约束不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_ID, constraint.id().value());
        body.put(FIELD_CONSTRAINT_JSON, codec.write(constraint).payload());
        return body;
    }

    /**
     * 嵌入形态 → 一条约束。
     *
     * <p>嵌入形态里<b>没有</b>三个关系型列, 于是类型只能靠载荷里的 {@code _type} 认
     * —— 这正是 {@code DomainPayloadCodec.withAuthoritativeType} 的"三个列全空时回退"
     * 分支。它在这里不是"历史遗留兼容", 而是<b>唯一可能的读法</b>。
     */
    @SuppressWarnings("unchecked")
    public PlanConstraint fromEmbedded(Map<String, Object> body) {
        Objects.requireNonNull(body, "要解码的约束不能为空");
        Object raw = body.get(FIELD_CONSTRAINT_JSON);
        if (!(raw instanceof Map)) {
            throw new IllegalStateException(
                    "嵌入形态的约束缺少 " + FIELD_CONSTRAINT_JSON + " 字段, 或它不是个对象: "
                            + body.keySet() + " —— 一条读不回来的约束会让计划在"
                            + "'没有约束保护'的状态下被排出来");
        }
        return codec.read(null, null, null,
                (Map<String, Object>) raw, PlanConstraint.class, true);
    }

    /**
     * 从嵌入形态里取约束的 id —— <b>不用把约束反序列化出来</b>。
     *
     * <p>诊断与"这一项上挂了哪些约束"这类只读 id 的场景用。
     * 它的价值在于: 即使某条约束的类型已经读不回来了, 仍然能列出
     * "这里有一条认不出来的约束"—— 而不是整段信息消失。
     */
    public static String idOfEmbedded(Map<String, Object> body) {
        Object id = body == null ? null : body.get(FIELD_ID);
        return id == null ? null : String.valueOf(id);
    }
}
