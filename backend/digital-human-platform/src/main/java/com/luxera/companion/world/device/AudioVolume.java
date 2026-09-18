package com.luxera.companion.world.device;

import java.util.Objects;

/**
 * V2.2 §4.3.3 —— <b>音量是一个 0–1 的连续值, 不是一个 {@code LOUD/QUIET/SILENT} 枚举</b>。
 *
 * <h2>为什么不能用枚举</h2>
 * 真实手机的音量是 16 级(iOS)或 15 级(Android), 而且是一个连续滑块。用四档枚举会立刻
 * 撞上一个答不上来的问题:
 * <pre>
 *   她的通知音量从 0.6 调到 0.53 —— 这算 NORMAL 还是 QUIET?
 *   算 NORMAL: "她调低了一格"这个动作在系统里消失了
 *   算 QUIET : 那么 0.53 和 0.31 变成同一件事, 而它们听起来完全不同
 * </pre>
 * 而这个数值最终要参与<b>乘法</b>(手机音量 0.6 × 应用的基础音量 1.0 = 有效响度 0.6),
 * 乘法要求它先是一个数。把连续量压成离散标签, 再在需要算的时候把它猜回来,
 * 是这类系统里最常见的一种自找麻烦。
 *
 * <h2>{@link #isMuted()} 的阈值为什么不是 0</h2>
 * 因为浮点音量在真实链路里会经历多次缩放(通道音量 × 应用基础音量 × 系统缩放),
 * 一个"她明明调到 0 了却还会响一声"的手机是不可接受的。所以 {@code <= 0.001} 视为静音 ——
 * 这个阈值是<b>一条防浮点误差的边界</b>, 不是一条关于"多小声算安静"的产品判断。
 *
 * <h2>不可变</h2>
 * 它是 {@code record}。{@code setVolume} 返回新值而不是原地改 —— 音量值会被写进事件载荷、
 * 被快照、被测试断言, 一个可变的音量会让"她当时设的是多少"变成一个只有当前值的问题。
 */
public record AudioVolume(double level) {

    /** 完全静音。 */
    public static final AudioVolume MUTED = new AudioVolume(0.0);

    /** 最大音量。 */
    public static final AudioVolume MAX = new AudioVolume(1.0);

    /**
     * 静音判定的容差。
     *
     * <p>见类注释: 它防的是浮点误差, 不是产品语义。调大到 0.05 会让"她把音量调到很低"
     * 变成"静音", 而那是一次真实的设置被系统悄悄改写。
     */
    public static final double MUTE_EPSILON = 0.001;

    public AudioVolume {
        if (Double.isNaN(level) || level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException(
                    "音量必须在 0..1 之间, 收到 " + level + " —— "
                            + "越界的音量会在乘法链里把有效响度算成负数或大于 1, "
                            + "而那个数最终会以刺激强度的形式进入她的感知");
        }
    }

    // ─────────────────────────── 构造 ───────────────────────────

    public static AudioVolume of(double level) {
        return new AudioVolume(level);
    }

    /** 百分比构造 —— 界面与配置里写 60 比写 0.6 自然。 */
    public static AudioVolume ofPercent(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("音量百分比必须在 0..100 之间, 收到 " + percent);
        }
        return new AudioVolume(percent / 100.0);
    }

    // ─────────────────────────── 运算 ───────────────────────────

    /**
     * 乘以一个系数 —— <b>有效响度就是这么算出来的</b>。
     *
     * <p>例: 通道音量 0.6 × 应用的基础音量 1.0 = 0.6; 通道音量 0.6 × 一条"轻提示音"的
     * 基础音量 0.4 = 0.24。结果被夹紧在 0..1, 因为"更响"不该能突破扬声器的上限 ——
     * 一个能超过 1 的响度会让下游的归一化假设全部失效。
     */
    public AudioVolume scaled(double factor) {
        if (Double.isNaN(factor) || factor < 0) {
            throw new IllegalArgumentException("音量系数不能为负, 收到 " + factor);
        }
        return new AudioVolume(Math.min(1.0, level * factor));
    }

    /** 调到最大 —— "她把通知音量拉满"是一个真实会发生的动作。 */
    public AudioVolume atMax() {
        return MAX;
    }

    // ─────────────────────────── 读取 ───────────────────────────

    public boolean isMuted() {
        return level <= MUTE_EPSILON;
    }

    public boolean isMax() {
        return level >= 1.0 - MUTE_EPSILON;
    }

    /** 百分比形式 —— 给界面与日志用, 不参与计算。 */
    public int percent() {
        return (int) Math.round(level * 100);
    }

    /**
     * 这条音量能不能让声音传到她那里。
     *
     * <p>与 {@link #isMuted()} 的区别: 后者回答"扬声器会不会出声", 本方法回答
     * "出了声她听不听得见"。当前实现里两者等价 —— 保留这个区分的<b>位置</b>是为了
     * 让将来的"耳机模式"(扬声器出声但她反而听得更清楚)有一个不用改所有调用点的落点。
     */
    public boolean audible() {
        return !isMuted();
    }

    /** {@code 0.6 (60%)} —— 日志里这个写法比裸 {@code 0.6} 更容易被人读对。 */
    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%.2f (%d%%)", level, percent());
    }

    /** 非空的便捷判定 —— 供可选音量的默认值用。 */
    public static AudioVolume orDefault(AudioVolume volume, AudioVolume fallback) {
        Objects.requireNonNull(fallback, "回退音量不能为空");
        return volume == null ? fallback : volume;
    }
}
