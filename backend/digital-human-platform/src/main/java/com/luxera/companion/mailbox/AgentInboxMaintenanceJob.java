package com.luxera.companion.mailbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V11 §4.1 —— 信箱的看护人。三件事, 每两分钟一遍。
 *
 * <ol>
 *   <li><b>回收租约</b> —— 消费者中途死了(或整个 JVM 重启), 信不能永远卡在 CONSUMING。</li>
 *   <li><b>重放</b> —— 把近期还没拆的信重新投递。这是"崩溃后接着做"的入口,
 *       <b>不是</b>"她错过了什么"的入口(那是唤醒补课的事)。</li>
 *   <li><b>清理</b> —— 超过保留期的信转 DROPPED。没有这一步, 一个被长期暂停的 agent
 *       会无限堆积事件, 而暂停正是用户用来省 token 的手段 ——
 *       一个会因为省钱而撑爆数据库的功能是不能上线的。</li>
 * </ol>
 *
 * <p>顺序有讲究: 先回收(<b>让能重试的重新可投递</b>)再重放, 否则这一轮就会漏掉
 * 那些刚好在租约边上过期的条目, 要再等两分钟。
 *
 * <p>默认 cron 刻意避开整点与 :30 —— 这个任务每两分钟跑一次, 若落在 :00 就会和
 * 一堆整点任务(<code>life-tick</code>、<code>open-loop</code>、<code>thought-maintenance</code>)
 * 撞在同一秒, 而它们全都会去读 {@code companions} 表。
 */
@Component
@Slf4j
public class AgentInboxMaintenanceJob {

    private final AgentMailbox mailbox;

    @Value("${app.v11.runtime.inbox-retention-hours:24}")
    private int retentionHours = 24;

    public AgentInboxMaintenanceJob(AgentMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Scheduled(cron = "${app.scheduler.inbox-maintenance-cron:0 */2 * * * *}")
    public void run() {
        try {
            mailbox.reclaimStale();
            mailbox.replayPending();
            mailbox.dropExpired(retentionHours);
        } catch (Exception e) {
            // 交给调度器的下一次触发重试, 不让异常冒到 Spring 的调度线程上
            log.warn("[Mailbox] 信箱维护任务出错", e);
        }
    }
}
