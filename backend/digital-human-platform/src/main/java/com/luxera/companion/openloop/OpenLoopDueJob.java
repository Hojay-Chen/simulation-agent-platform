package com.luxera.companion.openloop;

import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.wakeup.AgentWakeupService;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V11 Phase 5 —— <b>悬着的事到点了</b>。三类触发器里的 OpenLoop 那一路。
 *
 * <pre>
 *   §17.2  Scheduler 产生 LIFE_EVENT / WAKEUP / INTENTION_TRIGGER 再进入 Agent Mailbox
 * </pre>
 *
 * <h2>为什么它与 OpenLoopJob 是两个类</h2>
 * {@link OpenLoopJob} 是个<b>清洁工</b>: 它把没人再管的旧悬案标成 ABANDONED, 不产生任何
 * 认知上的后果(类注释写的"Level 0, 不调用 LLM"就是这个意思)。本类是个<b>触发器</b>:
 * 它产生的那封信会让她想起你。
 *
 * <p>合成一个类的代价很具体: 清洁工跑的频率与触发器跑的频率会绑死, 而这两件事没有理由
 * 同频 —— 一个要一天跑一次(扫全表找僵尸), 一个要几分钟跑一次(到点就得说)。
 * 更要紧的是, 那时"她的行为"就寄生在一个以"删除"为职责的类里, 后来读代码的人
 * 会因为类名而低估它。
 *
 * <h2>它不判断"该不该问"</h2>
 * 本类只判断"到点了"(一个关于时钟的、可以被穷举的事实)。价值够不够、会不会打扰、
 * 该不该等 —— 全在 {@code ProactiveEngine} 里, 那门引擎为此已经有一套成本曲线。
 * 在这里提前筛一遍的后果是把同一套判断写成两份, 而两份判断迟早会不一致;
 * 不一致的那天, 症状是"她知道这件事到点了, 却从来不问", 排查时两边看起来都对。
 *
 * <h2>幂等键里为什么带时刻</h2>
 * 键是 {@code openloop:<id>@<到点时刻>}。只带 id 的话, 一个悬案一辈子只会响一次 ——
 * 第一封信拆掉之后, 第二次改期到点会被信箱当成重复而静默丢掉。带上时刻之后,
 * <b>"同一个悬案的同一个到点时刻"</b>才是重复, 而这正是重复的定义。
 */
@Component
@Slf4j
public class OpenLoopDueJob {

    /** 还没了结的状态。与 {@code OpenLoopService.activeLoops} 用的是同一对值。 */
    private static final List<String> DUE_STATUSES = List.of("OPEN", "WAITING");

    /** 一轮最多投几封。与 AgentWakeupJob 同一个理由: 这个循环跑在共用调度线程上。 */
    private static final int BATCH = 200;

    private final OpenLoopRepository repo;
    private final AgentMailbox mailbox;
    private final V11ProactiveSwitch v11;
    private final ProactiveActionRecorder recorder;

    /**
     * 往回看多久。
     *
     * <p>必须有限: 一个三个月前就该有结果的悬案, 今天再问她一次已经不是"关心"了。
     * 窗口内的会补问(进程停过、她当时被暂停、上游写库晚了), 窗口外的不再打扰 ——
     * 那些会由 {@link OpenLoopJob} 按"长期无进展"标成 ABANDONED 自然收场。
     */
    @Value("${app.v11.proactive.open-loop-lookback-days:7}")
    private int lookbackDays;

    public OpenLoopDueJob(OpenLoopRepository repo, AgentMailbox mailbox,
                          V11ProactiveSwitch v11, ProactiveActionRecorder recorder) {
        this.repo = repo;
        this.mailbox = mailbox;
        this.v11 = v11;
        this.recorder = recorder;
    }

    @Scheduled(cron = "${app.scheduler.open-loop-due-cron:0 */3 * * * *}")
    @Transactional(readOnly = true)
    public void fireDue() {
        if (!v11.isActive()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusDays(Math.max(1, lookbackDays));
            List<OpenLoop> due = repo
                    .findByStatusInAndExpectedResolutionAtBetweenOrderByExpectedResolutionAtAsc(
                            DUE_STATUSES, from, now, PageRequest.of(0, BATCH));
            int sent = 0;
            for (OpenLoop loop : due) {
                if (publish(loop)) {
                    sent++;
                }
            }
            if (sent > 0) {
                log.info("[OpenLoop] {} 件悬着的事到点了, 已投进各自的心智信箱", sent);
            }
        } catch (Exception e) {
            log.warn("[OpenLoop] 到点投递失败: {}", e.getMessage());
        }
    }

    private boolean publish(OpenLoop loop) {
        if (loop.getCompanionId() == null || loop.getExpectedResolutionAt() == null) {
            return false;
        }
        try {
            Map<String, Object> refs = new LinkedHashMap<>();
            refs.put("openLoopId", loop.getId());
            refs.put("title", loop.getTitle());
            refs.put("ownerType", loop.getOwnerType());
            refs.put("importance", loop.getImportance());
            refs.put("emotionalWeight", loop.getEmotionalWeight());
            refs.put("expectedResolutionAt", loop.getExpectedResolutionAt().toString());

            String key = AgentWakeupService.key(AgentWakeupService.SRC_OPEN_LOOP, loop.getId())
                    + "@" + loop.getExpectedResolutionAt().toEpochSecond(ZoneOffset.UTC);
            return mailbox.accept(EventEnvelope.withDeterministicId(
                    loop.getCompanionId(), AgentEventType.OPEN_LOOP_DUE, EventSource.INTENTION,
                    key, refs));
        } catch (Exception e) {
            recorder.recordError();
            log.warn("[OpenLoop] 悬案 {} 投递失败: {}", loop.getId(), e.getMessage());
            return false;
        }
    }
}
