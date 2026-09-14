package com.luxera.companion.sleep;

import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * §3-§9 Sleep Model: 睡眠是 Emergent Behavior, 不是 Schedule。
 *
 * 彻底取消 "if time >= sleepTime → sleeping" 的硬规则。
 * 睡眠由四层决定:
 *   1. Sleep Pressure(Process S): 醒着越久越高(指数逼近), 睡觉时下降
 *   2. Circadian Drive(Process C): 昼夜节律正弦波, 峰值在生物钟深夜
 *   3. Body/Environment: 疲劳/不适/环境信号(困的信号)
 *   4. Motivation/Social: 意志克服睡意(聊天中/有重要事 → 硬撑)
 *
 * Sleep Decision: SLEEP / STAY_AWAKE / DELAY_SLEEP / NAP
 */
@Slf4j
@Service
public class SleepModel {

    /** 睡眠决策 */
    public enum SleepDecision { SLEEP, STAY_AWAKE, DELAY_SLEEP, NAP }

    /** 压力饱和上限 */
    private static final double PRESSURE_MAX = 0.95;
    /** 压力上升时间常数(小时): 醒着约 18h 到接近饱和 */
    private static final double TAU_WAKE_H = 18.0;
    /** 压力下降时间常数(小时): 睡眠约 8h 降到低点 */
    private static final double TAU_SLEEP_H = 8.0;
    /** 入睡倾向阈值 */
    private static final double SLEEP_THRESHOLD = 0.68;
    /** 深夜硬撑阈值(低于此才可能硬撑聊天) */
    private static final double HARD_STAY_AWAKE_THRESHOLD = 0.88;

    private final CircadianStateRepository circadianRepo;
    private final SleepSessionRepository sleepRepo;
    private final AgentStateService agentStateService;
    private final com.luxera.companion.persona.PersonaService personaService;

    public SleepModel(CircadianStateRepository circadianRepo,
                      SleepSessionRepository sleepRepo,
                      AgentStateService agentStateService,
                      com.luxera.companion.persona.PersonaService personaService) {
        this.circadianRepo = circadianRepo;
        this.sleepRepo = sleepRepo;
        this.agentStateService = agentStateService;
        this.personaService = personaService;
    }

    /**
     * 获取/初始化生物钟状态。chronotype 由 companionId 确定性派生(同 作息).
     * LATE 类型天然倾向晚睡晚起。
     */
    @Transactional
    public CircadianState getOrCreate(String companionId, LocalDateTime now) {
        return circadianRepo.findByCompanionId(companionId).orElseGet(() -> {
            CircadianState c = new CircadianState();
            c.setCompanionId(companionId);
            c.setChronotype(chronotypeFor(companionId));
            // LATE 型生物钟偏晚 +1~2h
            double shift = switch (c.getChronotype()) {
                case "EARLY" -> -1.0;
                case "LATE" -> 1.5;
                default -> 0.0;
            };
            c.setCircadianPhaseShift(shift);
            // 新伴侣不是"刚出生", 而是"已生活了一段时间"。
            // 按 chronotype 假定今天已醒来(起床时间), 让睡眠压力从起床起自然积累:
            // 深夜创建的新伴侣也会有合理睡意(而非凌晨还精神)。
            int wakeHour = switch (c.getChronotype()) {
                case "EARLY" -> 6;
                case "LATE" -> 10;
                default -> 7;
            };
            LocalDateTime todayWake = now.toLocalDate().atTime(wakeHour, 0);
            LocalDateTime lastWake = todayWake.isAfter(now) ? todayWake.minusDays(1) : todayWake;
            c.setLastWakeAt(lastWake);
            // 从起床到当前已醒时长 → 初始睡眠压力(指数模型, 上限不高)
            double awakeHours = java.time.Duration.between(lastWake, now).toMinutes() / 60.0;
            double initialPressure = 0.2 + Math.min(0.5, awakeHours / 24.0 * 0.6);
            c.setSleepPressure(Math.max(0.15, Math.min(0.7, initialPressure)));
            c.setSleepDebt(0.1);
            // 深夜创建的新伴侣: 假定她已生活到今天并入睡(而非凌晨还醒着)。
            // 这是"新伴侣已存在一段时间"的初始化, 不违反 emergent —— 后续由 SleepModel 演化。
            // 注意: LATE(夜班/酒吧)类型深夜应保持清醒(她要上班)。
            int hour = now.getHour();
            boolean deepNight = hour >= 22 || hour < 6;
            if (deepNight && c.getSleepPressure() >= 0.45 && !"LATE".equals(c.getChronotype())) {
                c.setSleeping(true);
                c.setSleepStartedAt(now.minusHours(2));   // 已睡约 2 小时
            } else {
                c.setSleeping(false);
            }
            return circadianRepo.save(c);
        });
    }

    /** chronotype: 夜班 persona → LATE; 白天作息 persona → NORMAL; 其余按 id 哈希 */
    private String chronotypeFor(String companionId) {
        // 夜班工作者(酒吧/夜班 persona) → 天然 LATE(晚睡晚起)
        if (companionId != null && isNightWorkerPersona(companionId)) {
            return "LATE";
        }
        // 白天作息(朝九晚五/上班族/早起 persona) → 天然 NORMAL(早睡早起), 不被哈希随机成夜猫子
        if (companionId != null && isDayWorkerPersona(companionId)) {
            return "NORMAL";
        }
        int h = Math.floorMod(companionId == null ? 0 : companionId.hashCode(), 1000);
        return h % 10 < 3 ? "LATE" : (h % 10 < 5 ? "EARLY" : "NORMAL");
    }

    /** 通过 persona 判断是否夜班工作者(酒吧/夜班/晚班) */
    private boolean isNightWorkerPersona(String companionId) {
        try {
            var persona = personaService.getActive(companionId);
            if (persona == null) return false;
            StringBuilder text = new StringBuilder();
            if (persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                text.append(persona.getPersonality().getSummary()).append(' ');
            }
            if (persona.getLife() != null && persona.getLife().getBackground() != null) {
                text.append(persona.getLife().getBackground()).append(' ');
            }
            String s = text.toString();
            return s.contains("酒吧") || s.contains("夜班") || s.contains("晚班")
                    || s.contains("夜场") || s.contains("夜店") || s.contains("通宵");
        } catch (Exception e) {
            return false;
        }
    }

    /** 通过 persona 判断是否白天作息工作者(朝九晚五/上班族/早起) */
    private boolean isDayWorkerPersona(String companionId) {
        try {
            var persona = personaService.getActive(companionId);
            if (persona == null) return false;
            StringBuilder text = new StringBuilder();
            if (persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                text.append(persona.getPersonality().getSummary()).append(' ');
            }
            if (persona.getLife() != null && persona.getLife().getBackground() != null) {
                text.append(persona.getLife().getBackground()).append(' ');
            }
            String s = text.toString();
            return s.contains("朝九晚五") || s.contains("早九晚六") || s.contains("上班族")
                    || s.contains("白班") || s.contains("早起") || s.contains("正常作息")
                    || s.contains("工作日") || s.contains("朝九晚六");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 推进睡眠状态(每 tick 调用)。处理:
     * - 醒着 → sleep_pressure 指数上升
     * - 睡着 → 达到睡眠时长后自然醒
     * - 睡眠结束 → 记录 SleepSession
     */
    @Transactional
    public void tick(String companionId, LocalDateTime now) {
        CircadianState c = getOrCreate(companionId, now);
        if (c.isSleeping()) {
            // 睡眠中: 压力下降
            double elapsedH = hoursSince(c.getSleepStartedAt(), now);
            c.setSleepPressure(pressureDecay(c.getSleepPressure(), elapsedH));
            // 达到足够睡眠(≥6.5h)或压力降到低位 → 自然醒
            if (elapsedH >= 6.5 || c.getSleepPressure() <= 0.15) {
                wakeUp(c, now, "NATURAL");
            }
        } else {
            // 醒着: 压力上升
            double awakeH = hoursSince(c.getLastWakeAt(), now);
            c.setSleepPressure(pressureBuild(c.getSleepPressure(), awakeH));
        }
        circadianRepo.save(c);
        // 同步到 AgentState.sleepiness(供现有下游读取)
        syncSleepiness(companionId, c);
    }

    /** 当前是否在睡眠中 */
    @Transactional(readOnly = true)
    public boolean isSleeping(String companionId, LocalDateTime now) {
        CircadianState c = circadianRepo.findByCompanionId(companionId).orElse(null);
        return c != null && c.isSleeping();
    }

    /**
     * 当前是否在睡眠中(首次访问会初始化)。
     * 供活动判定使用 —— 新伴侣首次访问时初始化生物钟状态(含深夜入睡判定)。
     */
    @Transactional
    public boolean currentSleeping(String companionId, LocalDateTime now) {
        CircadianState c = getOrCreate(companionId, now);
        return c.isSleeping();
    }

    /**
     * 计算综合睡意(sleep_propensity), 0-1。
     * propensity = pressure*0.55 + circadian*0.25 + body*0.15 + environment*0.05 - motivation
     */
    @Transactional(readOnly = true)
    public double sleepPropensity(String companionId, LocalDateTime now,
                                  double motivation, double socialEngagement) {
        CircadianState c = getOrCreate(companionId, now);
        double pressure = c.getSleepPressure();

        // 昼夜节律驱动(Process C): 生物钟深夜最高, 午后小峰(午睡倾向)
        double circadian = circadianDrive(c, now);

        // 身体状态: 疲劳/困倦放大
        AgentState state = agentStateService.get(companionId);
        double body = state != null
                ? Math.max(0, state.getSleepiness() * 0.5 + state.getPhysicalDiscomfort() * 0.5)
                : 0;

        // 动机/社交: 意志克服睡意
        double motivationFactor = Math.max(0, Math.min(1,
                motivation * 0.35 + socialEngagement * 0.3));

        double raw = pressure * 0.7 + circadian * 0.15 + body * 0.1 + 0.05 - motivationFactor;
        return Math.max(0, Math.min(1, raw));
    }

    /**
     * 睡眠决策: 根据睡意 + 动机决定。
     * - propensity 低(<0.45) → STAY_AWAKE
     * - propensity 中高(0.45~0.68) → 有动机则 STAY_AWAKE, 无则 DELAY_SLEEP
     * - propensity 高(>0.68) → SLEEP; 除非动机极强(HARD_STAY_AWAKE)且深夜聊天
     */
    @Transactional(readOnly = true)
    public SleepDecision decideSleep(String companionId, LocalDateTime now,
                                     double motivation, double socialEngagement) {
        double propensity = sleepPropensity(companionId, now, motivation, socialEngagement);
        boolean sleeping = isSleeping(companionId, now);
        if (sleeping) return SleepDecision.SLEEP;

        if (propensity < 0.45) return SleepDecision.STAY_AWAKE;

        // 生物钟午后小峰 + 压力中等 → 可能 NAP(午睡)
        CircadianState c = getOrCreate(companionId, now);
        double circadian = circadianDrive(c, now);
        if (propensity >= 0.45 && propensity < 0.62 && circadian > 0.5) {
            return SleepDecision.NAP;
        }

        if (propensity >= SLEEP_THRESHOLD) {
            // 高睡意: 除非动机极强且睡意未到硬撑极限
            double motivationFactor = Math.max(0, Math.min(1,
                    motivation * 0.35 + socialEngagement * 0.3));
            if (motivationFactor >= 0.75 && propensity < HARD_STAY_AWAKE_THRESHOLD) {
                return SleepDecision.STAY_AWAKE;   // 意志克服睡意(场景3)
            }
            if (motivationFactor >= 0.75) {
                return SleepDecision.DELAY_SLEEP;  // 延迟睡(还要硬撑一会)
            }
            return SleepDecision.SLEEP;
        }
        return SleepDecision.DELAY_SLEEP;
    }

    /** 入睡: 记录 sleep_session 开始 */
    @Transactional
    public void fallAsleep(String companionId, LocalDateTime now, String cause) {
        CircadianState c = getOrCreate(companionId, now);
        if (c.isSleeping()) return;
        c.setSleeping(true);
        c.setSleepStartedAt(now);
        circadianRepo.save(c);

        SleepSession s = new SleepSession();
        s.setCompanionId(companionId);
        s.setStartTime(now);
        s.setSleepType("NORMAL");
        s.setCause(cause);
        sleepRepo.save(s);
    }

    /** 醒来: 关闭 sleep_session, 重置压力, 记录时长 */
    @Transactional
    public void wakeUp(CircadianState c, LocalDateTime now, String cause) {
        if (!c.isSleeping()) return;
        c.setSleeping(false);
        c.setLastWakeAt(now);
        circadianRepo.save(c);

        sleepRepo.findByCompanionIdOrderByStartTimeDesc(c.getCompanionId())
                .stream().filter(s -> s.getEndTime() == null).findFirst()
                .ifPresent(s -> {
                    s.setEndTime(now);
                    s.setDurationMinutes((int) Duration.between(s.getStartTime(), now).toMinutes());
                    s.setSleepQuality(quality(c.getSleepPressure()));
                    s.setCause(cause);
                    sleepRepo.save(s);
                });
    }

    /** 睡眠质量: 睡眠时长足够 + 醒来时压力低 → 质量高 */
    private static double quality(double wakePressure) {
        return Math.max(0.3, Math.min(1.0, 0.9 - wakePressure));
    }

    /** Process S 压力上升: 指数逼近饱和 */
    private static double pressureBuild(double current, double awakeHours) {
        if (awakeHours <= 0) return current;
        // S(t) = Smax - (Smax-S0)*e^(-t/tau)
        double target = PRESSURE_MAX - (PRESSURE_MAX - current) * Math.exp(-awakeHours / TAU_WAKE_H);
        return Math.max(current, Math.min(PRESSURE_MAX, target));
    }

    /** Process S 压力下降: 指数衰减 */
    private static double pressureDecay(double current, double sleepHours) {
        if (sleepHours <= 0) return current;
        double target = current * Math.exp(-sleepHours / TAU_SLEEP_H);
        return Math.max(0.05, Math.min(current, target));
    }

    /** Process C 昼夜节律: 生物钟 03:00 睡意峰值, 22:00-06:00 处于高睡意区; 午后 13-16 有午睡小峰 */
    private static double circadianDrive(CircadianState c, LocalDateTime now) {
        double bioHour = (now.getHour() + now.getMinute() / 60.0 + 24 + c.getCircadianPhaseShift()) % 24;
        // 用 cos: 03:00 处 = cos(0) = 1(最困); 23:00 处 = cos(5.24) ≈ 0.5(已明显困)
        double nightSleepiness = Math.max(0, Math.cos((bioHour - 3) / 24 * 2 * Math.PI));
        // 午后小峰(13-16点): 午睡倾向
        double afternoonNap = (bioHour >= 13 && bioHour <= 16) ? 0.35 : 0;
        return Math.max(0, Math.min(1, nightSleepiness * 0.8 + afternoonNap));
    }

    private static double hoursSince(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) return 0;
        return Duration.between(from, to).toMinutes() / 60.0;
    }

    /** 同步 sleepiness 到 AgentState(现有下游读取) */
    private void syncSleepiness(String companionId, CircadianState c) {
        try {
            AgentState s = agentStateService.getOrCreate(companionId);
            s.setSleepiness(c.isSleeping() ? Math.min(1.0, 0.4 + c.getSleepPressure())
                    : Math.max(0, c.getSleepPressure()));
            agentStateService.save(s);
        } catch (Exception ignored) {
        }
    }
}
