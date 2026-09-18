package com.luxera.companion.persistence.store;

import com.luxera.companion.human.life.plan.PlanConstraint;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanOrigin;
import com.luxera.companion.human.life.plan.PlanPriority;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.PlanItemRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §7.3 —— {@code PlanItem} ⇄ {@code plan_item} 那一行的编解码。
 *
 * <h2>本类存在的理由: 一个计划项会以<b>两种形态</b>被存下来</h2>
 * <table border="1">
 *   <tr><th>形态</th><th>在哪</th><th>为什么需要它</th></tr>
 *   <tr>
 *     <td><b>实体形态</b></td><td>{@code plan_item} 表的一行</td>
 *     <td>当前计划的项要能被 {@code dueAt(t)} 那样的查询扫到 —— 那需要真正的列与索引</td>
 *   </tr>
 *   <tr>
 *     <td><b>嵌入形态</b></td><td>{@code plan_revision.mutations_json} 里的一个对象</td>
 *     <td>{@code PlanMutation.Insert} / {@code Replace} <b>各自嵌着整整一个
 *         {@code PlanItem}}</b>。不存它, "这一次重排插进来的是什么"就丢了</td>
 *   </tr>
 * </table>
 *
 * <p>两种形态承载<b>同样的信息</b>, 这是必须的: 从 mutation 重放出来的计划,
 * 必须与从表里读出来的计划逐字段一致。否则"这条历史计划长什么样"会取决于
 * 你是用哪条路读的 —— 而那种分歧没有任何日志能指向根因。
 * 于是"哪些字段要写"这件事在本类里只定义<b>一次</b>:
 * {@link #toRecord} 与 {@link #toEmbedded} 写的是同一组字段, 只是落点不同。
 *
 * <h2>为什么不能直接把 {@code PlanItem} 交给 Jackson（哪怕是多态序列化器）</h2>
 * 因为 {@code PlanItem} 里嵌着一个 <b>开放接口</b> {@code PlanIntent}:
 * <pre>
 *   PlanItem → intent(PlanIntent, 由三方实现) → 它自己的字段
 * </pre>
 * 让 Jackson 反射绑定整个对象图, 等于让<b>持久化格式跟着类的形状走</b> ——
 * 于是重构一次字段名就要一次数据迁移, 而迁移出错的方式是
 * "某一天某些历史计划读不出来"。§7.3 要的正是相反的东西:
 * 类型名走关系型列、内容走 JSON, 两者由<b>显式的</b>映射绑定。
 *
 * <h2>{@link #fromRecord} 会抛, 不是宽容读</h2>
 * 与 {@code PlanConstraintCodec} 同一条理由: 恢复"她此刻的计划"时少了一个项,
 * 表现是"她忘了自己下午要去实验室"——一个看起来完全正常、只是偶尔丢事的人。
 * 需要宽容读的场景（回放历史做行为分析）应当用 {@code tryRead} 那条路,
 * 而那条路在 {@code PlanStore} 里是<b>另一个方法</b>, 不是这里的默认行为。
 */
public final class PlanItemCodec {

    private static final String F_ID = "id";
    private static final String F_INTENT = "intent";
    private static final String F_WINDOW_START = "windowStart";
    private static final String F_WINDOW_END = "windowEnd";
    private static final String F_EXPECTED_DURATION_MS = "expectedDurationMs";
    private static final String F_CONSTRAINTS = "constraints";
    private static final String F_PRIORITY_LEVEL = "priorityLevel";
    private static final String F_PRIORITY_LABEL = "priorityLabel";
    private static final String F_LIFECYCLE = "lifecycle";
    private static final String F_ORIGIN_KIND = "originKind";
    private static final String F_ORIGIN_DETAIL = "originDetail";
    private static final String F_DEPENDENCIES = "dependencies";
    private static final String F_FIXED = "fixed";
    private static final String F_CREATED_IN_REVISION = "createdInRevision";
    private static final String F_NOTE = "note";

    private final DomainPayloadCodec codec;
    private final PlanConstraintCodec constraintCodec;

    public PlanItemCodec(DomainPayloadCodec codec, PlanConstraintCodec constraintCodec) {
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
        this.constraintCodec = Objects.requireNonNull(constraintCodec, "约束编解码器不能为空");
    }

    // ─────────────────────────── 实体形态 ───────────────────────────

    /**
     * 一项计划 → 一行 {@code plan_item}。
     *
     * <p>{@code humanId} 是<b>冗余列</b>（见 {@link PlanItemRecord#getHumanId()}）——
     * 它由写入方从所属的 {@code PlanRevision} 上取。本方法不做校验:
     * 校验不属于编解码的职责, 而且一个"校验失败"在这里能做的只有抛异常,
     * 那会让一次正常写入变成一次崩溃。
     */
    public PlanItemRecord toRecord(PlanItem item, String humanId, String revisionId) {
        Objects.requireNonNull(item, "要落库的计划项不能为空");
        Objects.requireNonNull(humanId, "计划项必须属于某个人 —— 没有人的计划项查不出来");
        Objects.requireNonNull(revisionId, "计划项必须属于某一版计划");

        DomainPayloadCodec.PersistedForm intent = codec.write(item.intent());

        PlanItemRecord record = new PlanItemRecord();
        // 主键是复合的（哪一版 + 哪一项）—— 同一项会在多版里各有一行,
        // 见 {@link PlanItemRecord} 的类注释"主键是复合的"。
        // 拼法只定义在 PlanItemRecord.rowKeyOf 一处, 免得写与读拼岔
        record.setId(PlanItemRecord.rowKeyOf(revisionId, item.id().value()));
        record.setItemId(item.id().value());
        record.setRevisionId(revisionId);
        record.setHumanId(humanId);
        record.setIntentTypeNamespace(intent.typeNamespace());
        record.setIntentTypeName(intent.typeName());
        record.setIntentTypeVersion(intent.majorVersion());
        record.setIntentJson(intent.payload());
        record.setStartAt(item.window().start());
        record.setEndAt(item.window().end());
        record.setDurationMs(item.expectedDuration().toMillis());
        record.setPriorityLevel(item.priority().level());
        record.setPriorityLabel(item.priority().label());
        record.setLifecycle(item.lifecycle().name());
        record.setOriginKind(item.origin().kind());
        record.setOriginDetail(item.origin().detail());
        record.setDependencies(ids(item.dependencies()));
        record.setFixedSlot(item.fixed());
        record.setCreatedInRevision(item.createdInRevision());
        record.setNote(item.note());
        return record;
    }

    /** 一行 → 一项计划。会抛（见类注释）。 */
    public PlanItem fromRecord(PlanItemRecord record) {
        Objects.requireNonNull(record, "要恢复的行不能为空");
        PlanIntent intent = codec.read(record.getIntentTypeNamespace(), record.getIntentTypeName(),
                record.getIntentTypeVersion(), record.getIntentJson(), PlanIntent.class, true);
        return build(intent,
                record.getItemId(),
                record.getStartAt(),
                record.getEndAt(),
                record.getDurationMs(),
                record.getPriorityLevel(),
                record.getPriorityLabel(),
                record.getLifecycle(),
                record.getOriginKind(),
                record.getOriginDetail(),
                record.getDependencies(),
                record.isFixedSlot(),
                record.getCreatedInRevision(),
                record.getNote());
    }

    // ─────────────────────────── 嵌入形态 ───────────────────────────

    /**
     * 一项计划 → 可嵌进 {@code mutations_json} 的 Map。
     *
     * <p>字段名与 {@link #toRecord} 写进列的是同一组事实, 只是这里用
     * {@code camelCase} 的 JSON 键名 —— 因为 JSON 的读者是人与诊断面板,
     * 而列名的读者是 SQL。
     */
    public Map<String, Object> toEmbedded(PlanItem item) {
        Objects.requireNonNull(item, "要编码的计划项不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(F_ID, item.id().value());
        body.put(F_INTENT, codec.write(item.intent()).payload());
        body.put(F_WINDOW_START, item.window().start().toString());
        body.put(F_WINDOW_END, item.window().end().toString());
        body.put(F_EXPECTED_DURATION_MS, item.expectedDuration().toMillis());
        List<Map<String, Object>> constraints = new ArrayList<>(item.constraints().size());
        for (PlanConstraint c : item.constraints()) {
            constraints.add(constraintCodec.toEmbedded(c));
        }
        body.put(F_CONSTRAINTS, constraints);
        body.put(F_PRIORITY_LEVEL, item.priority().level());
        body.put(F_PRIORITY_LABEL, item.priority().label());
        body.put(F_LIFECYCLE, item.lifecycle().name());
        body.put(F_ORIGIN_KIND, item.origin().kind());
        body.put(F_ORIGIN_DETAIL, item.origin().detail());
        body.put(F_DEPENDENCIES, ids(item.dependencies()));
        body.put(F_FIXED, item.fixed());
        body.put(F_CREATED_IN_REVISION, item.createdInRevision());
        body.put(F_NOTE, item.note());
        return body;
    }

    /** 嵌入形态 → 一项计划。会抛（见类注释）。 */
    @SuppressWarnings("unchecked")
    public PlanItem fromEmbedded(Map<String, Object> body) {
        Objects.requireNonNull(body, "要解码的计划项不能为空");
        Object rawIntent = body.get(F_INTENT);
        if (!(rawIntent instanceof Map)) {
            throw new IllegalStateException(
                    "嵌入形态的计划项缺少 " + F_INTENT + " 字段, 或它不是个对象: "
                            + body.keySet() + " —— 没有意图的计划项既描述不了也执行不了");
        }
        PlanIntent intent = codec.read(null, null, null,
                (Map<String, Object>) rawIntent, PlanIntent.class, true);

        List<PlanConstraint> constraints = new ArrayList<>();
        Object rawConstraints = body.get(F_CONSTRAINTS);
        if (rawConstraints instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map) {
                    constraints.add(constraintCodec.fromEmbedded((Map<String, Object>) element));
                }
            }
        }

        PlanItem base = build(intent,
                str(body, F_ID),
                instant(body, F_WINDOW_START),
                instant(body, F_WINDOW_END),
                lng(body, F_EXPECTED_DURATION_MS),
                integer(body, F_PRIORITY_LEVEL),
                str(body, F_PRIORITY_LABEL),
                str(body, F_LIFECYCLE),
                str(body, F_ORIGIN_KIND),
                str(body, F_ORIGIN_DETAIL),
                strList(body, F_DEPENDENCIES),
                bool(body, F_FIXED),
                lng(body, F_CREATED_IN_REVISION),
                str(body, F_NOTE));
        return constraints.isEmpty() ? base : base.withConstraints(constraints);
    }

    // ─────────────────────────── 装配 ───────────────────────────

    private static PlanItem build(PlanIntent intent, String id, Instant start, Instant end,
                                  long durationMs, int priorityLevel, String priorityLabel,
                                  String lifecycle, String originKind, String originDetail,
                                  List<String> dependencies, boolean fixed,
                                  long createdInRevision, String note) {
        return new PlanItem(
                PlanItemId.of(id),
                intent,
                TimeWindow.of(start, end),
                Duration.ofMillis(durationMs),
                List.of(),
                PlanPriority.of(priorityLevel, priorityLabel),
                PlanLifecycle.valueOf(lifecycle),
                new PlanOrigin(originKind, originDetail == null ? "" : originDetail),
                idsToItemIds(dependencies),
                fixed,
                createdInRevision,
                note);
    }

    private static List<String> ids(List<PlanItemId> values) {
        List<String> out = new ArrayList<>(values.size());
        for (PlanItemId id : values) {
            out.add(id.value());
        }
        return out;
    }

    private static List<PlanItemId> idsToItemIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<PlanItemId> out = new ArrayList<>(values.size());
        for (String v : values) {
            out.add(PlanItemId.of(v));
        }
        return out;
    }

    // ─────────────────────────── 读原始值的工具 ───────────────────────────
    //
    // 这一组刻意写得"啰嗦且会抛": 嵌入形态是 JSON, 而 JSON 里的类型是可以从外面
    // 写坏的（导入脚本、人工修数据）。一个静默的 ClassCastException 或一个
    // 悄悄变成 0 的缺失字段, 会让"她下午的安排"变成一段谁也解释不了的时间。

    private static String str(Map<String, Object> body, String key) {
        Object v = require(body, key);
        return String.valueOf(v);
    }

    private static Instant instant(Map<String, Object> body, String key) {
        Object v = require(body, key);
        try {
            return Instant.parse(String.valueOf(v));
        } catch (RuntimeException e) {
            throw new IllegalStateException("字段 " + key + " 不是合法的 ISO-8601 时刻: " + v, e);
        }
    }

    private static long lng(Map<String, Object> body, String key) {
        Object v = require(body, key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("字段 " + key + " 不是整数: " + v, e);
        }
    }

    private static int integer(Map<String, Object> body, String key) {
        return (int) lng(body, key);
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object v = require(body, key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> strList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(element == null ? null : String.valueOf(element));
            }
            return out;
        }
        throw new IllegalStateException("字段 " + key + " 应当是数组, 实际是 "
                + v.getClass().getName());
    }

    /**
     * 取一个必需字段, 缺了就抛。
     *
     * <p>刻意<b>不</b>提供"缺了就用默认值"的宽松版本: 一个默认值会让
     * "行写坏了一半"变成"她有一项时间不明的计划"。而失败在这里是好事 ——
     * 它把问题挡在恢复流程的入口, 而不是让它渗进她的生活。
     */
    private static Object require(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            throw new IllegalStateException("嵌入形态的计划项缺少必需字段 " + key
                    + ", 实际字段有: " + body.keySet());
        }
        return v;
    }
}
