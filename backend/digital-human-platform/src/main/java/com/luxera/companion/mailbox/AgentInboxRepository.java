package com.luxera.companion.mailbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * V11 §4.1 信箱仓储。
 *
 * <p>注意 {@link #claim} / {@link #reclaimStale} 这两条条件 UPDATE —— 它们是并发正确性的所在,
 * 不是优化。两个线程同时想投递同一条目时, 只有 UPDATE 影响行数为 1 的那个能继续,
 * 另一个拿到 0 必须放手。用"先查状态再改"的写法在并发下一定会漏。
 */
public interface AgentInboxRepository extends JpaRepository<AgentInboxEntry, String> {

    boolean existsByEventId(String eventId);

    Optional<AgentInboxEntry> findByEventId(String eventId);

    /**
     * 重放用: <b>近期</b>还没拆的信, 老的先来(FIFO —— 她该先看到先说出口的那句)。
     *
     * <p>为什么限定"近期"而不是把全部 PENDING 捞出来: 一个被暂停了一周的 agent
     * 的信箱里会有成百条历史条目, 按时间升序它们永远排在最前面, 于是重放任务每次都
     * 只看得到这些<b>注定不会被执行</b>的条目, 而另一个 agent 十分钟前因进程崩溃
     * 遗留的那一条永远轮不到。窗口把"崩溃遗留"和"长期暂停的积压"分开:
     * 前者是重放要解决的, 后者是保留期({@code dropExpired})要清理的。
     *
     * <p>暂停期间真的积压了一堆消息、恢复后该不该补, 由唤醒补课
     * ({@code WakeupCatchUpService})决定 —— 那是"她醒来发现错过了什么"的语义,
     * 和"进程崩了要接着做"不是同一件事, 不该由同一个机制顺手做掉。
     */
    List<AgentInboxEntry> findTop200ByStatusAndCreatedAtAfterOrderByOccurredAtAsc(
            String status, LocalDateTime after);

    List<AgentInboxEntry> findTop200ByAgentIdAndStatusOrderByOccurredAtAsc(String agentId, String status);

    /** 信箱深度 —— 快照与运维看板要看的那个数字。 */
    long countByAgentIdAndStatus(String agentId, String status);

    /** 全平台还没拆的信有多少 —— 恢复报告用。 */
    long countByStatus(String status);

    /**
     * 认领一条待处理条目。
     *
     * <p>条件 {@code status = PENDING} 是全部要点: 只有一个调用者能把行数从 1 改成 1,
     * 其余拿到 0。返回 0 就是"别人先拿到了", 不是错误。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentInboxEntry e set e.status = :consuming, e.claimedAt = :now, e.attempts = e.attempts + 1 "
            + "where e.id = :id and e.status = :pending")
    int claim(@Param("id") String id,
              @Param("pending") String pending,
              @Param("consuming") String consuming,
              @Param("now") LocalDateTime now);

    /**
     * 回收租约过期的条目 —— 消费者(或整个 JVM)中途死了, 条目不能永远卡在 CONSUMING。
     *
     * <p>超时窗口必须显著大于一次正常消费的耗时, 否则会把<b>正在处理</b>的条目抢回来,
     * 表现为她隔几分钟重复回复同一条消息。这个数字是权衡: 太长则崩溃后要等很久才恢复,
     * 太短则重复回复。取分钟级, 因为一次认知链正常在秒级。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentInboxEntry e set e.status = :pending, e.claimedAt = null, e.lastError = :reason "
            + "where e.status = :consuming and e.claimedAt < :deadline")
    int reclaimStale(@Param("consuming") String consuming,
                     @Param("pending") String pending,
                     @Param("deadline") LocalDateTime deadline,
                     @Param("reason") String reason);

    /**
     * 超过保留期仍未拆的信 → DROPPED。
     *
     * <p>必须存在, 否则一个被永久暂停的 agent 会无限堆积事件, 而"暂停"恰恰是
     * 用户明确要求的省钱手段 —— 一个会因为省钱而撑爆数据库的功能是不可接受的。
     * 标 DROPPED 而不是 DELETE: "她漏掉了什么"应当留得下来。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentInboxEntry e set e.status = :dropped, e.lastError = :reason "
            + "where e.status in :openStatuses and e.createdAt < :cutoff")
    int dropExpired(@Param("openStatuses") List<String> openStatuses,
                    @Param("dropped") String dropped,
                    @Param("cutoff") LocalDateTime cutoff,
                    @Param("reason") String reason);

    /** agent 被删时的连带清理。派生删除查询同样需要事务边界, 否则调用方一忘就炸。 */
    @Transactional
    long deleteByAgentId(String agentId);
}
