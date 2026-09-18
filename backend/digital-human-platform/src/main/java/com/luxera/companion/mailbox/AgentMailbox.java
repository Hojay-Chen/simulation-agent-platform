package com.luxera.companion.mailbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventPriority;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V11 §4.1 —— <b>她的信箱</b>。世界把信放进这里, 她按自己的节奏拆。
 *
 * <h2>它替代了什么, 保留了什么</h2>
 * 它<b>不</b>重建串行化。今天每个 agent 已经有一条自己的队列和一条专属消费线程
 * ({@code digitalhuman/actor/PersonActor}, 30 秒空闲自回收)。那是一份写对了的代码,
 * 重写它只会引入新的并发 bug。本类做的是在那条队列<b>前面</b>加一层:
 * 信先落库, 再投递。于是"排队中的信"从易失的内存变成一个可查询、可重放的事实。
 *
 * <h2>三层闸门, 一层都没有绕过</h2>
 * agent 开关(用户明确要求的省钱手段)在 V11 里被<b>加固而不是绕过</b>:
 * <ol>
 *   <li>{@link #accept} 落库之后、投递之前就问一次 {@code isRunnable}; 暂停中的 agent
 *       那些信就<b>留在 PENDING</b>, 一个 attempt 都不消耗。</li>
 *   <li>{@link #deliver} 再问一次 —— 投递是异步的, 第一次问过之后 agent 可能已经被暂停。</li>
 *   <li>真正的认知链入口({@code EventProcessingChain})和 LLM 硬闸({@code LlmRouter})
 *       原样保留。V11 没有新增任何一条绕开它们的路。</li>
 * </ol>
 * 为什么要问两次: 第一次问是"别白干活", 第二次问是"别在暂停之后才开始干"。
 * 两次都不消耗 attempt, 所以一个暂停三天的 agent 恢复后不会发现自己的信全 FAILED 了。
 *
 * <h2>没有消费者时会怎样</h2>
 * 信留在 PENDING, 一条警告, 不消耗 attempt。这是 Phase 1 的正常状态 ——
 * 那时候还没有任何认知链接上信箱。它也是正确的长期语义: "世界说了话,
 * 但这一版还没有能听懂它的人"应当留下痕迹, 而不是假装收下了。
 */
@Service
@Slf4j
public class AgentMailbox {

    /** 租约: 超过这么久还停在 CONSUMING 就认为消费者死了, 把信捞回来。 */
    private static final int LEASE_MINUTES = 5;

    private final AgentInboxRepository inbox;
    private final PersonActorRegistry actors;
    private final AgentSwitchService agentSwitch;
    private final List<AgentInboxConsumer> consumers;
    private final ObjectMapper json;

    /**
     * 重放窗口。只捞这个时间窗内还没拆的信, 见
     * {@link AgentInboxRepository#findTop200ByStatusAndCreatedAtAfterOrderByOccurredAtAsc}。
     */
    @Value("${app.v11.runtime.replay-window-minutes:60}")
    private int replayWindowMinutes = 60;

    public AgentMailbox(AgentInboxRepository inbox,
                        PersonActorRegistry actors,
                        AgentSwitchService agentSwitch,
                        List<AgentInboxConsumer> consumers,
                        ObjectMapper json) {
        this.inbox = inbox;
        this.actors = actors;
        this.agentSwitch = agentSwitch;
        this.consumers = consumers == null ? List.of() : consumers;
        this.json = json;
    }

    // ─────────────────────────── 收信 ───────────────────────────

    /**
     * 世界把一个事件交给她。
     *
     * @return true 表示这封信是<b>新收下</b>的; false 表示要么信封不合法、
     *         要么同 eventId 的信早就在信箱里了(幂等短路)。
     */
    public boolean accept(EventEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        if (inbox.existsByEventId(envelope.eventId())) {
            log.debug("[Mailbox] 事件 {} 已在信箱里, 幂等短路", envelope.eventId());
            return false;
        }

        AgentInboxEntry entry = new AgentInboxEntry();
        entry.setEventId(envelope.eventId());
        entry.setAgentId(envelope.agentId());
        entry.setEventType(envelope.type().wire());
        entry.setSource(envelope.source().wire());
        entry.setPriority(envelope.priority().name());
        entry.setPayload(serialize(envelope));
        entry.setOccurredAt(envelope.occurredAt());
        entry.setStatus(AgentInboxEntry.S_PENDING);

        try {
            // saveAndFlush: 让唯一约束在这里就炸, 而不是等到事务提交时 ——
            // 提交时炸的话异常会飞到一个已经离开本方法的调用栈里。
            inbox.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发下的正常结果: 另一个线程刚刚收下了同一封信。不是错误。
            log.debug("[Mailbox] 事件 {} 被并发收下, 让给对方", envelope.eventId());
            return false;
        }

        deliver(entry.getId());
        return true;
    }

    // ─────────────────────────── 拆信 ───────────────────────────

    /**
     * 把一封信交给消费者。已经在拆的、拆过的、以及收信人已暂停的, 都会原样返回 false。
     *
     * @return true 表示这次调用真的消费成功了。
     */
    public boolean deliver(String entryId) {
        Optional<AgentInboxEntry> found = inbox.findById(entryId);
        if (found.isEmpty()) {
            return false;
        }
        AgentInboxEntry entry = found.get();
        if (!AgentInboxEntry.S_PENDING.equals(entry.getStatus())) {
            return false;
        }

        // 闸门 1: 暂停中的 agent 的信, 一个 attempt 都不消耗, 就地等着。
        if (!agentSwitch.isRunnable(entry.getAgentId())) {
            log.debug("[Mailbox] agent {} 已暂停, 事件 {} 留在信箱", entry.getAgentId(), entry.getEventId());
            return false;
        }

        AgentEventType type = AgentEventType.fromLegacyWire(entry.getEventType()).orElse(null);
        AgentInboxConsumer consumer = consumerFor(type);
        if (consumer == null) {
            // 留在 PENDING 且不认领 —— 没有消费者不是"处理失败", 是"还没有人听得懂"。
            log.debug("[Mailbox] 事件 {} 的类型 {} 暂无消费者, 留在信箱", entry.getEventId(), entry.getEventType());
            return false;
        }

        // 闸门 2: 上面几步之间 agent 可能已被暂停。claim 是条件更新, 所以这里同时
        // 解决了并发重复投递 —— 拿到 0 行的人必须放手。
        int claimed = inbox.claim(entryId, AgentInboxEntry.S_PENDING,
                AgentInboxEntry.S_CONSUMING, LocalDateTime.now());
        if (claimed == 0) {
            log.debug("[Mailbox] 事件 {} 已被别人认领", entry.getEventId());
            return false;
        }

        // claim 是 bulk update(clearAutomatically), 上下文已清, 必须重新读才拿得到 attempts
        AgentInboxEntry claimedEntry = inbox.findById(entryId).orElse(null);
        if (claimedEntry == null) {
            return false;
        }

        // 消费线程就是她自己的那条(每个 agent 一条, 天然串行, 不同 agent 并行)。
        final EventEnvelope envelope = deserialize(claimedEntry);
        actors.tell(claimedEntry.getAgentId(), () -> consume(claimedEntry.getId(), envelope, consumer));
        return true;
    }

    private void consume(String entryId, EventEnvelope envelope, AgentInboxConsumer consumer) {
        try {
            consumer.consume(envelope);
            markConsumed(entryId);
        } catch (Exception e) {
            markFailed(entryId, e);
        }
    }

    // ─────────────────────── 重放与清理 ───────────────────────

    /**
     * 重放近期还没拆的信 —— 进程崩溃/重启后"接着做"的入口。
     *
     * @return 这次真的被消费掉的条数
     */
    public int replayPending() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(Math.max(1, replayWindowMinutes));
        List<AgentInboxEntry> pending =
                inbox.findTop200ByStatusAndCreatedAtAfterOrderByOccurredAtAsc(
                        AgentInboxEntry.S_PENDING, since);
        int delivered = 0;
        for (AgentInboxEntry e : pending) {
            if (deliver(e.getId())) {
                delivered++;
            }
        }
        if (delivered > 0) {
            log.info("[Mailbox] 重放 {} 条未拆事件(窗口 {} 分钟内共 {} 条待处理)",
                    delivered, replayWindowMinutes, pending.size());
        }
        return delivered;
    }

    /** 把租约过期的信捞回 PENDING。返回到期被回收的条数。 */
    public int reclaimStale() {
        int n = inbox.reclaimStale(AgentInboxEntry.S_CONSUMING, AgentInboxEntry.S_PENDING,
                LocalDateTime.now().minusMinutes(LEASE_MINUTES),
                "租约超时, 消费者未在 " + LEASE_MINUTES + " 分钟内完成");
        if (n > 0) {
            log.warn("[Mailbox] 回收 {} 条租约过期的信箱条目", n);
        }
        return n;
    }

    /** 超过保留期仍未拆的信转 DROPPED。返回条数。 */
    public int dropExpired(int retentionHours) {
        int n = inbox.dropExpired(
                List.of(AgentInboxEntry.S_PENDING, AgentInboxEntry.S_CONSUMING, AgentInboxEntry.S_FAILED),
                AgentInboxEntry.S_DROPPED,
                LocalDateTime.now().minusHours(Math.max(1, retentionHours)),
                "超过保留期仍未处理");
        if (n > 0) {
            log.info("[Mailbox] {} 条信箱条目超过 {} 小时保留期, 转 DROPPED", n, retentionHours);
        }
        return n;
    }

    /** 某个 agent 还欠着多少封信 —— 快照与运维看板的输入。 */
    public long depthOf(String agentId) {
        return inbox.countByAgentIdAndStatus(agentId, AgentInboxEntry.S_PENDING);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private void markConsumed(String entryId) {
        inbox.findById(entryId).ifPresent(e -> {
            e.setStatus(AgentInboxEntry.S_CONSUMED);
            e.setConsumedAt(LocalDateTime.now());
            e.setLastError(null);
            inbox.save(e);
        });
    }

    private void markFailed(String entryId, Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        inbox.findById(entryId).ifPresent(e -> {
            e.setLastError(truncate(message, 500));
            if (e.retryable()) {
                e.setStatus(AgentInboxEntry.S_PENDING);
                e.setClaimedAt(null);
                log.warn("[Mailbox] 事件 {} 处理失败(第 {}/{} 次), 退回信箱: {}",
                        e.getEventId(), e.getAttempts(), AgentInboxEntry.MAX_ATTEMPTS, message);
            } else {
                e.setStatus(AgentInboxEntry.S_FAILED);
                log.error("[Mailbox] 事件 {} 已重试 {} 次仍失败, 转 FAILED: {}",
                        e.getEventId(), e.getAttempts(), message);
            }
            inbox.save(e);
        });
    }

    private AgentInboxConsumer consumerFor(AgentEventType type) {
        if (type == null) {
            return null;
        }
        for (AgentInboxConsumer c : consumers) {
            try {
                if (c.supports(type)) {
                    return c;
                }
            } catch (Exception e) {
                log.warn("[Mailbox] 消费者 {} 的 supports 抛异常, 跳过", c.getClass().getSimpleName(), e);
            }
        }
        return null;
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return json.writeValueAsString(envelope.references());
        } catch (Exception e) {
            log.warn("[Mailbox] 信封 {} 的 references 序列化失败, 落库为空", envelope.eventId(), e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private EventEnvelope deserialize(AgentInboxEntry entry) {
        Map<String, Object> refs = Map.of();
        if (entry.getPayload() != null && !entry.getPayload().isBlank()) {
            try {
                refs = json.readValue(entry.getPayload(), Map.class);
            } catch (Exception e) {
                log.warn("[Mailbox] 信箱条目 {} 的 payload 解析失败, 当作空坐标", entry.getId(), e);
            }
        }
        return new EventEnvelope(
                entry.getEventId(),
                entry.getAgentId(),
                AgentEventType.requireWire(entry.getEventType()),
                EventSource.fromWire(entry.getSource()),
                EventPriority.fromWire(entry.getPriority()),
                entry.getOccurredAt(),
                refs);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
