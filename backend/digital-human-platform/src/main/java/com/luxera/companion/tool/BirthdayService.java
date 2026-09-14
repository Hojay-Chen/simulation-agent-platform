package com.luxera.companion.tool;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生日服务: 每日确保每位伴侣有一条待触发的生日提醒。
 * (伴侣生日是"出生日期",年龄动态计算,生日提醒每年一次)
 *
 * <p>LAP v1 R5: 它不再写 {@code reminders} 表, 而是把"今天是她的生日"这件事交给
 * {@link ReminderService} → {@code reminder.create}。
 *
 * <p><b>去重也一并交出去了。</b> 旧代码每天先查一次"今年建过没有"
 * ({@code findByCompanionIdAndStatus...anyMatch(type == "birthday")}), 那是把一条业务规则
 * 写在<em>定时任务的调用方</em>身上 —— 于是"每年最多一条"这件事只有每天跑一次的这段代码
 * 记得, 任何别的入口(前端、对话、MCP)都绕过去了。现在规则长在数据旁边: 应用收到一条
 * {@code type=birthday} 且同一个 owner/companion 已有待办时, 直接返回那一条, 不新建。
 *
 * <p>因此这里是幂等的: 一天跑十次和跑一次结果相同, 重复调用返回同一条提醒。
 * 每个伴侣单独 try/catch —— 一位伴侣的提醒建不出来(比如她的 owner 身份有问题)不该让
 * 后面所有人的生日都停下来。
 */
@Slf4j
@Component
public class BirthdayService {

    private final CompanionRepository companionRepo;
    private final ReminderService reminderService;

    public BirthdayService(CompanionRepository companionRepo, ReminderService reminderService) {
        this.companionRepo = companionRepo;
        this.reminderService = reminderService;
    }

    @Scheduled(cron = "${app.scheduler.birthday-cron}")
    public void ensureBirthdayReminders() {
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null || c.getBirthDate() == null) continue;
            try {
                reminderService.create(c.getUserId(), c.getId(), "birthday",
                        "今天是" + c.getName() + "的生日",
                        "祝" + c.getName() + "生日快乐,一年又一年,你都在。",
                        nextBirthday(c.getBirthDate(), 8));
            } catch (Exception e) {
                log.warn("[生日提醒] 伴侣 {} 的提醒没能建出来: {}", c.getId(), e.getMessage());
            }
        }
    }

    private static LocalDateTime nextBirthday(LocalDate birth, int hour) {
        LocalDate today = LocalDate.now();
        LocalDate next = birth.withYear(today.getYear());
        if (next.isBefore(today) || next.isEqual(today)) {
            next = next.plusYears(1);
        }
        return next.atTime(hour, 0);
    }
}
