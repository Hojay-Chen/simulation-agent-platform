package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §7.2 —— {@code activity_record}: <b>她真的做过的那件事, 摊平之后的样子</b>。
 *
 * <h2>这张表是 {@code AbstractActivity} 的 javadoc 明确承诺过的那一份</h2>
 * 那段话是这么写的（{@code human/life/activity/AbstractActivity} 的"为什么这些类上没有
 * Jackson 注解"一节）:
 * <blockquote>
 * 落库走的是另一条路: 活动被摊成一个扁平的 {@code activity_record}
 * （JSONB, 类型名由 {@code DomainType} 那个机制记录, 与 {@code world_event} 完全同构）。
 * {@code Activity} 与那份记录之间的转换由持久化层显式写出来 —— 多写一层映射的代价,
 * 换来的是"数据库里的历史不会因为代码重构而失效"。<b>这部分随 §7 的表设计一起落地。</b>
 * </blockquote>
 * 本类就是那个承诺的兑现物。
 *
 * <h2>"扁平"是什么意思, 它买到了什么</h2>
 * 一个 {@code Activity} 里嵌着一个 {@code PlanIntent}（开放接口, 由三方实现）,
 * 而意图里可能又嵌着别的东西。如果把整个对象图交给 Jackson 反射绑定:
 * <pre>
 *   Activity → intent → (三方的 HomeworkIntent) → 它自己的字段
 * </pre>
 * 那么<b>持久化格式就跟着类的形状走</b>了 —— 重构一次字段名就要写一次数据迁移,
 * 而迁移脚本出错的方式是"某一天某些历史活动读不出来"。
 *
 * <p>摊平之后, 这一行只有三类列, 每一类都有明确的归属:
 * <table border="1">
 *   <tr><th>列组</th><th>装什么</th><th>谁决定它的形状</th></tr>
 *   <tr>
 *     <td>{@code id} / {@code human_id} / {@code plan_item_id} / {@code started_at} /
 *         {@code ended_at} / {@code state} / {@code closing_note} / {@code final_progress}</td>
 *     <td>{@link com.luxera.companion.human.life.activity.AbstractActivity.CommonFields}
 *         的八个字段 —— 由本平台决定, 因此它们是<b>真正的列</b></td>
 *     <td>平台（这张表的主语是"她做过什么", 而那是平台的定义）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code activity_type_*} / {@code intent_type_*} / {@code intent_json}</td>
 *     <td>多态形状的那部分</td>
 *     <td>类型名由实现类自己声明（{@code @DomainType}）</td>
 *   </tr>
 * </table>
 *
 * <p>这个划分不是审美: 它决定了哪些查询能走索引。{@code state} 是一列而不是
 * JSON 里的一个字段, 于是"她现在有没有在做的事"（重启后的第一问）是一次
 * {@code WHERE human_id = ? AND state = 'RUNNING'} —— 而如果它埋在 JSON 里,
 * 那句话要写成 JSON 提取, 且无法用普通索引。
 *
 * <h2>{@code activity_type} 与 {@code intent_type} 为什么都要存</h2>
 * 它们回答不同的问题, 而且其中一个不能由另一个推出来:
 * <ul>
 *   <li>{@code activity_type}（{@code life.activity.sleep}）—— <b>她实际在做的是哪一类活动</b>。
 *       注意它与 {@code intent.activityType()} 声明的不一定相同: 意图说了
 *       {@code life.activity.gaming}, 而 {@code ActivityFactory} 因为三方没注册而
 *       落到了 {@code OtherActivity}。两者都存下来, 才能回答
 *       "有多少次她本来要做的事没做成" —— 而那是"某个三方忘了注册"的唯一可查信号
 *       （见 {@code ActivityFactory} 关于 {@code unregisteredUses} 的那段）;</li>
 *   <li>{@code intent_type}（{@code life.intent.homework}）—— <b>她当时想的是什么</b>。
 *       它是 {@code intent_json} 的读法, 是恢复活动的必需品。</li>
 * </ul>
 *
 * <h2>这张表只增不改（除了收尾那一次）</h2>
 * {@code AbstractActivity} 的接口纪律是"已经发生过的执行是历史, 她能改的只有
 * '接下来做什么'"。落在这张表上就是: 一行一旦写入, 只有 {@code state} /
 * {@code ended_at} / {@code closing_note} / {@code final_progress} 这四个字段
 * 会在收尾时被填上一次, <b>此后永不再改</b>。没有 UPDATE 的 delete 语义 ——
 * 这张表里没有"删除一次活动"这个操作。
 */
@Entity
@Table(name = "activity_record", indexes = {
        // ① "她这一天做了什么" —— 时间轴视图与行为分析的主查询。等值列在前、范围列在后。
        @Index(name = "idx_activity_human_time", columnList = "human_id,started_at"),
        // ② 重启后的第一问: "她现在是不是正在做某件事"。
        //    没有它, 恢复流程要扫她全部历史活动才能找出那一行 —— 而那张表只增不减。
        @Index(name = "idx_activity_human_state", columnList = "human_id,state"),
        // ③ "这一项计划实际做了几次、每次多久" —— 计划与实际对比（她估时间准不准）。
        //    plan_item_id 可以为空（她做了一件计划表上没有的事, 那是合法的, 见 Activity 的说明）,
        //    索引对 NULL 不生效, 这没有问题: 我们恰恰只关心非空的那部分。
        @Index(name = "idx_activity_plan_item", columnList = "plan_item_id")
})
@Getter
@Setter
public class ActivityRecord {

    /** 执行 id —— {@code ActivityId.value()}（形如 {@code act-17}）。§7.2 写 UUID, 这里跟领域。 */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 谁的执行 —— agent 的 id。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /** 活动类型三元组 —— 取自实现类上的 {@code @DomainType}。 */
    @Column(name = "activity_type_namespace", nullable = false, length = 96)
    private String activityTypeNamespace;

    @Column(name = "activity_type_name", nullable = false, length = 96)
    private String activityTypeName;

    @Column(name = "activity_type_version", nullable = false)
    private int activityTypeVersion;

    /** 意图类型三元组 —— {@code intent_json} 的读法。 */
    @Column(name = "intent_type_namespace", nullable = false, length = 96)
    private String intentTypeNamespace;

    @Column(name = "intent_type_name", nullable = false, length = 96)
    private String intentTypeName;

    @Column(name = "intent_type_version", nullable = false)
    private int intentTypeVersion;

    /**
     * 意图本身的多态载荷。
     *
     * <p>{@code Activity} 的另一半是"她在做的这件事是什么"。不把它存下来, 恢复出来的
     * 活动就只有一个类型和一段时间 —— 她能回答"我在睡觉", 答不出"我在睡哪一觉、
     * 为什么现在睡"。而后者正是 {@code Mind} 决定要不要叫醒她时的输入。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "intent_json", columnDefinition = "text")
    private Map<String, Object> intentJson;

    /** 它实现的是计划表上的哪一项。可以为空 —— 计划表外的事是真实的。 */
    @Column(name = "plan_item_id", length = 64)
    private String planItemId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * 状态 —— {@code RUNNING} / {@code CONCLUDED} / {@code ABANDONED}。
     *
     * <h2>这里刻意<b>不</b>存 JSON（§7.2 没写这一列, 但它是必须的）</h2>
     * {@code ActivityState} 是一个<b>正确的枚举</b>（见 {@code V22BoundaryArchitectureTest}
     * 的"刻意不禁的东西"清单: 一次活动只有"在做/做完了/放弃了"三种去处, 那是设计决定的,
     * 不是世界决定的）。把它包进 JSON 只会让上面那条索引查不了。
     */
    @Column(name = "state", nullable = false, length = 16)
    private String state;

    /** 结束时刻。进行中为空 —— 空与"刚结束"不是一回事。 */
    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * 收尾时的一句话 —— 进她的 LLM context, 所以是给人和模型读的句子, 不是状态码。
     *
     * <p>长度取 1000 而 §7.2 没给: 它由 LLM 或用户产生, 而"被叫走了, 因为妈妈打电话
     * 来说外婆住院了"这种长度的句子里包含的信息量恰恰是最高的。截断它的代价是
     * 把最有价值的行为数据切掉半句。
     */
    @Column(name = "closing_note", length = 1000)
    private String closingNote;

    /**
     * 收尾时记下的进度（0..1）。未收尾时为空。
     *
     * <p>用 {@code Double} 而不是 {@code double}: {@code null} 与 {@code 0.0} 是两件事 ——
     * 0.0 是"她一点没做就结束了", {@code null} 是"还没结束, 进度无从谈起"
     * （见 {@code AbstractActivity.CommonFields.finalProgress} 的说明）。
     * 用原始类型会让这个区别在落库那一刻被静默抹掉。
     */
    @Column(name = "final_progress")
    private Double finalProgress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 她还在做这件事吗。 */
    public boolean running() {
        return "RUNNING".equals(state);
    }

    public String describe() {
        return activityTypeNamespace + "." + activityTypeName + "[" + id + "] " + state
                + " 起于 " + startedAt + (endedAt == null ? "" : ", 止于 " + endedAt);
    }
}
