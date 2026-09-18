package com.luxera.companion.world.device;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.5 —— <b>振动系统</b>: 一台设备用"抖一下"来触达她。
 *
 * <h2>为什么它不是 {@link AudioSystem} 里的一个布尔字段</h2>
 * 因为它与声音<b>正交</b>, 而正交性在这条链上有真实后果:
 * <pre>
 *   静音 + 振动 → 她可能察觉(手机在兜里, 她正低头看文件)
 *   静音 + 不震 → 她几乎肯定不察觉
 *   响铃 + 振动 → 两种通道同时到, 而她会先被声音抓走
 *   没电        → 两件事都不发生(见 {@link NotificationSystem.Default#receive} 的第 ① 步)
 * </pre>
 * 把这四种情形压进一个字段, 会得到一个无法表达"静音但她察觉到了"的类型 ——
 * 而那个状态恰恰是"她为什么回了消息"最常见的解释。
 *
 * <h2>它<b>不投递</b>世界事件 —— 与 {@code AudioSystem} 的 {@code NOTIFICATION} 通道同一条纪律</h2>
 * 振动的世界陈述由 {@link NotificationSystem} 完成, 因为只有它同时知道
 * "震了没有""响没响""几条信号"。若本类也投一条 {@code device.phone.vibrated.v1},
 * 那么一次"静音来电"会在她的触觉里产生两条刺激 ——
 * 一条来自本类, 一条来自通知事件(它带着 {@code vibration=true})。
 *
 * <p>这不是抽象洁癖: {@code RealtimeEventQueue} 会为每一条刺激分配注意力预算,
 * 重复的刺激会<b>挤掉真正该被注意到的那一条</b>。
 *
 * <h2>谁该调用它</h2>
 * {@link NotificationSystem}(通知到点)、{@link Phone}(来电、她按下的"确认"反馈)。
 * <b>不</b>由应用直接调用: 应用说"我有新消息", 至于震动还是响铃是<b>设备</b>的决定,
 * 见 {@link Device#notify(NotificationRequest)}。
 */
public interface VibrationSystem {

    /** 振动开关 —— 她在设置里关掉它, 或某个 Action 关掉它。 */
    boolean isEnabled();

    void setEnabled(boolean enabled);

    /** 正在执行的振动模式; 没在震时为 {@code null}。 */
    VibrationPattern currentPattern();

    /**
     * 抖一下 —— <b>时刻由调用方给</b>。
     *
     * <h2>为什么时刻是参数而不是从系统时钟读</h2>
     * 仿真世界里时间可以加速十倍, 也可以用一条时间线重放。若本类自己去读
     * {@code System.currentTimeMillis()}, 那么"上一条振动还没结束"这个判断在重放时
     * 会得到与首次运行<b>不同的结果</b> —— 而一次不可重放的仿真不是仿真。
     *
     * <p>这里<b>刻意没有</b>"无时刻的重载"。曾经有过一个, 它内部写的是
     * {@code vibrate(pattern, Instant.now())} —— 而那段代码与上面这段论证直接矛盾:
     * 同一份文件里, 一段文字说"读系统时钟不可重放", 一个方法在读系统时钟。
     * 留一个"方便调用方"的墙钟重载, 结局一定是<b>便利的那个被用得多</b>,
     * 而重放悄悄失效。所以正确的做法不是"加个注释说明它是妥协", 而是<b>删掉它</b>:
     * 调用方总有一个时刻可给 —— {@link NotificationSystem} 手里有
     * {@code NotificationRequest.occurredAt()}, {@link Phone} 手里有这一 tick 的
     * {@code now}, 而她按下的确认反馈发生在她行动的同一刻。
     *
     * @param at 这次振动的时刻
     * @return <b>真的震了</b>才返回 true。关闭状态、或上一次还没结束, 都返回 false ——
     *         调用方(通知系统)要把这个值原样写进
     *         {@code device.phone.notification-raised.v1} 的 {@code vibration} 字段。
     *         返回 {@code void} 会让调用方自己猜, 而猜错的后果是
     *         "她以为震动过, 其实没有"
     */
    boolean vibrate(VibrationPattern pattern, java.time.Instant at);

    /**
     * 振动到她那里还剩多少 —— 与 {@link Device.Proximity#factorOf} 相乘之前的"通道灵敏度"。
     *
     * <p>它用于两个地方: {@code notification-raised} 的 {@code perceptibility},
     * 以及来电是否该升级成"持续振动"。
     *
     * <p><b>为什么振动也需要一个位置因子, 而不是"震了就一定感觉得到"</b>:
     * 因为振动传递需要<b>接触</b>。手机在另一个房间的桌上震, 她没有接触面,
     * 物理上就是感觉不到。若振动忽略位置, 那么"她睡觉时手机在客厅"这个场景里
     * 静音来电会变成"她一定醒了" —— 而那与用户设计的场景直接矛盾。
     */
    double perceptibility(String proximity);

    /** 振动系统状态的快照 —— 进 {@code Device.state()} 与诊断面板。 */
    Map<String, Object> state();

    // ═══════════════════════════ 模式 ═══════════════════════════

    /**
     * 振动模式 —— <b>固定枚举, 三条判别全部成立</b>。
     *
     * <ol>
     *   <li>取值由<b>硬件与操作系统</b>决定: Android 的 {@code VibrationEffect.createPredefined}
     *       自 API 26 起就是这几个固定波形, 第三方应用只能调用它们, 不能发明新的;</li>
     *   <li>新增一个取值<b>不改变 Agent 能做什么</b> —— 她能做的事是"让它震一下",
     *       而不是"发明一种新的震动方式";</li>
     *   <li>它不是"世界上有哪几种通知": 第三方扩展的是自己发什么通知
     *       ({@link NotificationRequest#reason()}), 不是把手机震得更花。</li>
     * </ol>
     *
     * <p>与 {@link AudioChannel} 同一个判据, 与 {@code NotificationRequest.reason} 的
     * "为什么是字符串"形成对照 —— 两者放在一起正好说明 P4 的判别标准不是"枚举坏、字符串好",
     * 而是<b>这件事的取值集合会不会随生态长大</b>。
     */
    public enum VibrationPattern {

        /** 短促一下 —— 通知的最低配。 */
        SHORT("短震", 120, 0.6),

        /** 连震两下 —— 通知的默认, 比单次更不容易被忽略。 */
        SHORT_DOUBLE("双短震", 260, 0.75),

        /** 长震 —— 来电。持续且明显, 因为来电不该被错过。 */
        LONG("长震", 1200, 1.0),

        /** 心跳式 —— 提醒类(闹钟、日程), 有节奏感。 */
        HEARTBEAT("心跳", 900, 0.9);

        private final String label;
        private final long durationMillis;
        private final double strength;

        VibrationPattern(String label, long durationMillis, double strength) {
            this.label = label;
            this.durationMillis = durationMillis;
            this.strength = strength;
        }

        public String label() {
            return label;
        }

        public long durationMillis() {
            return durationMillis;
        }

        /**
         * 强度 —— {@code [0, 1]}。它<b>不是</b>她设置里的那个开关:
         * 开关是"震不震", 强度是"震得多明显"。两者都存在是因为真实手机就是这样
         * (iOS 的"触感强度"、Android 的振动幅度)。
         */
        public double strength() {
            return strength;
        }

        /** 平台认得的全部模式 —— 给诊断与三方设备作者看。 */
        public static List<VibrationPattern> known() {
            return List.of(values());
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 默认实现: 一个布尔开关 + 当前模式 + 接触灵敏度表。
     *
     * <h2>它为什么把"位置"这件事复制了一份</h2>
     * {@link Device.Proximity} 里已经有一张感知因子表了, 这里又写了一张
     * (振动版)。看起来像重复, 但两张表的值<b>刻意不同</b>:
     * <pre>
     *   位置        声音因子    振动因子
     *   手上          1.00        1.00
     *   桌上          0.75        0.85     ← 桌面是很好的共振体
     *   包里          0.30        0.20     ← 布料把声闷住, 也把震闷住
     *   另一个房间     0.05        0.00     ← 没有接触面, 物理上不可能感觉到
     * </pre>
     * 如果强行合成一张表, 就必须在"桌面共振"与"包内闷震"之间取一个折中值,
     * 而那个折中值会在两个方向上同时出错。两张表各自回答自己那一半问题,
     * 而<b>合并它们省下的代码行数远小于合并造成的误差</b>。
     */
    @Slf4j
    final class Default implements VibrationSystem {

        private boolean enabled;
        private VibrationPattern current;

        /** 当前振动结束的仿真时刻 —— 用来实现"上一次没结束就不再震"。 */
        private java.time.Instant busyUntil;

        public Default(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean nowEnabled) {
            if (this.enabled == nowEnabled) {
                return;
            }
            this.enabled = nowEnabled;
            log.info("[VibrationSystem] 振动开关: {}", nowEnabled ? "开" : "关");
        }

        @Override
        public VibrationPattern currentPattern() {
            return current;
        }

        @Override
        public boolean vibrate(VibrationPattern pattern, java.time.Instant at) {
            Objects.requireNonNull(pattern, "振动模式不能为空 —— 想表达'不震'请用 isEnabled() 判断");
            Objects.requireNonNull(at, "振动必须带发生时刻 —— 仿真时钟下不许读墙上时钟");
            if (!enabled) {
                // DEBUG 而不是 INFO: 一个关掉振动的手机每收到一条通知就写一行 INFO,
                // 会把"她为什么没察觉"这件事的日志噪音放大十倍
                log.debug("[VibrationSystem] 振动已关闭, 忽略 {}", pattern.label());
                return false;
            }
            if (busyUntil != null && at.isBefore(busyUntil)) {
                // 真实手机的行为: 上一条振动还没走完, 新的会打断它或叠加。
                // 这里选择"忽略"而不是"打断": 打断会让一段长震(来电)被一条
                // 短震(通知)截断, 而那正是"来电被漏掉"的机制
                log.debug("[VibrationSystem] 上一个振动({})尚未结束, 忽略 {}",
                        current == null ? "?" : current.label(), pattern.label());
                return false;
            }
            this.current = pattern;
            this.busyUntil = at.plusMillis(pattern.durationMillis());
            log.debug("[VibrationSystem] 振动: {} ({}ms, 强度 {})",
                    pattern.label(), pattern.durationMillis(), pattern.strength());
            // 刻意<b>不</b>在这里投任何事件 —— 见接口注释的第一条纪律
            return true;
        }

        @Override
        public double perceptibility(String proximity) {
            if (!enabled) {
                return 0.0;
            }
            double byProximity = contactFactorOf(proximity);
            return byProximity * strengthOf(current);
        }

        /**
         * 每个位置的<b>接触因子</b> —— 见默认实现类注释里的那张两表对照。
         *
         * <p>{@code OTHER_ROOM} 给的是 0.0 而不是一个很小的正数, 这与声学的 0.05
         * 刻意不同: 声音可以穿墙(哪怕很弱), 而振动需要物理接触, 隔着一个房间
         * 就是感觉不到。把这两个数都设成"很小的正数"会让两者看起来是同一件事,
         * 而它们不是。
         */
        private double contactFactorOf(String proximity) {
            if (proximity == null) {
                // 未知位置的回退与声学保持一致: 让"她可能感觉得到"比"确定感觉不到"安全
                return Device.Proximity.UNKNOWN_FACTOR;
            }
            return switch (proximity) {
                case Device.Proximity.HAND -> 1.0;
                case Device.Proximity.DESK -> 0.85;
                case Device.Proximity.BAG -> 0.2;
                case Device.Proximity.OTHER_ROOM -> 0.0;
                default -> Device.Proximity.UNKNOWN_FACTOR;
            };
        }

        private double strengthOf(VibrationPattern pattern) {
            return pattern == null ? VibrationPattern.SHORT_DOUBLE.strength() : pattern.strength();
        }

        /**
         * 振动状态的快照。
         *
         * <p>用 {@code LinkedHashMap} + {@code Collections.unmodifiableMap} 而不是
         * {@code Map.copyOf}: 后者不保证迭代顺序, 而诊断面板的两次快照要做 diff。
         */
        @Override
        public Map<String, Object> state() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("vibrationEnabled", enabled);
            snapshot.put("currentPattern", current == null ? null : current.name());
            return java.util.Collections.unmodifiableMap(snapshot);
        }
    }
}
