package com.luxera.companion.wakeup;

import com.luxera.companion.world.AgentEventType;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 设计文档 §18.1 <b>唤醒来源</b>里的 Timer 那一条 —— <b>她自己设的闹钟</b>。
 *
 * <h2>这不是"定时任务"</h2>
 * 一个容易被读错的地方: 这张表里<b>没有运维配的东西</b>。谁来写它?
 * <pre>
 *   她说"忙完再说"      → {@code CognitiveDecision.nextWakeupAt} → {@link AgentWakeupService#schedule}
 *   她在等一个结果      → open loop 的 expectedResolutionAt
 *   她该睡了/该醒了      → 作息
 * </pre>
 * 也就是说: <b>闹钟是她的一个决定, 而不是一个外部指令</b>。区别不是措辞 ——
 * 外部指令的语义是"到点了, 你去干活", 而她自己的闹钟的语义是"到点了, 你醒一下,
 * 再看看要不要做什么"。后者才是 §18.2 描述的唤醒: 醒来之后并不必然产生任何行为。
 *
 * <h2>为什么不复用 {@code intentions}</h2>
 * 因为那是两件事。`intentions` 存的是"她想做什么"(一件待办, 有内容、有情绪、会遗忘);
 * 本表存的是"她下一次什么时候睁眼" —— 一个时刻加一个理由, 事件发出去就作废。
 * 把闹钟塞进待办表会得到一个必然的副作用: 每次 DEFER 都往她的记忆里写一条"我打算做某事",
 * 而她当时可能只是"等他别再刷屏了"。
 *
 * <h2>一行一个闹钟, 不是一个 agent 一行</h2>
 * 她可以同时等两件事(等面试结果 + 记着晚上问他吃药没), 而它们的时刻不同。
 * 一行一个的做法下一个闹钟响了不会把另一个顶掉。代价是"下次什么时候醒"要取<b>最早</b>的那个,
 * 这件事由 {@link AgentWakeupService#nextWakeupOf} 负责, 调用方不要自己去比。
 *
 * <h2>幂等键是 (agentId, eventType, sourceKey) 三元组</h2>
 * 同一个来源重复排期是<b>改主意</b>, 不是新增: 她本来说"一小时后", 又说"算了, 三小时后" ——
 * 那是同一个闹钟被推后了。没有这个三元组的话, 每一次 DEFER 都会多出一行,
 * 而它们到点时会一起响, 于是"她醒来"这件事在日志里会变成一串同时到达的事件。
 */
@Entity
@Table(name = "agent_schedule",
        indexes = {
                @Index(name = "idx_wakeup_agent", columnList = "agent_id"),
                @Index(name = "idx_wakeup_due", columnList = "status,wake_at")
        })
@Getter
@Setter
public class AgentWakeup {

    // ─────────────── 状态 ───────────────
    /** 等着响。 */
    public static final String S_PENDING = "PENDING";
    /** 事件已经进了信箱 —— 从这一刻起本行只是历史, 不再是"她还会醒"。 */
    public static final String S_FIRED = "FIRED";
    /** 她等的那件事已经有了结果(对方回了/意图作废), 这个闹钟没有意义了。 */
    public static final String S_CANCELLED = "CANCELLED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    /**
     * 醒来时该给她发哪种事件。
     *
     * <p>存 {@link AgentEventType} 而不是自造一个枚举: 闹钟响的时候要往信箱里投一封信,
     * 而信的类型只能是信箱认识的那几种。中间再夹一层自己的枚举, 就得写一张两边的映射表,
     * 而那张表唯一的作用是让两处可以不一致。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private AgentEventType eventType;

    /**
     * 来源键 —— 幂等的那一半。同一个三元组只会有一行。
     *
     * <p>取值要能<b>从来源算出来</b>(如 {@code intention:<id>} / {@code openloop:<id>}),
     * 不要用随机数: 一个随机键在"同一次 DEFER 被处理两遍"时会排两个闹钟,
     * 而它们响的是同一件事。
     */
    @Column(name = "source_key", nullable = false, length = 128)
    private String sourceKey;

    @Column(name = "wake_at", nullable = false)
    private LocalDateTime wakeAt;

    /** 为什么这会儿醒 —— 只进日志与诊断, 不参与判断。 */
    @Column(length = 160)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status = S_PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fired_at")
    private LocalDateTime firedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = S_PENDING;
        }
    }

    /** 还在等 —— 只有这个状态的行会被 {@link AgentWakeupJob} 看到。 */
    public boolean isPending() {
        return S_PENDING.equals(status);
    }
}
