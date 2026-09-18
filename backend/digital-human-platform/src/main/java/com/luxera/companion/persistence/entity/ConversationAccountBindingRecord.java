package com.luxera.companion.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V2.2 §7.2 / §3.4.6 —— {@code conversation_account_binding}:
 * <b>聊天账号 → 她心里的那个人</b>。她的通讯录。
 *
 * <h2>这条绑定为什么是"她的", 而不是聊天平台的</h2>
 * §3.4.6 末尾把这个设计的产品意义写得很直白:
 * <blockquote>
 * 她的"通讯录"是<b>她自己长出来的</b>, 不是从聊天平台同步下来的。
 * 这与真人一致 —— 你手机里的备注名是你自己起的, 微信不告诉你这个人是谁。
 * </blockquote>
 * 那句"她自己长出来的"在这张表上落成三件事:
 * <ol>
 *   <li><b>绑定的主语是 {@code human_id}。</b> 同一个聊天账号在 A 的通讯录里是
 *       "妈妈", 在 B 的通讯录里可能是"不认识的人"—— 所以主键不能是账号,
 *       绑定也不是全局的;</li>
 *   <li><b>绑定会被<b>逐步</b>建立。</b> {@code bind_reason = LEARNED} 那一档
 *       就是"聊过几次之后形成的认识"。它意味着绑定有<b>时间</b>, 所以
 *       {@code bound_at} 是一列真数据, 不是审计列 ——
 *       "她是哪一天开始认识这个人的"是关系分析里的一个真实问题;</li>
 *   <li><b>未绑定的账号是正常状态。</b> 见 {@code RelationshipGraph.resolve} 返回
 *       {@code Optional}: 一个陌生账号意味着"有个不认识的人找我",
 *       而那是一个需要被表达的事实, 不是一次失败。这张表因此<b>允许缺行</b>,
 *       {@code bind_reason} 里也不需要"UNKNOWN"这种取值去填这个空缺。</li>
 * </ol>
 *
 * <h2>{@code reason} 那一类的列为什么是 VARCHAR + 一组常量, 而不是枚举</h2>
 * §7.2 给了三个取值 {@code BOOTSTRAP / LEARNED / MANUAL} —— 一个封闭集合,
 * 按 P4 的判断标准它<b>可以</b>是枚举（与 {@code action_command.status} 同理）。
 * 这里仍然存字符串, 沿用本仓约定（{@code person/Person.personType} 用的是
 * {@code public static final String} 常量）。理由是具体的: 这一列会被
 * {@code grep} 与 {@code psql} 直接读, 而 {@code @Enumerated(STRING)} 的价值
 * 只在 Java 侧 —— 而 Java 侧本来就有 {@code BindReason} 这个类型做校验。
 * 数据库侧多一层枚举校验换不来任何东西。
 *
 * <h2>{@code person_id} 没有外键约束 —— 这是刻意的</h2>
 * 指向 {@link PersonObjectRecord}。本仓全部实体都没有外键（零
 * {@code @ManyToOne} / {@code @JoinColumn}）, 而这在<b>这一张</b>表上恰好是必须的:
 * <pre>
 *   她删掉了对一个人的记忆（或那条记忆过期清理）  →  person_object 少了那一行
 *   而绑定还在                                    →  悬空引用
 * </pre>
 * 加外键的效果是<b>删记忆这个动作失败</b>, 于是在"她忘了一个人"和"数据库保持
 * 引用完整性"之间, 系统的选择变成了后者 —— 而那与 §3.4.6 的整个设计意图相反。
 * 悬空引用在这里的正确处理是读的时候返回 {@code Optional.empty()}（"我不认识这个账号"）,
 * 而不是阻止删除。
 */
@Entity
@Table(name = "conversation_account_binding", indexes = {
        // ① 热路径: "这个账号是谁" —— 每收到一条消息都要问一次。
        //    (human_id, chat_account_id) 的顺序由查询形状决定: 先确定是谁的通讯录,
        //    再查里面有没有这个账号。
        @Index(name = "idx_binding_human_account", columnList = "human_id,chat_account_id"),
        // ② 反向: "我认识的人里谁是这个账号" / "这条绑定指向的人还认不认得"。
        //    指向 person_id 的查询不会走 ①, 因为它的第一列是 human_id。
        @Index(name = "idx_binding_person", columnList = "person_id")
})
@Getter
@Setter
public class ConversationAccountBindingRecord {

    public static final String REASON_BOOTSTRAP = "BOOTSTRAP";
    public static final String REASON_LEARNED = "LEARNED";
    public static final String REASON_MANUAL = "MANUAL";

    /**
     * 行的 id。
     *
     * <p>为什么不用 {@code (human_id, chat_account_id)} 做复合主键: 见
     * {@link DeviceApplicationRecord} 的说明（本仓约定单列 String 主键,
     * 破例会让通用代码为它分支）。
     *
     * <p>而"同一个人的通讯录里一个账号只出现一次"这条约束, §7.2 没有要求,
     * 这里也<b>没有</b>加唯一约束 —— 因为 {@code bind} 的语义是
     * "她改主意了, 把这个人重新归到另一个人物对象上"（§3.4.6:
     * "一个账号也只对应一个人"是<b>值</b>的约束, 不是<b>行数</b>的约束）。
     * 读的时候取最新的一行（{@code bound_at} 最大）。这个选择让"她改过主意"
 *   留下了历史, 而 {@code bound_at} 正是那个排序键。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** 谁的通讯录 —— agent 的 id。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /**
     * 聊天平台的账号 ID —— §7.2 给的是 {@code VARCHAR(64)}。
     *
     * <p>取值形如 {@code "user_8f3a"} / {@code "agent_m3k9"}（§6.5 的初始化流程里
     * 出现的就是这两个）。64 是这个平台账号 id 的统一宽度
     * （见 {@code persona/Companion.chatAccountId}）。
     *
     * <p>刻意<b>不是</b>指向仓 1 的外键: §6.5 明确了 {@code ChatApplication} 对聊天平台
     * 的唯一认知是 {@code ChatPlatformGateway}（"它不允许用'我是内部的'这类后门直读数据库"）。
     * 在这一列上建外键, 就等于从仿真平台的数据库里伸手进聊天平台的库 ——
     * 那正是那条规则要防的事。
     */
    @Column(name = "chat_account_id", nullable = false, length = 64)
    private String chatAccountId;

    /** 她心里那个人物对象 —— 指向 {@link PersonObjectRecord#getPersonId()}。见类注释"没有外键"。 */
    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    /**
     * 这条绑定是怎么来的 —— {@code BOOTSTRAP} / {@code LEARNED} / {@code MANUAL}。
     *
     * <p>三个取值的差异是行为分析里的一等素材:
     * {@code BOOTSTRAP} 是人给她预置的（用户自己）, {@code LEARNED} 是她自己聊出来的,
     * {@code MANUAL} 是外部显式指定的。少了这一列, "她的通讯录里有多少人是她自己认出来的"
     * 这个问题就答不出来 —— 而那是她社交能力最直接的一个指标。
     */
    @Column(name = "bind_reason", nullable = false, length = 32)
    private String bindReason;

    /** 绑定时刻（仿真时刻）—— 不是审计列, 见类注释。 */
    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    /** 这一行什么时候被写进来的（墙上时钟）。与 {@code bound_at} 是两种时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public boolean bootstrap() {
        return REASON_BOOTSTRAP.equals(bindReason);
    }

    public String describe() {
        return chatAccountId + " → " + personId + " (" + bindReason + " @ " + boundAt + ")";
    }
}
