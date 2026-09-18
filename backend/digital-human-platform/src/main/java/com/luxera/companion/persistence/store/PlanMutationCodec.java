package com.luxera.companion.persistence.store;

import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.TimeWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §7.3 —— {@code List<PlanMutation>} ⇄ {@code plan_revision.mutations_json}。
 *
 * <h2>为什么这一层必须手写, 而多态序列化器在这里<b>用不上</b></h2>
 * 三个理由, 前两个是硬阻塞:
 * <ol>
 *   <li><b>六个实现都没有 {@code @DomainType}。</b> {@code PlanMutation} 是
 *       {@code sealed interface}, 六个实现都是 record —— 而"是不是 record"
 *       与"有没有类型名"是两件事。没有注解, {@code PolymorphicSerializer.toMap}
 *       会给每一行打上 {@code _untyped: <类名>} 并告警, 而读回来只能是个
 *       {@code Map} —— 于是"存下去再读回来是同一个对象"这条要求直接不成立;</li>
 *   <li><b>{@code Insert} 与 {@code Replace} 里嵌着一整项计划。</b>
 *       {@code PlanMutation.Insert(PlanItem item, String reason)} ——
 *       而 {@code PlanItem} 里又一个开放接口 {@code PlanIntent}。
 *       也就是说, 一个 mutation 里嵌着一个<b>需要多态处理的子对象</b>,
 *       而那个子对象的形状由三方决定。这一层必须显式地把"怎么存一项计划"
 *       这件事委托给 {@link PlanItemCodec} —— Jackson 反射做不到这件事,
 *       它只会试着把整个图一起绑定, 然后在 {@code PlanIntent} 上失败;</li>
 *   <li><b>本仓的既有约定是不给领域类型加持久化注解。</b> {@code AbstractActivity}
 *       的 javadoc 把这条写得很清楚（"为什么这些类上没有 Jackson 注解"）:
 *       落库走另一条路, 多写一层显式映射的代价换来的是
 *       "数据库里的历史不会因为代码重构而失效"。本类就是那一层。</li>
 * </ol>
 *
 * <h2>{@code kind} 为什么是一个字符串 —— 它不是"域扩展枚举"的翻版</h2>
 * 六个取值由 {@code sealed interface} 在<b>编译期</b>穷尽:
 * 加第七种 mutation 会让 {@code PlanBoard} 的穷尽匹配编译不过,
 * 于是一个"忘了更新本编解码器"的错误会在<b>编译时</b>暴露, 而不是在读了半年历史之后。
 *
 * <p>这与 P4 要禁的东西有本质差别（P4 禁的是"用枚举承载由<b>世界/三方</b>决定的
 * 类型集合"）。"她做了哪些改动"是一个封闭的、由本设计定义的集合 ——
 * §9 验收标准里没有任何一条要求三方去扩展 {@code PlanMutation}
 * （三方扩展的是 {@code PlanIntent} / {@code Activity} / {@code WorldEvent}）。
 * 所以这里的字符串是<b>线格式</b>, 不是领域约束。
 *
 * <p>但必须承认它有一个代价, 所以要写清楚: 字符串是"格式的一部分",
 * 改了它就读不懂旧数据。因此取值<b>一旦写下就不能改</b>（不能把 {@code MOVE}
 * 改成 {@code Moved}）, 而 {@link #KIND_MOVE} 这类常量集中放在类顶部,
 * 让这件事有一个唯一的落点。
 *
 * <h2>为什么不用 {@code switch} 表达式</h2>
 * Java 17 的 {@code switch} 模式匹配仍是预览特性（构造约束: 本仓编译在 Java 17,
 * 不许开预览）。于是解码用 {@code instanceof} 链 ——
 * 最终那一条 {@code throw} 是<b>必要的</b>: 它是"六种之外还有第七种但没人更新这里"
 * 这个状态的唯一出口。
 */
public final class PlanMutationCodec {

    static final String KIND_INSERT = "INSERT";
    static final String KIND_REMOVE = "REMOVE";
    static final String KIND_MOVE = "MOVE";
    static final String KIND_RESIZE = "RESIZE";
    static final String KIND_REPLACE = "REPLACE";
    static final String KIND_KEEP_ACTIVE = "KEEP_ACTIVE";

    private static final String F_KIND = "kind";
    private static final String F_REASON = "reason";
    private static final String F_ITEM = "item";
    private static final String F_ITEM_ID = "itemId";
    private static final String F_REMOVED_ID = "removedId";
    private static final String F_WINDOW_START = "windowStart";
    private static final String F_WINDOW_END = "windowEnd";
    private static final String F_DURATION_MS = "durationMs";

    private final PlanItemCodec itemCodec;

    public PlanMutationCodec(PlanItemCodec itemCodec) {
        this.itemCodec = Objects.requireNonNull(itemCodec, "计划项编解码器不能为空");
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 一串改动 → 可写进 {@code mutations_json} 的列表。
     *
     * <p><b>顺序必须原样保留</b>: 一串 mutation 是有序的,
     * 而 {@code PlanBoard} 按顺序应用它们。重排序的后果是
     * "先插入再移动"变成"先移动再插入" —— 后者会去移动一个还不存在的项。
     */
    public List<Map<String, Object>> toList(List<PlanMutation> mutations) {
        Objects.requireNonNull(mutations, "要编码的改动列表不能为空");
        List<Map<String, Object>> out = new ArrayList<>(mutations.size());
        for (PlanMutation mutation : mutations) {
            out.add(toMap(mutation));
        }
        return out;
    }

    /** 一条改动 → 一个可嵌入的 Map。 */
    public Map<String, Object> toMap(PlanMutation mutation) {
        Objects.requireNonNull(mutation, "要编码的改动不能为空");
        Map<String, Object> body = new LinkedHashMap<>();

        if (mutation instanceof PlanMutation.Insert insert) {
            body.put(F_KIND, KIND_INSERT);
            body.put(F_REASON, insert.reason());
            body.put(F_ITEM, itemCodec.toEmbedded(insert.item()));
            return body;
        }
        if (mutation instanceof PlanMutation.Replace replace) {
            body.put(F_KIND, KIND_REPLACE);
            body.put(F_REASON, replace.reason());
            body.put(F_REMOVED_ID, replace.removed().value());
            body.put(F_ITEM, itemCodec.toEmbedded(replace.inserted()));
            return body;
        }
        if (mutation instanceof PlanMutation.Remove remove) {
            body.put(F_KIND, KIND_REMOVE);
            body.put(F_REASON, remove.reason());
            body.put(F_ITEM_ID, remove.itemId().value());
            return body;
        }
        if (mutation instanceof PlanMutation.Move move) {
            body.put(F_KIND, KIND_MOVE);
            body.put(F_REASON, move.reason());
            body.put(F_ITEM_ID, move.itemId().value());
            body.put(F_WINDOW_START, move.newWindow().start().toString());
            body.put(F_WINDOW_END, move.newWindow().end().toString());
            return body;
        }
        if (mutation instanceof PlanMutation.Resize resize) {
            body.put(F_KIND, KIND_RESIZE);
            body.put(F_REASON, resize.reason());
            body.put(F_ITEM_ID, resize.itemId().value());
            body.put(F_DURATION_MS, resize.newLength().toMillis());
            return body;
        }
        if (mutation instanceof PlanMutation.KeepActive keepActive) {
            body.put(F_KIND, KIND_KEEP_ACTIVE);
            body.put(F_REASON, keepActive.reason());
            body.put(F_ITEM_ID, keepActive.itemId().value());
            return body;
        }

        // 走到这里说明 sealed interface 多了第七个实现而本类没跟上。
        // 因为接口是 sealed, 这个分支在编译期是可见的（PlanBoard 的穷尽匹配会先报错）——
        // 但运行时也必须炸, 而不是把这条改动静默丢掉:
        // 丢掉一条 REMOVE 的后果是"她删掉的写作业在重启后又回来了"
        throw new IllegalStateException(
                "不认识的 PlanMutation 实现: " + mutation.getClass().getName()
                        + " —— sealed interface 加了新实现, 但 PlanMutationCodec 没跟上。"
                        + "不能静默跳过: 丢掉一条改动会让重排后的计划与历史不一致");
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 列表 → 一串改动。
     *
     * <p>返回的 {@code List} 是<b>不可变</b>的拷贝: {@code PlanRevision} 的
     * 规范构造器会再拷一次, 但这里先拷能保证"读出来的东西不会被调用方改"——
     * 而一份被改过的历史会让"她为什么改主意"这个问题在下一次读取时给出不同答案。
     */
    public List<PlanMutation> fromList(List<Map<String, Object>> bodies) {
        if (bodies == null) {
            return List.of();
        }
        List<PlanMutation> out = new ArrayList<>(bodies.size());
        for (Map<String, Object> body : bodies) {
            out.add(fromMap(body));
        }
        return List.copyOf(out);
    }

    /** 一个 Map → 一条改动。 */
    @SuppressWarnings("unchecked")
    public PlanMutation fromMap(Map<String, Object> body) {
        Objects.requireNonNull(body, "要解码的改动不能为空");
        String kind = String.valueOf(body.get(F_KIND));
        if (KIND_INSERT.equals(kind)) {
            return new PlanMutation.Insert(item(body), reason(body));
        }
        if (KIND_REPLACE.equals(kind)) {
            return new PlanMutation.Replace(itemId(body, F_REMOVED_ID), item(body), reason(body));
        }
        if (KIND_REMOVE.equals(kind)) {
            return new PlanMutation.Remove(itemId(body, F_ITEM_ID), reason(body));
        }
        if (KIND_MOVE.equals(kind)) {
            return new PlanMutation.Move(itemId(body, F_ITEM_ID),
                    TimeWindow.of(instant(body, F_WINDOW_START), instant(body, F_WINDOW_END)),
                    reason(body));
        }
        if (KIND_RESIZE.equals(kind)) {
            return new PlanMutation.Resize(itemId(body, F_ITEM_ID),
                    Duration.ofMillis(lng(body, F_DURATION_MS)), reason(body));
        }
        if (KIND_KEEP_ACTIVE.equals(kind)) {
            return new PlanMutation.KeepActive(itemId(body, F_ITEM_ID), reason(body));
        }
        throw new IllegalStateException(
                "不认识的改动种类 \"" + kind + "\"（字段: " + body.keySet() + "）。"
                        + "它可能是由更新版本的代码写下的 —— 而一条读不懂的改动"
                        + "不能当作'什么都没发生': 那会让她的计划在重启后悄悄回退一步。"
                        + "已知种类: " + KIND_INSERT + "/" + KIND_REMOVE + "/" + KIND_MOVE
                        + "/" + KIND_RESIZE + "/" + KIND_REPLACE + "/" + KIND_KEEP_ACTIVE);
    }

    // ─────────────────────────── 字段读取 ───────────────────────────

    private PlanItem item(Map<String, Object> body) {
        Object raw = body.get(F_ITEM);
        if (!(raw instanceof Map)) {
            throw new IllegalStateException("改动 " + body.get(F_KIND)
                    + " 缺少 " + F_ITEM + " 字段, 或它不是个对象 —— "
                    + "没有它就不知道插进来的是什么");
        }
        return itemCodec.fromEmbedded((Map<String, Object>) raw);
    }

    private static PlanItemId itemId(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            throw new IllegalStateException(
                    "改动 " + body.get(F_KIND) + " 缺少 " + key + " 字段 —— "
                            + "一条不知道作用于哪一项的改动无法被应用");
        }
        return PlanItemId.of(String.valueOf(raw));
    }

    private static String reason(Map<String, Object> body) {
        Object raw = body.get(F_REASON);
        // 理由可以为空字符串但不能缺 —— 缺字段说明写入路径有 bug。
        // 用空串兜住它: PlanMutation 的规范构造器只要求非 null,
        // 而"这个改动的理由读不出来"不该让整次恢复失败
        return raw == null ? "" : String.valueOf(raw);
    }

    private static Instant instant(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            throw new IllegalStateException("缺少时刻字段 " + key);
        }
        return Instant.parse(String.valueOf(raw));
    }

    private static long lng(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw == null) {
            throw new IllegalStateException("缺少数字字段 " + key);
        }
        return Long.parseLong(String.valueOf(raw).trim());
    }
}
