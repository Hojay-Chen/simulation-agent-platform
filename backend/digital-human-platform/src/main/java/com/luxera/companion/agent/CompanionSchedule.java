package com.luxera.companion.agent;

import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.sleep.SleepModel;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 日常时间表(重构)。
 *
 * 核心原则: 作息不是 Schedule, 而是 Emergent Behavior。
 * 睡眠状态完全由 {@link SleepModel}(睡眠压力 + 昼夜节律 + 动机)决定,
 * 不再有 "if time >= sleepTime → SLEEP" 的硬规则。
 *
 * activityFor 返回的是"理想社会活动"(工作/休闲时段), 但:
 * - 若 SleepModel 判定她在睡 → SLEEP(覆盖一切)
 * - 夜班/酒吧工作 → chronotype=LATE + 夜间上班时段, 通过工作动机自然压制睡意
 */
@Component
public class CompanionSchedule {

    public enum Activity {
        SLEEP, MORNING, WORK_BUSY, LUNCH, WORK_AFTERNOON, EVENING, LEISURE, LATE_NIGHT
    }

    /** 一天的时段块(供 Life 模拟生成 LifeActivity) */
    public record TimeBlock(String type, String title, LocalTime start, LocalTime end) {}

    /** 作息: 只是"理想社会活动"时段; 睡眠由 SleepModel 决定 */
    private record Schedule(int workStart, int workEnd) {}

    private final PersonaService personaService;
    private final SleepModel sleepModel;

    /** 夜班识别缓存 */
    private final Map<String, Boolean> nightWorkerCache = new ConcurrentHashMap<>();
    private final Map<String, Long> nightWorkerCacheTs = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;

    public CompanionSchedule(PersonaService personaService, SleepModel sleepModel) {
        this.personaService = personaService;
        this.sleepModel = sleepModel;
    }

    /** 理想社会活动时段: 夜班(酒吧) → 18:00-02:00 上班; 普通 → 9-18 上班 */
    private Schedule scheduleFor(String companionId) {
        if (isNightWorker(companionId)) {
            return new Schedule(18, 26);   // 酒吧: 18:00-02:00 上班
        }
        int h = Math.floorMod(companionId == null ? 0 : companionId.hashCode(), 1000);
        int workStart = 8 + h % 3;          // 8-10 点上班
        int workEnd = 17 + (h / 3) % 3;     // 17-19 点下班
        return new Schedule(workStart, workEnd);
    }

    /**
     * 当前活动状态。
     * 睡眠优先: SleepModel 判定在睡 → SLEEP(即使按社会时段应该在上班/休闲)。
     * 未睡 → 按社会活动时段派生。
     */
    public Activity activityFor(String companionId, LocalDateTime now) {
        // 睡眠是 emergent —— 由 SleepModel 决定, 不是时刻表
        if (sleepModel.currentSleeping(companionId, now)) {
            return Activity.SLEEP;
        }
        Schedule s = scheduleFor(companionId);
        int hour = now.getHour();
        boolean weekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean night = isNightWorker(companionId);

        if (night) {
            // 酒吧作息: 白天"睡醒后"到上班前是起床/晚饭, 18 点后上班
            if (hour >= 12 && hour < 16) return Activity.MORNING;
            if (hour >= 16 && hour < 18) return Activity.LUNCH;
            if (hour >= 18 || hour < 2) return Activity.WORK_BUSY;
            if (hour >= 2 && hour < 4) return Activity.EVENING;
            return Activity.LATE_NIGHT;
        }

        if (weekend) {
            // 周末: 睡醒后全天休闲
            if (hour < 8) return Activity.MORNING;
            if (hour >= 22) return Activity.LATE_NIGHT;
            return Activity.LEISURE;
        }

        if (hour < s.workStart()) return Activity.MORNING;
        if (hour >= s.workStart() && hour < 12) return Activity.WORK_BUSY;
        if (hour >= 12 && hour < 14) return Activity.LUNCH;
        if (hour >= 14 && hour < s.workEnd()) return Activity.WORK_AFTERNOON;
        if (hour < s.workEnd() + 1) return Activity.EVENING;
        if (hour < 22) return Activity.LEISURE;
        return Activity.LATE_NIGHT;
    }

    /** 睡眠决策(供消息处理判断她会不会硬撑聊天) */
    public SleepModel.SleepDecision sleepDecision(String companionId, LocalDateTime now,
                                                  double motivation, double socialEngagement) {
        return sleepModel.decideSleep(companionId, now, motivation, socialEngagement);
    }

    /** 生成某一天的时段块序列 */
    public List<TimeBlock> dayBlocks(String companionId, LocalDate date) {
        Schedule s = scheduleFor(companionId);
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        List<TimeBlock> blocks = new ArrayList<>();
        boolean night = isNightWorker(companionId);

        if (night) {
            // 酒吧夜班: 无固定睡眠块(睡眠由 SleepModel emergent 决定), 只排社会活动
            blocks.add(new TimeBlock("MORNING", "起床洗漱", LocalTime.of(12, 0), LocalTime.of(16, 0)));
            blocks.add(new TimeBlock("MEAL", "早晚饭", LocalTime.of(16, 0), LocalTime.of(18, 0)));
            blocks.add(new TimeBlock("WORK", "酒吧上班", LocalTime.of(18, 0), LocalTime.of(23, 59)));
            return blocks;
        }

        // 普通作息: 只排社会活动, 睡眠由 SleepModel 决定
        blocks.add(new TimeBlock("MORNING", "起床洗漱", LocalTime.of(6, 0), LocalTime.of(s.workStart(), 0)));
        if (weekend) {
            blocks.add(new TimeBlock("LEISURE", "悠闲地度过", LocalTime.of(9, 0), LocalTime.of(12, 30)));
            blocks.add(new TimeBlock("MEAL", "午餐", LocalTime.of(12, 30), LocalTime.of(13, 30)));
            blocks.add(new TimeBlock("LEISURE", "看书/散步/见朋友", LocalTime.of(13, 30), LocalTime.of(18, 0)));
            blocks.add(new TimeBlock("MEAL", "晚餐", LocalTime.of(18, 0), LocalTime.of(19, 0)));
            blocks.add(new TimeBlock("LEISURE", "晚间放松", LocalTime.of(19, 0), LocalTime.of(23, 59)));
        } else {
            blocks.add(new TimeBlock("WORK", "上班", LocalTime.of(s.workStart(), 0), LocalTime.of(12, 0)));
            blocks.add(new TimeBlock("MEAL", "午餐", LocalTime.of(12, 0), LocalTime.of(13, 30)));
            blocks.add(new TimeBlock("WORK", "下午工作", LocalTime.of(13, 30), LocalTime.of(s.workEnd(), 0)));
            blocks.add(new TimeBlock("COMMUTE", "下班路上", LocalTime.of(s.workEnd(), 0), LocalTime.of(s.workEnd() + 1, 0)));
            blocks.add(new TimeBlock("MEAL", "晚餐", LocalTime.of(s.workEnd() + 1, 0), LocalTime.of(s.workEnd() + 2, 0)));
            blocks.add(new TimeBlock("LEISURE", "晚间自由时间", LocalTime.of(s.workEnd() + 2, 0), LocalTime.of(23, 59)));
        }
        return blocks;
    }

    /** 此刻她在做什么(注入 Prompt) */
    public String describe(String companionId, String name, LocalDateTime now) {
        if (sleepModel.currentSleeping(companionId, now)) {
            return String.format("现在是%s %s,%s正在睡觉。",
                    weekName(now), now.format(DateTimeFormatter.ofPattern("HH:mm")), name);
        }
        Activity a = activityFor(companionId, now);
        String base;
        if (isNightWorker(companionId)) {
            base = switch (a) {
                case SLEEP -> "正在睡觉";
                case MORNING -> "刚起床,在收拾准备去上班";
                case LUNCH -> "在吃今天的第一顿饭";
                case WORK_BUSY -> "正在酒吧上班,店里正忙";
                case EVENING -> "刚下班,在收拾准备回家";
                case LATE_NIGHT -> "在忙自己的事";
                case LEISURE -> "在悠闲地休息";
                case WORK_AFTERNOON -> "正在忙工作";
            };
        } else {
            base = switch (a) {
                case SLEEP -> "正在睡觉";
                case MORNING -> "刚起床,在收拾准备新的一天";
                case WORK_BUSY -> "正在忙工作";
                case LUNCH -> "在午休";
                case WORK_AFTERNOON -> "还在忙下午的工作";
                case EVENING -> "刚下班,在忙自己的事";
                case LEISURE -> "在悠闲地享受自己的时间";
                case LATE_NIGHT -> "准备休息了";
            };
        }
        return String.format("现在是%s %s,%s%s。",
                weekName(now), now.format(DateTimeFormatter.ofPattern("HH:mm")), name, base);
    }

    private static String weekName(LocalDateTime now) {
        return switch (now.getDayOfWeek()) {
            case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
            case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六"; case SUNDAY -> "周日";
        };
    }

    /** 主动消息倾向: 睡眠中为 0, 忙时低, 休闲高 */
    public double proactiveFactor(String companionId, LocalDateTime now) {
        if (sleepModel.currentSleeping(companionId, now)) return 0.0;
        return switch (activityFor(companionId, now)) {
            case SLEEP -> 0.0;
            case WORK_BUSY, WORK_AFTERNOON -> 0.35;
            case MORNING -> 0.6;
            case LATE_NIGHT -> 0.65;
            case LUNCH, EVENING -> 0.9;
            case LEISURE -> 1.2;
        };
    }

    /** 判断该伴侣是否为夜班工作者(酒吧/夜班/晚班) → chronotype=LATE */
    boolean isNightWorker(String companionId) {
        if (companionId == null) return false;
        long now = System.currentTimeMillis();
        Long cachedTs = nightWorkerCacheTs.get(companionId);
        if (cachedTs != null && now - cachedTs < CACHE_TTL_MS) {
            return Boolean.TRUE.equals(nightWorkerCache.get(companionId));
        }
        boolean night = detectNightWorker(companionId);
        nightWorkerCache.put(companionId, night);
        nightWorkerCacheTs.put(companionId, now);
        return night;
    }

    private boolean detectNightWorker(String companionId) {
        try {
            Persona persona = personaService.getActive(companionId);
            if (persona == null) return false;
            StringBuilder text = new StringBuilder();
            if (persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                text.append(persona.getPersonality().getSummary()).append(' ');
            }
            if (persona.getLife() != null && persona.getLife().getBackground() != null) {
                text.append(persona.getLife().getBackground()).append(' ');
            }
            String s = text.toString();
            if (s.isBlank()) return false;
            return s.contains("酒吧") || s.contains("夜班") || s.contains("晚班")
                    || s.contains("上夜班") || s.contains("夜场") || s.contains("通宵")
                    || s.contains("夜间工作") || s.contains("KTV") || s.contains("夜店");
        } catch (Exception e) {
            return false;
        }
    }
}
