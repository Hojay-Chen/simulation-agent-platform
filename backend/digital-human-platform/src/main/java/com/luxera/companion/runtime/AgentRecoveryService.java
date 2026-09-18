package com.luxera.companion.runtime;

import com.luxera.companion.mailbox.AgentInboxEntry;
import com.luxera.companion.mailbox.AgentInboxRepository;
import com.luxera.companion.mailbox.AgentMailbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * V11 §4.1 —— <b>重启之后, 接着做</b>。
 *
 * <h2>它修的是什么</h2>
 * 今天进程一重启, 内存队列里排队的事件全部消失, 而且消失得<b>无声无息</b>:
 * 数据库里找不到任何痕迹说明"她本来该处理这条消息"。于是用户看到的是
 * "她没回", 而不是"系统丢了"。这两件事在运维上完全不同 ——
 * 前者会被归因成性格, 后者才是故障。
 *
 * <p>信箱落库之后, "没拆的信"变成一个可以查的行。恢复流程就只是把那些行重新投递 ——
 * 没有魔法, 也不需要扫描或推断。
 *
 * <h2>它不做什么</h2>
 * <ul>
 *   <li><b>不补课</b>。暂停三天后恢复, 那三天积压的消息不会在这里被一次性灌进来。
 *       那是"她醒来发现错过了什么"的语义, 属于唤醒补课({@code WakeupCatchUpService}),
 *       它有自己的一套判断(该不该提、提哪几条)。混在一起会让她在恢复的瞬间
 *       一口气回几十条消息, 那是最糟的一种"活着"。</li>
 *   <li><b>不绕过开关</b>。重放走的是 {@link AgentMailbox#deliver}, 而它第一件事
 *       就是问 agent 开关。暂停中的 agent 一条都不会处理 —— 包括重启后。</li>
 *   <li><b>不因为恢复失败而拒绝启动</b>。见 {@link #run}。</li>
 * </ul>
 */
@Service
@Slf4j
@Order(200)
public class AgentRecoveryService implements ApplicationRunner {

    private final AgentMailbox mailbox;
    private final AgentInboxRepository inbox;

    /**
     * 启动时是否自动恢复。默认 true —— 一个"重启后不恢复"的默认值会让这个功能
     * 在真正需要它的那一刻(部署重启)恰好不生效。
     */
    @Value("${app.v11.runtime.recover-on-startup:true}")
    private boolean recoverOnStartup = true;

    public AgentRecoveryService(AgentMailbox mailbox, AgentInboxRepository inbox) {
        this.mailbox = mailbox;
        this.inbox = inbox;
    }

    /**
     * 启动钩子。
     *
     * <p>整个方法包在 try/catch 里并且<b>吞掉异常</b>: 恢复是尽力而为的事,
     * 一个读不到信箱的数据库不该让整个平台起不来。今天 53 个 agent 全部处于暂停,
     * 这个方法在默认部署下什么也不会做 —— 这是正确的, 不是没生效。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!recoverOnStartup) {
            log.info("[Recover] app.v11.runtime.recover-on-startup=false, 跳过启动恢复");
            return;
        }
        try {
            RecoveryReport report = recoverAll();
            if (report.reclaimed() > 0 || report.replayed() > 0 || report.stillWaiting() > 0) {
                log.info("[Recover] 启动恢复完成: 回收租约 {} 条, 重放 {} 条, 仍等待 {} 条(多半是已暂停的 agent)",
                        report.reclaimed(), report.replayed(), report.stillWaiting());
            }
        } catch (Exception e) {
            log.warn("[Recover] 启动恢复失败, 平台照常启动(下一次信箱维护会再试)", e);
        }
    }

    /** 全量恢复: 先回收死掉的租约, 再重放。 */
    public RecoveryReport recoverAll() {
        int reclaimed = mailbox.reclaimStale();
        int replayed = mailbox.replayPending();
        long waiting = inbox.countByStatus(AgentInboxEntry.S_PENDING);
        return new RecoveryReport(reclaimed, replayed, waiting);
    }

    /**
     * 单个 agent 的恢复 —— 恢复一个刚被重新启用的 agent 时用。
     *
     * <p>注意它<b>不会</b>重放超出窗口的老条目: 那些已经被保留期转成 DROPPED 了。
     * 这不是遗漏, 是刻意的 —— 见 {@link AgentInboxRepository} 里对窗口的解释。
     */
    public RecoveryReport recover(String agentId) {
        List<AgentInboxEntry> pending =
                inbox.findTop200ByAgentIdAndStatusOrderByOccurredAtAsc(agentId, AgentInboxEntry.S_PENDING);
        int replayed = 0;
        for (AgentInboxEntry e : pending) {
            if (mailbox.deliver(e.getId())) {
                replayed++;
            }
        }
        return new RecoveryReport(0, replayed, pending.size() - replayed);
    }

    /**
     * @param reclaimed    租约超时被捞回的条数
     * @param replayed     真的被消费掉的条数
     * @param stillWaiting 还原样躺在信箱里的条数
     */
    public record RecoveryReport(int reclaimed, int replayed, long stillWaiting) {
    }
}
