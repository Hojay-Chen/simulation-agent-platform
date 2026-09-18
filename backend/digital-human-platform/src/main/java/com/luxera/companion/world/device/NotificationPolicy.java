package com.luxera.companion.world.device;

import java.util.Objects;

/**
 * V2.2 §4.3.4 —— <b>本机的通知策略</b>: 收到信号之后<b>怎么响</b>。
 *
 * <h2>它<b>不</b>决定什么: 要不要发通知信号</h2>
 * 这是整条链上最容易做错的一处分工, 文档 §6.2 用一整张表把它钉住了:
 *
 * <table border="1">
 *   <tr><th>层</th><th>归属</th><th>决定什么</th><th>例子</th></tr>
 *   <tr>
 *     <td>{@code ConversationNotificationSetting.muted}</td>
 *     <td><b>聊天平台</b>(它自己的用户设置)</td>
 *     <td>要不要<b>发</b>这个通知信号</td>
 *     <td>"这个群我免打扰了" → 平台一条信号都不发, 手机根本没收到东西</td>
 *   </tr>
 *   <tr>
 *     <td><b>本 record</b></td>
 *     <td><b>她的手机</b></td>
 *     <td>收到信号后<b>怎么响</b></td>
 *     <td>"我在开会, 手机静音了" → 信号发了, 手机没响</td>
 *   </tr>
 * </table>
 *
 * <p><b>两者都必须存在。</b>把免打扰判定搬到手机侧会得到一个错误的模型: 手机侧不知道
 * 会话的粒度(她免打扰的是某个群, 不是整个聊天软件), 而聊天平台侧不知道她的物理处境。
 * 各判各的之后, "她没听见"这个状态是<b>可以被解释</b>的: 要么平台没发, 要么手机没响 ——
 * 两句话指向两个不同的、都可查证的事实。
 *
 * <h2>{@link RingerMode} 为什么可以是枚举</h2>
 * 与 {@link AudioChannel} 同一个判别: Android 的 {@code AudioManager} 从 API 1 起
 * 就只有 {@code RINGER_MODE_NORMAL / VIBRATE / SILENT} 三个值, 它是<b>硬件与操作系统的
 * 固定分类</b>, 而不是"我们能想到几种通知方式"。新增一种响铃模式不会改变 Agent
 * 能做什么, 第三方也不需要扩展它 —— 第三方扩展的是"我发什么通知",
 * 那是 {@link NotificationRequest#reason()} 那个开放字符串的事。
 */
public record NotificationPolicy(RingerMode ringer, boolean vibrationEnabled, boolean wakeScreen) {

    /**
     * 响铃模式 —— 固定三值, 见类注释。
     *
     * <p>它同时约束声音与振动, 但不约束亮屏: 真实手机在静音模式下<b>仍然会亮屏</b>,
     * 而"她正好在看手机"是一个真实会发生的情况。把三者压成一个模式会让
     * "静音但她看见了"这个状态无法表达。
     */
    public enum RingerMode {

        /** 正常 —— 会出声。 */
        SOUND("响铃"),

        /** 仅振动 —— 不出声, 但会震。 */
        VIBRATE("振动"),

        /** 静音 —— 既不出声也不震(亮屏仍可能)。 */
        SILENT("静音");

        private final String label;

        RingerMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 出厂默认: 正常响铃 + 振动 + 亮屏。 */
    public static final NotificationPolicy DEFAULT =
            new NotificationPolicy(RingerMode.SOUND, true, true);

    /** 开会/睡觉时她自己会按的那个: 静音, 不震, 但还会亮屏。 */
    public static final NotificationPolicy MEETING =
            new NotificationPolicy(RingerMode.SILENT, false, true);

    public NotificationPolicy {
        Objects.requireNonNull(ringer, "响铃模式不能为空 —— 想表达'正常'请用 RingerMode.SOUND");
    }

    // ─────────────────────────── 构造 ───────────────────────────

    public static NotificationPolicy of(RingerMode ringer) {
        return new NotificationPolicy(ringer, ringer != RingerMode.SILENT, true);
    }

    public static NotificationPolicy of(RingerMode ringer, boolean vibration, boolean wakeScreen) {
        return new NotificationPolicy(ringer, vibration, wakeScreen);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 允许出声吗。
     *
     * <p>{@link RingerMode#VIBRATE} 与 {@link RingerMode#SILENT} 都为 false ——
     * 而她<b>仍然可能听见</b>, 如果手机在桌上震得响。那条路径由
     * {@link VibrationSystem} 与 {@code perceptibility} 表达, 不在这里。
     */
    public boolean soundAllowed() {
        return ringer == RingerMode.SOUND;
    }

    /** 允许振动吗 —— 静音模式下不管这个字段怎么设都不震。 */
    public boolean vibrationAllowed() {
        return ringer != RingerMode.SILENT && vibrationEnabled;
    }

    /** 允许亮屏吗 —— 与响铃模式<b>无关</b>, 见 {@link RingerMode} 的注释。 */
    public boolean wakeScreenAllowed() {
        return wakeScreen;
    }

    /**
     * 这个策略下她能不能察觉到一条通知。
     *
     * <p>声与震都不行时, 通知在她那里等于没发生 —— 但<b>未读数仍然 +1</b>
     * (她打开聊天软件时会看到红点)。这两件事必须分开, 见
     * {@link NotificationSystem} 的说明。
     */
    public boolean perceptible() {
        return soundAllowed() || vibrationAllowed();
    }

    public NotificationPolicy withRinger(RingerMode mode) {
        return new NotificationPolicy(mode, vibrationEnabled, wakeScreen);
    }

    /** 一行摘要 —— 诊断面板与日志用。 */
    public String describe() {
        return ringer.label() + (vibrationAllowed() ? "+振动" : "") + (wakeScreen ? "+亮屏" : "");
    }
}
