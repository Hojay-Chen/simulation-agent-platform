package com.luxera.companion.wakeup;

import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设计文档 §17.2 —— <b>Scheduler 不直接让 Agent 聊天</b>。
 *
 * <pre>
 *   §17.2  Scheduler 产生 LIFE_EVENT / WAKEUP / INTENTION_TRIGGER, 再进入 Agent Mailbox
 * </pre>
 * 本类就是那个"产生 WAKEUP"的地方: 到点的闹钟不再就地触发任何认知, 而是<b>投一封信</b>。
 * 拆信与决策是消费者的事(见 {@code ProactiveActionConsumer})。
 *
 * <h2>为什么这个区别不是搬了个家</h2>
 * 直接调用的世界里, "闹钟响了"与"她做了某事"之间没有接缝 —— 一次异常、一次部署、
 * 她当时被暂停, 那个时刻就<b>永远消失了</b>, 而且没有任何痕迹说明它存在过。
 * 投信之后, 那个时刻变成了一行可重放、可计数、可丢弃的记录:
 * 她后来才知道自己该醒过, 而这件事在账上是看得见的。
 *
 * <h2>暂停中的 agent 照样投信</h2>
 * 一个自然的想法是"她被暂停了, 就别投了"。这是错的: 暂停是<b>用户让她别说话</b>,
 * 不是"这段时间对她不存在"。{@link AgentMailbox#accept} 收下这封信(它不判暂停),
 * 拆信那一步才判 —— 于是她被恢复之后, 那些闹钟响过的时刻仍然在信箱里等她。
 * 反过来(暂停时不投)会得到一个安静的后果: 恢复之后她对自己睡过的那段时间一无所知。
 *
 * <h2>两根调度线程的纪律</h2>
 * Spring 默认的 {@code TaskPoolTaskScheduler} 池大小是 1, 全平台 ~19 个 {@code @Scheduled}
 * 共用那一根(含 5 秒一次的 outbox-relay)。所以本类<b>只做三件常数时间的事</b>:
 * 查一批到点的行、投信、标记。认知一个字都不跑 —— 那是消费线程的事
 * (与 {@code V11TurnSealJob} 那条纪律同源: 调度线程只做"查开关 + 入队")。
 */
@Component
@Slf4j
public class AgentWakeupJob {

    /**
     * 一轮最多投几封。
     *
     * <p>有上限是必须的, 不是保守: 这个循环跑在共用调度线程上, 而"库里积压了十万个
     * 过期闹钟"是一旦发生就会让所有定时任务一起停摆的故障形态。投不完的下一轮还在。
     */
    private static final int BATCH = 200;

    private final AgentWakeupService wakeups;
    private final AgentMailbox mailbox;
    private final V11ProactiveSwitch v11;

    @Value("${app.scheduler.agent-wakeup-retention-days:7}")
    private int retentionDays;

    public AgentWakeupJob(AgentWakeupService wakeups, AgentMailbox mailbox, V11ProactiveSwitch v11) {
        this.wakeups = wakeups;
        this.mailbox = mailbox;
        this.v11 = v11;
    }

    @Scheduled(cron = "${app.scheduler.agent-wakeup-cron:*/10 * * * * *}")
    public void fireDue() {
        if (!v11.isActive()) {
            // 两个都关 = 一个字都不发。刻意不是"发了但没人收": 那样信会攒在信箱里,
            // 而切流那天它们会一起被拆开(见 V11ProactiveSwitch 的类注释)。
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int fired = 0;
            for (AgentWakeup w : wakeups.due(now, BATCH)) {
                // 逐个隔开, 而不是一个 try 包住整个循环: 一个投不出去的闹钟(它的行本身
                // 有问题、或者那一刻库刚好抖了一下)会把<b>它后面那一批</b>一起留在原地,
                // 而它们下一轮还会被读到 —— 于是每一个 tick 都从同一个毒药行开始,
                // 队列头部永远推不动。逐条隔开之后, 坏的那个自己重试, 好的照常走。
                try {
                    if (emit(w, now)) {
                        fired++;
                    }
                } catch (Exception e) {
                    log.warn("[Wakeup] 闹钟 {} 投递失败, 留在等待中: {}", w.getId(), e.getMessage());
                }
            }
            if (fired > 0) {
                log.info("[Wakeup] 醒了 {} 个闹钟 (等待中 {} 个)", fired, wakeups.pendingCount());
            }
        } catch (Exception e) {
            // 调度线程上抛出去会静默吃掉整个 tick —— 记下来, 下一轮还在
            log.warn("[Wakeup] 投递失败: {}", e.getMessage());
        }
    }

    /**
     * 投一封信, 然后把这行记成历史。
     *
     * <p>{@code accept} 的返回值<b>不参与判断</b>: 它返回 false 只意味着"这封信已经在信箱里"
     * (幂等短路或并发重复)。两种情况下这个闹钟都已经完成使命, 所以都标记 fired ——
     * 否则一封已经在信箱里的信会让它的闹钟每 10 秒重试一次, 直到永远。
     *
     * <p>信封的幂等键是 <b>{@code 行的 id + 这一次响的时刻}</b>, 两个都不能少:
     * <ul>
     *   <li>不能用 sourceKey —— 它恰恰是"同一个闹钟"的身份, 在"她改了主意、把它推后"时
     *       一个字都不变, 于是第二次响会算出同一个 eventId 被静默丢掉;</li>
     *   <li>也不能只用行的 id —— {@link AgentWakeupService#schedule} 对已经响过的闹钟是
     *       <b>复活同一行</b>而不是新建(否则新行会撞上旧行的幂等键), 所以同一个 id
     *       会经历多次"排期 → 响"的循环, 只用 id 的话第二轮以后全部静默丢失 ——
     *       表现为"她第一次没回, 之后那个闹钟就再也没响过"。</li>
     * </ul>
     * 两者合起来才是正确的语义: <b>同一个闹钟的第几次响</b>。
     */
    private boolean emit(AgentWakeup w, LocalDateTime now) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("wakeupId", w.getId());
        refs.put("source", w.getSourceKey());
        refs.put("reason", w.getReason() == null ? "" : w.getReason());
        refs.put("scheduledFor", w.getWakeAt().toString());
        refs.put("firedAt", now.toString());

        // 时刻取 epoch 秒(纯数字), 于是拼出来的键只有字母数字与连字符 ——
        // withDeterministicId 的 sanitize 不会把它改成下划线。
        String fireKey = w.getId() + "-" + w.getWakeAt().toEpochSecond(ZoneOffset.UTC);
        EventEnvelope envelope = EventEnvelope.withDeterministicId(
                w.getAgentId(), w.getEventType(), EventSource.SCHEDULE, fireKey, refs);
        mailbox.accept(envelope);
        wakeups.markFired(w, now);
        return true;
    }

    /**
     * 清历史。一天一次, 刻意<b>不在</b>投递那个循环里: 它扫的是 {@code fired_at},
     * 而那一列上没有索引 —— 每 10 秒做一次全表扫描是另一种形式的停摆。
     */
    @Scheduled(cron = "${app.scheduler.agent-wakeup-purge-cron:0 40 4 * * *}")
    public void purgeFinished() {
        try {
            int days = Math.max(1, retentionDays);
            LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
            int purged = wakeups.purgeFinishedBefore(cutoff);
            if (purged > 0) {
                log.info("[Wakeup] 清掉了 {} 条 {} 天前的闹钟记录", purged, days);
            }
        } catch (Exception e) {
            log.warn("[Wakeup] 清理失败: {}", e.getMessage());
        }
    }
}
