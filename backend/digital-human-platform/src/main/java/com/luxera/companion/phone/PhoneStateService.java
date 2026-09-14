package com.luxera.companion.phone;

import com.luxera.companion.agent.CompanionSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 手机状态服务(§五): 手机状态由作息+时间确定性派生 —— 上班静音/开会勿扰/晚间震动/睡觉勿扰。
 * 决定"消息能否通过通知触达她"(Phone Runtime 是消息生命周期的第一道门)。
 */
@Service
public class PhoneStateService {

    private final PhoneStateRepository repo;
    private final CompanionSchedule schedule;

    public PhoneStateService(PhoneStateRepository repo, CompanionSchedule schedule) {
        this.repo = repo;
        this.schedule = schedule;
    }

    /** 获取(或创建)当前手机状态, 并按作息刷新 */
    @Transactional
    public PhoneState current(String companionId, LocalDateTime now) {
        PhoneState s = repo.findByCompanionId(companionId).orElseGet(() -> {
            PhoneState n = new PhoneState();
            n.setCompanionId(companionId);
            return repo.save(n);
        });
        // 按作息派生刷新
        CompanionSchedule.Activity activity = schedule.activityFor(companionId, now);
        int hour = now.getHour();

        switch (activity) {
            case WORK_BUSY, WORK_AFTERNOON -> {
                s.setNotificationMode("silent");
                s.setSoundEnabled(false);
                s.setVibrationEnabled(false);
                s.setPhoneLocation("desk");
                s.setDoNotDisturb(false);
            }
            case MORNING -> {
                s.setNotificationMode("vibrate");
                s.setSoundEnabled(false);
                s.setVibrationEnabled(true);
                s.setPhoneLocation("hand");
                s.setDoNotDisturb(false);
            }
            case LUNCH, EVENING -> {
                s.setNotificationMode("vibrate");
                s.setSoundEnabled(true);
                s.setVibrationEnabled(true);
                s.setPhoneLocation("hand");
                s.setDoNotDisturb(false);
            }
            case LEISURE, LATE_NIGHT -> {
                s.setNotificationMode("vibrate");
                s.setSoundEnabled(true);
                s.setVibrationEnabled(true);
                s.setPhoneLocation("hand");
                s.setDoNotDisturb(false);
            }
            case SLEEP -> {
                s.setNotificationMode("dnd");
                s.setSoundEnabled(false);
                s.setVibrationEnabled(false);
                s.setPhoneLocation("other_room");
                s.setDoNotDisturb(true);
            }
            default -> { }
        }
        // 电池低 → 也可能忽略消息
        s.setBattery(Math.max(0.1, Math.min(1.0, 0.85 - (hour % 4) * 0.03)));
        s.setLastCheckedAt(now);
        return repo.save(s);
    }

    /** 通知触达度(0-1): 手机状态决定消息能被注意到的上限 */
    public double notificationSalience(PhoneState s) {
        if (s == null) return 0.5;
        double base = switch (s.getNotificationMode() == null ? "vibrate" : s.getNotificationMode()) {
            case "dnd" -> 0.0;
            case "silent" -> 0.25;
            case "vibrate" -> 0.55;
            case "sound" -> 0.8;
            default -> 0.5;
        };
        // 手机在手 → 更容易注意; 其他房间/包里 → 更慢
        String loc = s.getPhoneLocation() == null ? "hand" : s.getPhoneLocation();
        switch (loc) {
            case "hand" -> base += 0.25;
            case "desk" -> base += 0.0;
            case "bag" -> base -= 0.15;
            case "other_room" -> base -= 0.35;
            default -> { }
        }
        if (s.isDoNotDisturb()) base = 0.0;
        return Math.max(0, Math.min(1, base));
    }
}
