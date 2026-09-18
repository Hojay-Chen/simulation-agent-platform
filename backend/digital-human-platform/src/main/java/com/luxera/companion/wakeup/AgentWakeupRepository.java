package com.luxera.companion.wakeup;

import com.luxera.companion.world.AgentEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentWakeupRepository extends JpaRepository<AgentWakeup, String> {

    /** 幂等查找: 同一个来源重复排期是"改主意", 不是新增一行。 */
    Optional<AgentWakeup> findByAgentIdAndEventTypeAndSourceKey(String agentId, AgentEventType eventType,
                                                                String sourceKey);

    /**
     * 到点了、还在等的那些 —— <b>按时刻升序</b>, 先到先响。
     *
     * <p>带 {@link Pageable} 是必须的: 这个查询跑在调度线程上(全平台共用一根),
     * 而"库里有十万个过期闹钟"是一旦发生就会让所有定时任务一起停摆的故障形态。
     * 宁可少发一批 —— 剩下的下一轮还在。
     */
    List<AgentWakeup> findByStatusAndWakeAtBeforeOrderByWakeAtAsc(String status, LocalDateTime now,
                                                                 Pageable page);

    /** 她下一次什么时候醒(最早的那个)。诊断用。 */
    Optional<AgentWakeup> findFirstByAgentIdAndStatusOrderByWakeAtAsc(String agentId, String status);

    List<AgentWakeup> findByAgentIdAndStatusOrderByWakeAtAsc(String agentId, String status);

    /** 她不再等这件事了。 */
    List<AgentWakeup> findByAgentIdAndSourceKeyAndStatus(String agentId, String sourceKey, String status);

    long countByStatus(String status);

    /** 派生删除会先把实体读出来再逐条删 —— 这是有意的: 这一列上没有级联, 也没有外键。 */
    long deleteByStatusInAndFiredAtBefore(List<String> statuses, LocalDateTime cutoff);
}
