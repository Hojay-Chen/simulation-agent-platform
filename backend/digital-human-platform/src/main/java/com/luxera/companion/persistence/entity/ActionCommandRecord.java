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
import javax.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §7.2 —— {@code action_command}: <b>她做过的事, 以及那些没做成的事</b>。
 *
 * <h2>为什么"没做成"也要落库</h2>
 * 这是这张表与普通审计表最重要的差别。{@code ActionResult} 有五种结局
 * （{@code SUCCEEDED} / {@code REJECTED} / {@code UNAVAILABLE} / {@code FAILED} /
 * {@code RETRYABLE}）, 而其中只有一种是成功。若只记成功的命令:
 * <ul>
 *   <li>"她今天想给妈妈发消息, 试了三次都没发出去" —— 这个故事无法回答。
 *       而这是行为分析里最有价值的一类素材（她的意图与她的能力之间的差距）;</li>
 *   <li>失败的原因无法区分。{@code rejected}（她做不到）与 {@code unavailable}
 *       （能力暂时不在）是两件事, 前者会让她改变计划, 后者只会让她稍后再试。
 *       只看到"没成功"这两个字, 恢复出来的 agent 会选错。</li>
 * </ul>
 *
 * <h2>{@code status} 为什么是一个"允许的字符串", 而不是 §7.2 暗示的枚举</h2>
 * §7.2 在这一列下面特意写了一句: <b>"status 是允许使用枚举的（P4）：它是有限的基础设施状态机, 不是开放领域类型"</b>。
 * 这句话是对的, 本实现也同意 —— 所以这里存的是一个取值有限的字符串
 * （{@code PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED}, 外加 {@code ActionResult}
 * 里多出来的 {@code REJECTED} / {@code UNAVAILABLE}）。
 *
 * <p>但本仓的既有约定是<b>枚举在实体里一律存字符串</b>而不是 {@code @Enumerated}
 * （见 {@code runtime/WorldEventLog}、{@code person/Person.personType} ——
 * 后者甚至把三个取值写成 {@code public static final String} 常量）。
 * 这条约定在这里恰好也不吃亏: 它让 {@code status} 与
 * {@code ActionResult.Status} 之间多一层显式映射, 而那层映射正是"领域枚举改了、
 * 数据库里还有旧值"这件事的唯一处理点 —— 用 {@code @Enumerated(STRING)} 的话,
 * 删掉一个枚举常量会让历史上所有该状态的行在加载时抛
 * {@code IllegalArgumentException}, <b>整个查询失败</b>, 而不是返回一行状态是
 * 陌生字符串的记录。
 *
 * <h2>{@code idempotency_key} 上的唯一约束为什么必须在数据库里</h2>
 * {@code ActionCommand} 的类注释把幂等键的归属论证得很清楚（"重试发生在命令的
 * <b>发送方</b>"）。那条推理的落点是: 两次重试会带着<b>同一个键</b>到
 * <b>不同的线程/进程</b>上。
 * <pre>
 *   线程 A: SELECT ... WHERE idempotency_key = 'k'  → 没有
 *   线程 B: SELECT ... WHERE idempotency_key = 'k'  → 没有      ← 检查与写入之间有窗口
 *   线程 A: INSERT k
 *   线程 B: INSERT k                                             ← 两条同键命令都执行了
 * </pre>
 * 代码里的"先查再写"永远关不上那个窗口。唯一约束把裁决权交给数据库:
 * 第二条 INSERT 拿到约束冲突, 于是"重复副作用"变成一次<b>可捕获的异常</b>,
 * 而不是一条静默产生的第二条消息。这与 {@code Person.handle} 上那条唯一约束
 * 是同一条推理（见 {@code Person} 的 javadoc: "代码里那次查重仍然保留,
 * 但它的职责只是给出友好的错误信息, 不是保证正确性"）。
 *
 * <p><b>为什么可空</b>: {@code ActionCommand} 明写"幂等键可以为空 —— 表示这条命令
 * 重复执行也无害"（"看一眼手机"）。而 SQL 的唯一约束<b>不约束 NULL</b>
 * （多行 NULL 互不冲突）, 于是"可空 + 唯一"这两个要求恰好同时满足、
 * 不需要任何部分索引技巧。这是本表里最省事的一处 —— 但它依赖于
 * "空"用真正的 {@code NULL} 表示, 而不是空字符串。若写入方把空键写成 {@code ""},
 * 那么第二条无键命令会撞上唯一约束而失败。写入方（{@code ActionCommandStore}）
 * 因此必须做 {@code null} 与 {@code ""} 的归一。
 */
@Entity
@Table(name = "action_command",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_action_command_idempotency",
                        columnNames = "idempotency_key")
        },
        indexes = {
                // ① "她今天做过什么" —— 行为分析与控制台的主查询。等值列在前、范围列在后。
                @Index(name = "idx_action_command_human_time", columnList = "human_id,issued_at"),
                // ② "还有哪些命令没跑完" —— 崩溃恢复: 重启后 PENDING/RUNNING 的命令
                //    是"不知道自己有没有生效"的那批, 它们需要被重新审视（不是被重发 ——
                //    见上面幂等键那一节: 重发是安全的, 但必须带着同一个键）。
                //    没有它, 恢复要扫她全部历史命令, 而那是一次随时间线性增长的开销。
                @Index(name = "idx_action_command_human_status", columnList = "human_id,status"),
                // ③ "这个能力被调用过多少次、成功率如何" —— 能力健康度与诊断面板。
                @Index(name = "idx_action_command_capability", columnList = "capability_key")
        })
@Getter
@Setter
public class ActionCommandRecord {

    /** 命令 id —— {@code ActionCommand.commandId()}（形如 {@code cmd-<uuid>}）。 */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 谁发起的 —— {@code ActionCommand.actorId()}。§7.2 的列名就是 {@code human_id}。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /**
     * 要调用哪个能力 —— {@code CapabilityDescriptor} 的 key（{@code namespace.name}）。
     *
     * <p>存 key 而不是类名: 见 {@code ActionCommand} 的说明 ——
     * 能力可以由第三方注册, 宿主不该认识它的类。存类名的直接后果是
     * <b>三方重命名一个实现类, 全部历史命令变得不可解释</b>。
     *
     * <p>本列刻意<b>不拆成 {@code capability_namespace} / {@code capability_name} 两列</b>
     * （§7.2 的 {@code action_command} 也是这么写的）—— 与
     * {@code world_event} 的 {@code type_namespace} + {@code type_name} 不同。
     * 理由: 事件类型需要按命名空间整体筛选（"这个插件的全部事件"）,
     * 而能力本来就是一个整体标识, 全部查询都按完整 key 做。拆成两列只会让每一次查询
     * 都要写一次拼接, 而拼错（漏了那个点）的表现是"查不到"。
     */
    @Column(name = "capability_key", nullable = false, length = 192)
    private String capabilityKey;

    /**
     * 调用参数 —— JSON, <b>不是强类型的</b>。
     *
     * <p>见 {@code ActionCommand} 的"参数为什么是 JSON 形状的 Map"那一节:
     * 参数的类型由能力自己定义, 而能力可以是第三方实现的。宿主无法为一个它不认识的
     * {@code laboratory.centrifuge} 定义一个 Java 参数类 —— 那正是扩展性的技术含义。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "arguments_json", columnDefinition = "text")
    private Map<String, Object> argumentsJson;

    /**
     * 状态 —— 见类注释关于"允许的字符串"那一段。
     *
     * <p>长度 16: 最长取值是 {@code UNAVAILABLE}(11) 与 {@code SUCCEEDED}(9),
     * 而 {@code CANCELLED}(9) / {@code REJECTED}(8) / {@code RUNNING}(7) /
     * {@code PENDING}(7) / {@code FAILED}(6) 都在其下。留到 16 是给
     * "超时"(TIMEOUT) 之类的新结局留的余量 —— 而这一列的取值集合确实会生长,
     * 它只是长得慢（这是"有限状态机"与"开放域类型"的差别: 前者由设计决定, 后者由世界决定）。
     */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * 幂等键 —— 见类注释。{@code NULL} = 这条命令重复执行无害。
     *
     * <p>长度 128 与 §7.2 一致。它不是随便定的: 幂等键会由调用方拼出来
     * （{@code "chat.send-message:" + conversationId + ":" + messageId}）,
     * 而两个 36 位的 id 加上能力名就已经接近 100。
     */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /**
     * 命令发起时刻（仿真时刻）—— {@code ActionCommand.issuedAt()}。
     *
     * <p>它必须来自领域而不是本层读时钟: {@code ActionCommand} 的规范构造器明写
     * "命令必须带发起时刻 —— 仿真时钟下不许读墙上时钟"。本层若在这里填一个
     * {@code Instant.now()}, 就等于把仿真时间轴换成了物理时间轴。
     */
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** 结局 —— 只在这个时刻被填一次。{@code null} = 还没跑完。 */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * 结局的一句话说明 —— {@code ActionResult.reason()}（"没有信号" / "她不认识这个能力"）。
     *
     * <p>存下来而不是每次从 status 重新推: 失败的理由是给人和 LLM 读的句子,
     * 而它<b>无法从状态码还原</b>。丢了它, 恢复出来的 agent 只知道"那次失败了"。
     */
    @Column(name = "outcome_note", length = 500)
    private String outcomeNote;

    /**
     * 能力返回的数据 —— {@code ActionResult.data()}。{@code null} 表示这条命令
     * 没有结局, 或者结局不带数据。
     *
     * <p>刻意与 {@code arguments_json} 分成两列而不是共用一列"某一边的 JSON":
     * 它们同时存在（一条成功的命令既有入参也有出参）, 合并会让其中一边被覆盖掉 ——
     * 而"她发了什么"与"她收到了什么"在排查问题时必须都能看到。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "result_json", columnDefinition = "text")
    private Map<String, Object> resultJson;

    /** 这一行什么时候被写进来的（墙上时钟）。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = "cmd-" + UUID.randomUUID();
        }
    }

    /** 这条命令有结局了吗。 */
    public boolean completed() {
        return completedAt != null;
    }

    public String describe() {
        return capabilityKey + "[" + id + "] " + status + " @ " + issuedAt
                + (idempotencyKey == null ? "" : " key=" + idempotencyKey);
    }
}
