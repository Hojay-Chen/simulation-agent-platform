package com.luxera.companion.openloop;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OpenLoopRepository extends JpaRepository<OpenLoop, String> {
    List<OpenLoop> findByCompanionIdAndStatusInOrderByImportanceDesc(String companionId, List<String> statuses);
    List<OpenLoop> findByStatusInAndExpectedResolutionAtBefore(List<String> statuses, LocalDateTime now);

    /**
     * 刚刚到点的那些 —— V11 Phase 5 的事件触发器用。
     *
     * <p>与上面那条 {@code Before} 的区别是<b>下界</b>, 而那个下界不是为了省事:
     * 没有它, 一个三个月前就该有结果的悬案会在每次扫描时都被判定为"到点了",
     * 于是她每隔几分钟就想起来问一次同一件事。锚定一个窗口之后, "到点"是一次
     * 发生在具体时刻的事件, 而不是一个持续为真的状态。
     *
     * <p>{@code Between} + {@link Pageable} 都用得上: 前者走
     * {@code expected_resolution_at} 上的索引, 后者保证这个跑在共用调度线程上的查询
     * 不会因为一次数据异常(比如批量导入造出十万条到点悬案)而拖垮全平台的定时任务。
     */
    List<OpenLoop> findByStatusInAndExpectedResolutionAtBetweenOrderByExpectedResolutionAtAsc(
            List<String> statuses, LocalDateTime from, LocalDateTime to, Pageable page);
}
