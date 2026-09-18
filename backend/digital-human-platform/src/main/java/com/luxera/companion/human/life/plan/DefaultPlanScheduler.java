package com.luxera.companion.human.life.plan;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.5.5 —— {@link PlanScheduler} 的默认实现。
 *
 * <h2>它是一个<b>薄</b>适配层, 这是刻意的</h2>
 * 真正的存储与索引都在 {@link PlanBoard} / {@link PlanRevision} 里。
 * 本类只做三件事: 转成 {@link PlanTrigger}、记 tick 序号、把 {@link #cancel} 变成一次重排。
 *
 * <p><b>为什么不把索引直接做在这里</b>: 因为索引必须与"计划表的内容"严格一致,
 * 而重排会改变内容。让索引与内容住在同一个对象里（{@link PlanRevision} 构造时算一次）,
 * 就不存在"内容改了索引没跟上"这个失效模式 —— 而那类 bug 的症状是
 * "有些计划项永远不会被触发", 极难查。
 *
 * <h2>{@link #tickSequence} 的用途</h2>
 * 它让 {@link PlanTrigger} 能被稳定排序, 也让"同一个 tick 里触发了哪几项"
 * 可以在日志里被分组。用序号而不是时刻是因为<b>同一 tick 内的多项触发共享同一个时刻</b> ——
 * 靠时刻分不出它们。
 */
@Slf4j
public class DefaultPlanScheduler implements PlanScheduler {

    private final PlanBoard board;

    private final AtomicLong tickSequence = new AtomicLong();

    /** 累计触发次数 —— 诊断用。 */
    private long totalTriggers;

    /** 累计迟到（毫秒）—— "她拖延得越来越厉害了吗"这个问题的原始数据。 */
    private long totalLatenessMillis;

    public DefaultPlanScheduler(PlanBoard board) {
        this.board = Objects.requireNonNull(board, "计划表不能为空 —— 调度器只是它的适配层");
    }

    @Override
    public void register(PlanRevision revision) {
        Objects.requireNonNull(revision, "要启用的版本不能为空");
        log.info("[PlanScheduler] 启用 {} —— {} 项生效", revision.revisionId(),
                revision.liveItems().size());
    }

    @Override
    public void reschedule(PlanRevision revision) {
        Objects.requireNonNull(revision, "要重排到的版本不能为空");
        // 与 register 的差别只在日志语义上 —— 见 PlanScheduler 的接口注释
        log.info("[PlanScheduler] 重排到 {} 「{}」 —— 现存 {} 项, {} 项改动",
                revision.revisionId(), revision.reason(), revision.liveItems().size(),
                revision.mutations().size());
    }

    @Override
    public void cancel(PlanItemId id) {
        Objects.requireNonNull(id, "要取消的计划项 id 不能为空");
        Instant now = board.current().createdAt();
        // 外部指令也是一次重排 —— 因为它同样"改变了计划表"。
        // 把它伪装成别的东西会让历史里出现一个解释不通的状态变化
        board.apply(now, "外部指令取消了这一项",
                List.of(new PlanMutation.Remove(id, "被显式取消")));
    }

    @Override
    public List<PlanTrigger> dueAt(Instant time) {
        Objects.requireNonNull(time, "查询必须带仿真时刻");
        List<PlanItem> due = board.dueAt(time);
        if (due.isEmpty()) {
            return List.of();
        }

        long tick = tickSequence.incrementAndGet();
        List<PlanTrigger> triggers = new ArrayList<>(due.size());
        for (PlanItem item : due) {
            PlanTrigger trigger = new PlanTrigger(item, item.window().start(), time, tick);
            triggers.add(trigger);
            totalTriggers++;
            totalLatenessMillis += trigger.latenessMillis();
        }

        // 迟到单独升到 INFO: 它是行为数据, 而不是噪声。见 PlanTrigger 的说明
        for (PlanTrigger trigger : triggers) {
            if (trigger.late()) {
                log.info("[PlanScheduler] {}", trigger.describe());
            } else {
                log.debug("[PlanScheduler] {}", trigger.describe());
            }
        }
        return List.copyOf(triggers);
    }

    @Override
    public int scheduledCount() {
        return board.current().liveItems().size();
    }

    @Override
    public PlanRevision currentRevision() {
        return board.current();
    }

    /** 累计触发次数。 */
    public long totalTriggers() {
        return totalTriggers;
    }

    /**
     * 平均迟到毫秒数。
     *
     * <p>返回 {@code 0} 而不是在无触发时抛异常 —— "还没有数据"和"平均零延迟"
     * 在报表里应该都表现为 0, 而不是让报表生成崩掉。
     * 需要区分时看 {@link #totalTriggers()}。
     */
    public double averageLatenessMillis() {
        return totalTriggers == 0 ? 0.0 : (double) totalLatenessMillis / totalTriggers;
    }

    public String describe() {
        return "PlanScheduler[" + scheduledCount() + " 项生效, 累计触发 " + totalTriggers
                + " 次, 平均迟 " + Math.round(averageLatenessMillis()) + "ms]";
    }
}
