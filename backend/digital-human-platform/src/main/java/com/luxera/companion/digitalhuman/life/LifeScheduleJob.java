package com.luxera.companion.digitalhuman.life;

import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V10 §7.4 LifeScheduleJob: 时间触发轮询 —— 到点事件分发执行。
 * cron 配置化(app.scheduler.life-schedule-cron, 测试环境禁用)。
 *
 * <h2>V11 Phase 5 加了什么: 事实照做, 反应变事件</h2>
 * 两件事在这里被<b>刻意分开</b>:
 * <ol>
 *   <li>世界的那一半 —— 活动结束、计划激活 —— {@link LifeEventDispatcher#dispatch} 照旧
 *       <b>同步执行</b>。它是事实, 不是她的想法: 如果把这一步也改成"投一封信, 谁来拆谁负责",
 *       那她一旦在睡觉/被暂停, 活动就永远不会结束, 而"活动还在进行中"是一个会被别处读到
 *       的状态(作息、可用性、注意力)。</li>
 *   <li>她的那一半 —— "她注意到生活变了、并可能因此做点什么" —— 变成一个
 *       {@link AgentEventType#LIFE_EVENT} <b>投进信箱</b>, 由 {@code ProactiveActionConsumer}
 *       在她自己的线程上拆。</li>
 * </ol>
 * 这正是设计文档 §17.2 那句话的形状: Scheduler 产生 LIFE_EVENT, 而不是 Scheduler 直接
 * 让她聊天。
 *
 * <h2>为什么投信不能失败于"她正忙"</h2>
 * {@link AgentMailbox#accept} 不判暂停、不判忙闲, 只落库并试着投递; 投不出去的信留在
 * PENDING 等她。于是"那一刻她的生活变了"这件事不会因为投递时机不好而消失 ——
 * 这是把直接调用换成投信的全部收益。
 */
@Slf4j
@Component
public class LifeScheduleJob {

    private final LifeScheduleStore store;
    private final LifeEventDispatcher dispatcher;
    private final AgentMailbox mailbox;
    private final V11ProactiveSwitch v11;
    private final ProactiveActionRecorder recorder;

    public LifeScheduleJob(LifeScheduleStore store, LifeEventDispatcher dispatcher,
                           AgentMailbox mailbox, V11ProactiveSwitch v11,
                           ProactiveActionRecorder recorder) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.mailbox = mailbox;
        this.v11 = v11;
        this.recorder = recorder;
    }

    @Scheduled(cron = "${app.scheduler.life-schedule-cron:*/20 * * * * *}")
    public void fireDueEvents() {
        List<LifeScheduleRecord> due = store.dueEvents(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        for (LifeScheduleRecord record : due) {
            try {
                boolean ok = dispatcher.dispatch(record);
                if (ok) {
                    store.markDone(record.getId());
                    publishLifeEvent(record);
                } else {
                    store.markFailed(record.getId(), "dispatch rejected");
                }
            } catch (Exception e) {
                log.warn("[LifeScheduler] 事件执行失败 schedule={}: {}", record.getScheduleId(), e.getMessage());
                store.markFailed(record.getId(), e.getMessage());
            }
        }
    }

    /**
     * 把"她的生活刚刚变了一格"告诉她。失败只记日志。
     *
     * <p>幂等键用 {@code scheduleId}: 一个排程项只对应一次生活变化, 而如果
     * {@code markDone} 那次写库失败了, 下一轮会重新走到这里 —— 那时同一个键会让信箱认出
     * 重复并丢掉第二封, 而不是让她经历两次"活动结束了"。用 {@code record.getId()} 也一样,
     * 但 {@code scheduleId} 是这段代码里唯一被别处也用作身份的值(`plan-reminder-<id>` /
     * `activity-end-<id>`), 用它省掉一次"这两个 id 是不是一回事"的追问。
     *
     * <p>不投递的情况只有两种, 而且都<b>不该</b>算失败: V11 主动行为没开
     * (那时投出去也没有消费者, 只会让信箱长草), 以及 {@code personId} 为空
     * (一条生活事件如果不知道属于谁, 它就不是她的事)。
     */
    private void publishLifeEvent(LifeScheduleRecord record) {
        if (!v11.isActive() || record.getPersonId() == null) {
            return;
        }
        try {
            Map<String, Object> refs = new LinkedHashMap<>();
            refs.put("lifeEventType", record.getEventType());
            refs.put("scheduleId", record.getScheduleId());
            if (record.getPayload() != null) {
                // payload 里是 id 与标题(活动/计划的坐标), 不是聊天正文 ——
                // 但还是要过一遍信封的白名单: 将来谁往里塞了正文字段, 这里会当场炸。
                refs.putAll(record.getPayload());
            }
            mailbox.accept(EventEnvelope.withDeterministicId(
                    record.getPersonId(), AgentEventType.LIFE_EVENT, EventSource.LIFE_SIMULATION,
                    record.getScheduleId(), refs));
        } catch (Exception e) {
            // 信封构造失败(比如 payload 里混进了内容类键)或落库失败 —— 生活的变化
            // <b>已经发生了</b>, 只是她这次没被告知。不能因此把排程项退回去重做。
            recorder.recordError();
            log.warn("[LifeScheduler] 生活事件投递失败 schedule={}: {}",
                    record.getScheduleId(), e.getMessage());
        }
    }
}

