package com.luxera.companion.digitalhuman.life;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * life_schedule 表访问。
 */
@Repository
public interface LifeScheduleRepository extends JpaRepository<LifeScheduleRecord, String> {

    Optional<LifeScheduleRecord> findByScheduleId(String scheduleId);

    boolean existsByScheduleId(String scheduleId);

    /** 到期待触发事件(按触发时间升序) */
    List<LifeScheduleRecord> findTop50ByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
            String status, LocalDateTime now);
}
