package com.luxera.companion.life;

import com.luxera.companion.agent.CompanionSchedule;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** 生活上下文提供: "她今天在干嘛" / 供 Prompt 与 API */
@Component
public class LifeContextProvider {

    private final CompanionLifeService lifeService;
    private final LifeActivityRepository activityRepo;
    private final CompanionSchedule schedule;

    public LifeContextProvider(CompanionLifeService lifeService, LifeActivityRepository activityRepo,
                               CompanionSchedule schedule) {
        this.lifeService = lifeService;
        this.activityRepo = activityRepo;
        this.schedule = schedule;
    }

    @Transactional(readOnly = true)
    public CompanionLife current(String companionId) {
        return lifeService.getOrCreate(companionId);
    }

    @Transactional(readOnly = true)
    public List<LifeActivity> todayActivities(String companionId) {
        LocalDate today = LocalDate.now();
        return activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    /** 今天从早到晚的叙述(回答"你今天干嘛了", 来自 Life Runtime 而非随机) */
    @Transactional(readOnly = true)
    public String describeToday(String companionId, String name) {
        List<LifeActivity> acts = todayActivities(companionId);
        if (acts.isEmpty()) {
            return name + "今天还没开始活动。";
        }
        List<String> lines = acts.stream()
                .filter(a -> !"SLEEP".equals(a.getType()))
                .map(a -> a.getPlannedStart() != null
                        ? a.getPlannedStart().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + a.getTitle()
                        : a.getTitle())
                .collect(Collectors.toList());
        return name + "今天: " + String.join(" · ", lines) + "。";
    }

    /** 此刻一句(注入 Prompt) */
    @Transactional(readOnly = true)
    public String currentMoment(String companionId, String name, LocalDateTime now) {
        return schedule.describe(companionId, name, now);
    }
}
