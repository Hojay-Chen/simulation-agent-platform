package com.luxera.companion.mailbox;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V11 §4.1 —— <b>落库的信箱条目</b>。世界给她的一封信, 在她拆开之前一直躺在那里。
 *
 * <h2>为什么必须是持久的</h2>
 * 今天事件在内存里排队({@code PersonActor.mailbox} 是一个 {@code LinkedBlockingQueue})。
 * JVM 一重启, 队列里的东西全部蒸发, 而<b>没有任何人知道丢了什么</b>。
 * 现实后果是很具体的: 用户 23:50 发了一条消息, 进程在 23:51 因为部署重启,
 * 那条消息就永远不会有下文 —— 不是"她决定不回", 是"她根本不知道有这回事"。
 * 这两者在数据库里长得一模一样, 这正是本次要修的。
 *
 * <p>落地之后, "她还欠着一件事"变成一个<b>可查询的事实</b>({@link #S_PENDING} 的行数),
 * 而不是一句推测。
 *
 * <h2>与 {@code processed_event} 的分工</h2>
 * 那张表回答"这件事<b>处理过了</b>吗"(去重); 本表回答"这件事<b>拆开了</b>吗"(待办)。
 * 两者不能合并: 一件处理完的事不该再被投递(去重表管), 一件还没拆的事必须能重放(本表管)。
 * 今天的 {@code DeduplicationHandler} 只保证前者, 于是"漏处理"和"处理过"无法区分 ——
 * 它<b>fail-open</b>(查库失败就当作没处理过), 这个选择在当时是对的(宁可多处理一次),
 * 但它也意味着去重表不能当作"处理过"的证据来用。
 *
 * <h2>状态机</h2>
 * <pre>
 *   PENDING ──claim──> CONSUMING ──成功──> CONSUMED
 *      ↑                   │
 *      └──租约超时回收──────┘
 *      └──────────失败──────────────> FAILED(重试到上限) / DROP 掉
 * </pre>
 * {@code CONSUMING} 这一级不能省: 少了它, 重放任务会把"正在被处理"的条目再投一遍,
 * 于是她会对同一条消息回两次。租约({@link #claimedAt})则是为了在消费者中途崩溃时
 * 能把条目捞回来 —— 只有超时才能回收, 否则就是上面那个重复回复。
 */
@Entity
@Table(name = "agent_inbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_inbox_event", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_agent_inbox_agent_status", columnList = "agent_id, status"),
                @Index(name = "idx_agent_inbox_status_created", columnList = "status, created_at")
        })
@Getter
@Setter
public class AgentInboxEntry {

    /** 还没拆。**这是唯一会被投递的状态**。 */
    public static final String S_PENDING = "PENDING";
    /** 已经交给某个消费者正在处理。有租约, 超时可回收。 */
    public static final String S_CONSUMING = "CONSUMING";
    /** 处理完了。 */
    public static final String S_CONSUMED = "CONSUMED";
    /** 重试到上限仍然失败。留着让人能查为什么 —— 直接删掉等于把证据也删了。 */
    public static final String S_FAILED = "FAILED";
    /** 过期被丢弃(超过保留期, 或 agent 已删除)。 */
    public static final String S_DROPPED = "DROPPED";

    /** 重试上限。到顶就 FAILED —— 永不停歇的重试会把一条坏数据变成一场事故。 */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** 幂等键。同一个世界事件重复投递, 数据库层面只可能有一行。 */
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    /** 收信人 = agent 平台标识 agent 个体的那个 id({@code companions.id})。 */
    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "priority", nullable = false, length = 16)
    private String priority;

    /** {@code references} 的 JSON。**只有坐标, 没有正文** —— 见 {@code EventEnvelope}。 */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = S_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 租约起点。回收任务靠它区分"正在处理"与"处理到一半崩了"。 */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

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
    }

    /** 还能再试吗。 */
    public boolean retryable() {
        return attempts < MAX_ATTEMPTS;
    }
}
