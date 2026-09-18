package com.luxera.companion.mind;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V11 §9.2 / §26.1 —— <b>持续存在的那个心智</b>(表 {@code agent_mind_states}, 每个 agent 一行)。
 *
 * <h2>它和 {@code cognitive_sessions} 是什么关系</h2>
 * 不是替代, 是<b>升一级</b>, 而且升级的理由很具体: {@code cognitive_sessions} 每次消息
 * 覆盖一组 {@code current_*} 字段, 它回答的是"最后一句话在说什么"。本表回答的是
 * "她手上挂着几条线、注意力在哪条上、上一次真正想过事是什么时候" —— 这些问题
 * 在多条线并行时才有意义, 而它们是覆盖式的字段<b>结构上</b>答不了的。
 *
 * <p>两个写入者, 两种粒度, 刻意不合并(Phase 3 不动 {@code cognitive_sessions} 的写入方):
 * <pre>
 *   cognitive_sessions.current_focus   消息级: 这一句话在谈什么   (AgentRuntime 原有链路)
 *   agent_mind_states.focus_*          回合级: 这个回合之后她关注什么(回合封口/认知后)
 * </pre>
 * 切流的最后一步才是让后者取代前者 —— 那要等回合真的在驱动认知(Phase 4)。
 *
 * <h2>workingThreads 为什么是 JSON 而不是子表</h2>
 * 因为它是<b>一个人的工作台</b>, 不是一张需要按行查询的表: 永远只按 companion_id
 * 整取整存, 条目数被回合窗口封在个位数, 而且没有人会去 SQL 里筛"pending_count > 2"。
 * 为它建表只会多出一份生命周期(删行/孤儿行)而没有多出任何一种查询。
 * 这与 {@code cognitive_sessions.active_plans} 的处理方式是同一个判断。
 *
 * <h2>计数为什么落库</h2>
 * 内存计数器(见 {@code ConversationTurnAggregator})回答"这个进程观察到了什么",
 * 重启就没了; 而"合并率到底是多少"这个问题要在<b>部署之后</b>还能问 —— 否则切流
 * 判据就成了一次性的、只能靠翻日志回忆的东西。两套数字在同一个诊断端点里并列,
 * 谁都看得出它们不同源。
 *
 * <h2>设计文档 §9.2 里的另外四个字段为什么不在这里</h2>
 * 文档列的 {@code attention} / {@code emotion} / {@code social} / {@code life} 都<b>已经有家</b>:
 * {@code agent_states}(精力/压力)、{@code phone_notifications}、关系表、{@code life} 那套内核。
 * 把它们再抄一份存进本表, 得到的不是"心智更完整", 而是<b>同一件事的两个值</b> ——
 * 一个由本表写入者更新、一个由原子系统更新, 于是"她现在累不累"会有两个答案,
 * 而发现它们不一致需要有人同时读两处。所以本表只留<b>没有别的地方可放</b>的那几样
 * (关注点、工作台、回合账目), 其余由 {@link MindSnapshot} 在读取时向同一个 Service 要
 * —— 与 {@code AgentSnapshotService} 用的是同一对 Service, 两份视图因此不可能分叉。
 *
 * <p>这与"信息越全越好"不矛盾: 要全的那个东西是 {@link MindSnapshot}(一个切面),
 * 不是这条记录(一份状态)。
 */
@Entity
@Table(name = "agent_mind_states")
@Getter
@Setter
public class MindState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    /** 她正关注的事(认知后的结论) */
    @Column(name = "focus_what", length = 200)
    private String focusWhat;

    /** 这件事来自哪次会话 */
    @Column(name = "focus_source", length = 36)
    private String focusSource;

    @Column(name = "focus_intensity", nullable = false)
    private double focusIntensity;

    @Column(name = "focus_since")
    private LocalDateTime focusSince;

    /** 手上的线(JSON 数组, 元素形状见 {@link WorkingThread}) */
    @Column(name = "working_threads", columnDefinition = "text")
    private String workingThreads = "[]";

    /** 最近一次认知落在哪次会话上 —— 回合封口时写 */
    @Column(name = "last_conversation_id", length = 36)
    private String lastConversationId;

    /** 最近一个回合的 id */
    @Column(name = "last_turn_id", length = 160)
    private String lastTurnId;

    /**
     * 她上一次<b>真正把注意力放到某件事上</b>是什么时候。
     *
     * <p>注意它<b>不</b>等于"上次收到消息": 一个被暂停丢掉、或者读不到正文的回合
     * 不该把它往前推。这个字段被读来做"她消停了多久"的判断, 所以它必须是事实而不是动作。
     */
    @Column(name = "last_cognitive_at")
    private LocalDateTime lastCognitiveAt;

    // ─── 累计回合计数(落库, 见类注释) ───

    @Column(name = "turns_opened", nullable = false)
    private long turnsOpened;

    @Column(name = "turns_sealed", nullable = false)
    private long turnsSealed;

    @Column(name = "messages_aggregated", nullable = false)
    private long messagesAggregated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (workingThreads == null) {
            workingThreads = "[]";
        }
    }

    /** 消息合并率。没有回合时返回 0, 而不是 NaN —— NaN 会一路传到 JSON 里变成 null。 */
    public double messagesPerTurn() {
        return turnsSealed == 0 ? 0 : (double) messagesAggregated / turnsSealed;
    }
}
