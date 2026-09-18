package com.luxera.companion.phone;

import com.luxera.companion.runtime.PersistentAgentRuntime;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventPriority;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V11 §2.2.2 / §7.2 —— <b>意识阶梯</b>: 同一条消息在她身上走过的五级台阶。
 *
 * <pre>
 *   RECEIVED  消息存在于世界里(对方的手机上显示"已送达")。她还什么都不知道。
 *   NOTIFIED  手机响了/震了。通知已经产生, 但她可能戴着耳机在开会。
 *   NOTICED   她意识到有这么个东西。还不构成"要去看"。
 *   READ      她真的看了。  ← 正文到这一刻才允许进入认知
 *   DEFERRED  看了, 但现在不回(忙/累/不知道怎么回)。已读不回是一个决定, 不是一个 bug。
 * </pre>
 *
 * <h2>为什么每一级都要单独记一笔</h2>
 * 因为今天它们被压成了一个事件名。{@code CHAT_MESSAGE_DELIVERED} 一个名字同时表示
 * "送到了"和"她知道了", 于是"她还没被通知"这个状态<b>根本无法表达</b> —— 一个状态
 * 无法表达, 就只能靠猜, 而今天代码里猜的方式是"送达即处理"。
 *
 * <p>分成五级之后, 每一级都变成一个可以查、可以重放、可以被测试断言的事实。于是
 * "她为什么没回"有了确定的答案: 是没送到, 是没听见, 是看见了没在意, 是看了不想回,
 * 还是回了但你没收到。今天这五种情况在数据库里长得一模一样。
 *
 * <h2>它是唯一的写入口</h2>
 * 任何"她意识到了什么"都必须经过这里。散在各处 emit 阶梯事件的话, "她到底在哪一级"
 * 就会变成一个见仁见智的问题, 而它是本轮最核心的那个事实。
 *
 * <h2>幂等</h2>
 * 每一级的 eventId 由 (agentId, 台阶, messageId) 决定, 所以同一级重复上报只会进信箱
 * 一次。这不是防御性代码: 消息重投、重放、恢复流程都会让同一级被上报两次,
 * 而"她读了同一条消息两遍"在认知上不存在。
 */
@Service
@Slf4j
public class AwarenessLadder {

    private final PersistentAgentRuntime runtime;

    public AwarenessLadder(PersistentAgentRuntime runtime) {
        this.runtime = runtime;
    }

    /** 消息送达了(世界的事实)。 */
    public int received(String agentId, String conversationId, Collection<String> messageIds) {
        return emit(agentId, AgentEventType.USER_MESSAGE_RECEIVED, conversationId, messageIds,
                Map.of(), EventPriority.NORMAL);
    }

    /** 手机通知已产生。 */
    public int notified(String agentId, String conversationId, Collection<String> messageIds) {
        return emit(agentId, AgentEventType.USER_MESSAGE_NOTIFIED, conversationId, messageIds,
                Map.of(), EventPriority.NORMAL);
    }

    /** 她意识到有这么个东西。 */
    public int noticed(String agentId, String conversationId, Collection<String> messageIds) {
        return emit(agentId, AgentEventType.USER_MESSAGE_NOTICED, conversationId, messageIds,
                Map.of(), EventPriority.NORMAL);
    }

    /**
     * 她真的看了 —— <b>整个 V11 里最重要的那一行日志</b>。
     *
     * @param transport 正文是从哪条路读到设备上的。记进事件是为了让"设备通道到底有没有
     *                  在工作"变成一个可查询的事实, 而不是只能翻日志
     */
    public int read(String agentId, String conversationId, Collection<String> messageIds,
                    MessageBatch.Transport transport) {
        return emit(agentId, AgentEventType.USER_MESSAGE_READ, conversationId, messageIds,
                Map.of("transport", transport == null ? MessageBatch.Transport.NONE.wire() : transport.wire()),
                EventPriority.NORMAL);
    }

    /** 看了, 但决定现在不回。{@code until} 为空表示"没说什么时候"。 */
    public int deferred(String agentId, String conversationId, Collection<String> messageIds,
                        LocalDateTime until, String reason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (until != null) {
            extra.put("until", until.toString());
        }
        if (reason != null && !reason.isBlank()) {
            extra.put("reason", reason);
        }
        return emit(agentId, AgentEventType.USER_MESSAGE_DEFERRED, conversationId, messageIds,
                extra, EventPriority.NORMAL);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private int emit(String agentId, AgentEventType step, String conversationId,
                     Collection<String> messageIds, Map<String, Object> extra, EventPriority priority) {
        if (agentId == null || agentId.isBlank() || messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            Map<String, Object> refs = new LinkedHashMap<>();
            refs.put("messageId", messageId);
            if (conversationId != null) {
                refs.put("conversationId", conversationId);
            }
            refs.putAll(extra);

            EventEnvelope envelope = EventEnvelope.withDeterministicId(
                    agentId, step, EventSource.CHAT_PLATFORM, messageId, refs);
            try {
                if (runtime.accept(envelope.withPriority(priority))) {
                    accepted++;
                }
            } catch (Exception e) {
                // 阶梯上报失败不能让主链崩掉: 她已经读到消息了, 那是既成事实,
                // 记不上只是"这一刻没留下痕迹", 不是"这件事没发生"
                log.warn("[Ladder] {} 上报 {} 失败(messageId={}): {}",
                        agentId, step.wire(), messageId, e.getMessage());
            }
        }
        return accepted;
    }

    /** 一次上报多条时的消息 id 提取 —— 从一批 {@code MessageView} 拿 id 列表。 */
    public static List<String> idsOf(MessageBatch batch) {
        return batch == null ? List.of() : batch.messages().stream()
                .map(com.luxera.companion.contracts.api.MessageView::getId)
                .toList();
    }
}
