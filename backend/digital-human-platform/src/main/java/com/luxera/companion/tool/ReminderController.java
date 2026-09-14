package com.luxera.companion.tool;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒的 REST 面 —— <b>契约一字未改</b>(LAP v1 R5)。
 *
 * <p>四个端点、请求体、响应 JSON 与前端 {@code types/index.ts} 里的 {@code Reminder} 类型
 * 都还是原来的样子。改的只有实现: 数据不再来自数字人自己的 {@code reminders} 表, 而是来自
 * {@code com.luxera.reminder} 应用。前端因此不需要知道提醒换了主人 —— 这正是"改造"与
 * "重写"的区别: 所有权变了, 接口没变。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public ReminderController(ReminderService reminderService, CompanionService companionService,
                              CurrentUser currentUser) {
        this.reminderService = reminderService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Reminder> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.list(userId, companionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reminder create(@PathVariable String companionId, @RequestBody CreateRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.create(userId, companionId,
                req.getType(), req.getTitle(), req.getContent(), req.getRemindAt());
    }

    @PutMapping("/{reminderId}/done")
    public Reminder markDone(@PathVariable String companionId, @PathVariable String reminderId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.markDone(userId, reminderId);
    }

    @DeleteMapping("/{reminderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String companionId, @PathVariable String reminderId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        reminderService.delete(userId, reminderId);
    }

    @Data
    public static class CreateRequest {
        private String type;
        private String title;
        private String content;
        private LocalDateTime remindAt;
    }
}
