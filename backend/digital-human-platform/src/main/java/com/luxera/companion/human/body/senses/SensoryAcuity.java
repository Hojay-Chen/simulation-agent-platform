package com.luxera.companion.human.body.senses;

import java.util.Objects;

/**
 * V2.2 §3.2.2 —— <b>一条感官通道的灵敏度</b>: "多大的原始信号才算被感觉到"。
 *
 * <h2>为什么灵敏度是一个独立的值对象, 而不是通道里的几个 double 字段</h2>
 * 因为它是<b>要被换掉的那一件东西</b>。用户对这套感官层的原话是:
 * <blockquote>
 *   这些可以很好在 world 中把各种信号输入给 agent, 而且我们当前这些信号都是基于
 *   我们程序仿真的世界, 将来甚至可以将 human 概念接入到我们的真实机器人
 * </blockquote>
 * 也就是说, 这套代码有<b>两个</b>使用场景, 而它们的差别只在这一处:
 * <table border="1">
 *   <tr><th></th><th>仿真世界(今天)</th><th>真实机器人(将来)</th></tr>
 *   <tr>
 *     <td>原始信号从哪来</td>
 *     <td>{@code Phone.AudioSystem} 造出来的一个 {@code Sound} 值对象</td>
 *     <td>麦克风阵列 → PCM → 声压级/主频</td>
 *   </tr>
 *   <tr>
 *     <td><b>灵敏度</b></td>
 *     <td>人耳的一组标定常数</td>
 *     <td>人耳常数, 或者<b>这只机器人自己的</b>传感器规格(灵敏度、量程、信噪比)</td>
 *   </tr>
 *   <tr>
 *     <td>通道代码</td>
 *     <td colspan="2" style="text-align:center"><b>完全一样</b></td>
 *   </tr>
 * </table>
 * 把灵敏度收进一个可整体替换的值对象, 是让上表最后一行成立的全部代价 ——
 * 而这个代价是零, 因为 {@link SensoryChannel#adapt(SensoryAcuity)} 本来就需要它
 * (嗅觉适应、暗适应都是"灵敏度随时间漂移")。
 *
 * <h2>四个数各自的含义, 以及它们为真实传感器预留的位置</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>含义</th><th>真实传感器上对应什么</th></tr>
 *   <tr>
 *     <td>{@link #detectionThreshold()}</td>
 *     <td><b>绝对阈限</b>: 低于它的信号等于不存在。人耳约 0 dB SPL, 人眼约 0.001 lux</td>
 *     <td>传感器的<b>噪底</b>。低于噪底的读数不该被当成"她感觉到了一个很小的东西"
 *         —— 那是把噪声当信号, 而它会让机器人在安静的房间里看见鬼</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #saturation()}</td>
 *     <td><b>饱和点</b>: 到这个强度之后感知不再增强。120 dB 的爆炸声和 140 dB 的
 *         对她而言一样响 —— 都是"震聋了"</td>
 *     <td>传感器的<b>量程上限</b>。没有它, 一个 100 倍的读数会把刺激强度算出 100,
 *         而强度是要进 {@code RealtimeEventQueue} 排序的</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #gain()}</td>
 *     <td>通道的<b>放大倍数</b>。一个在嘈杂环境里长大的人对细微声音更敏感, 就是 gain 高</td>
 *     <td>前置放大器的增益, 或者标定出的个体差异</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #justNoticeableDifference()}</td>
 *     <td><b>最小可觉差</b>(韦伯分数): 变化小于它的两次信号, 她分不出是"变了"还是"没变"</td>
 *     <td>传感器的<b>分辨率</b>。这是"她为什么没注意到天气在变冷"的答案 ——
 *         每 tick 掉 0.001 的保暖值确实没到能被察觉的程度</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么不把 {@code unit} 做成枚举</h2>
 * 单位是给人看的(<b>可读性</b>), 不是给分支用的。谁也不会写
 * {@code switch (acuity.unit()) { case DB -> ... } } —— 因为响度、照度、浓度的
 * 换算方式各不相同, 而那是<b>通道</b>的事({@link SensoryChannel} 的子类各自解释
 * 自己的量纲), 不是这个值对象的事。做成枚举只会诱使人在错误的地方写一次 switch。
 *
 * <p>它存在是为了让日志与诊断面板能打出 {@code "音量 62.0 dB"} 而不是 {@code "62.0"} ——
 * 一个不带单位的数在调试"她为什么没听见"时是没用的。
 */
public record SensoryAcuity(double detectionThreshold,
                            double gain,
                            double saturation,
                            double justNoticeableDifference,
                            double latencyMillis,
                            String unit) {

    /** 传导延迟的上限 —— 见 {@link #latencyMillis()}。 */
    public static final double MAX_PLAUSIBLE_LATENCY_MILLIS = 10_000.0;

    public SensoryAcuity {
        Objects.requireNonNull(unit, "灵敏度的单位不能为空 —— 一个不带单位的阈值在诊断面板上无法解释任何事");
        if (unit.isBlank()) {
            throw new IllegalArgumentException("灵敏度的单位不能是空白串");
        }
        if (gain <= 0.0) {
            throw new IllegalArgumentException(
                    "通道增益必须为正, 收到 " + gain + " —— 零增益的通道永远收不到任何东西, "
                            + "而它的表现是'她聋了'却没有任何报错");
        }
        if (saturation <= detectionThreshold) {
            throw new IllegalArgumentException(
                    "饱和点(" + saturation + ")必须高于绝对阈限(" + detectionThreshold + ") —— "
                            + "否则这条通道的动态范围是空的, 任何信号都会被算成满强度");
        }
        if (justNoticeableDifference < 0.0) {
            throw new IllegalArgumentException(
                    "最小可觉差不能为负, 收到 " + justNoticeableDifference + " —— "
                            + "负的最小可觉差意味着'变化越小越容易被察觉'");
        }
        if (latencyMillis < 0.0 || latencyMillis > MAX_PLAUSIBLE_LATENCY_MILLIS) {
            throw new IllegalArgumentException(
                    "传导延迟必须在 [0, " + MAX_PLAUSIBLE_LATENCY_MILLIS + "] 毫秒内, 收到 " + latencyMillis
                            + " —— 超过 10 秒的'神经传导'不是延迟, 是把事件顺序搞乱");
        }
    }

    // ─────────────────────────── 感知计算 ───────────────────────────

    /**
     * 把一个原始读数衰减成<b>感知强度</b> {@code [0, 1]}。
     *
     * <p>变换是"先减阈限, 再归一化到动态范围, 再乘增益, 最后截顶":
     * <pre>{@code
     *   raw <= threshold        → 0.0     (低于阈限 = 不存在, 不是"很小的值")
     *   threshold < raw < sat   → gain * (raw - threshold) / (sat - threshold)
     *   raw >= saturation       → 1.0     (饱和, 再大也一样)
     * }</pre>
     *
     * <h3>为什么低于阈限要返回 0 而不是一个很小的正数</h3>
     * 因为下游对它做的事完全不同。一个 {@code 0.001} 的感知强度会:
     * <ol>
     *   <li>在 {@code RealtimeEventQueue} 里占一个槽位(它按 salience 排序, 不是按"是否为零"过滤);</li>
     *   <li>在行为分析里表现为"她察觉到了 —— 只是很微弱", 而真相是<b>她什么都没感觉到</b>。</li>
     * </ol>
     * 这两件事的差别在仿真研究里是致命的: 前者会让人去调参"为什么她反应这么弱",
     * 后者才是正确答案"因为那条信号根本没到她面前"。
     *
     * <h3>为什么线性而不是分贝/对数</h3>
     * 人耳对响度的感知确实近似对数。但本方法返回的是<b>给队列排序用的强度</b>, 不是
     * 心理物理实验里的主观响度 —— 而仿真里更需要的是"强度可预测、可解释"。
     * 需要对数的时候, 那个变换属于具体通道的 {@code interpret()}(比如听觉通道可以
     * 自己把 dB 换成 phon), 不属于这个共用的衰减函数。
     */
    public double perceivedIntensity(double rawMagnitude) {
        if (rawMagnitude <= detectionThreshold) {
            return 0.0;
        }
        if (rawMagnitude >= saturation) {
            return 1.0;
        }
        double normalized = (rawMagnitude - detectionThreshold) / (saturation - detectionThreshold);
        return Math.min(1.0, gain * normalized);
    }

    /**
     * 这个读数够不够格被感知到。
     *
     * <p>与 {@code perceivedIntensity(raw) > 0} 等价, 但<b>语义更清楚</b> ——
     * 通道在 {@code receive} 里做的第一个判断是"收不收得到", 不是"算出来是多少"。
     */
    public boolean isDetectable(double rawMagnitude) {
        return rawMagnitude > detectionThreshold;
    }

    /**
     * 从 {@code previousRaw} 变到 {@code currentRaw}, 她能不能察觉出"变了"。
     *
     * <h3>这条判断服务的正是用户要的那条链</h3>
     * "变化产生的 diff 也能形成一个实时 event" —— 但 diff 不是她<b>一定能</b>察觉的。
     * 环境降温 0.01℃ 是一次真实的 diff, 而没有人会因为室温变了 0.01 度而觉得冷。
     * 所以阈值检测的第二条触发路径(diff 速率)必须先过这道门, 否则它会把
     * "每 tick 都有一点点变化"变成"每 tick 都有一条实时事件"。
     *
     * <p>用韦伯定律的近似式: 可觉差与<b>当前强度</b>成正比, 而不是一个绝对常数。
     */
    public boolean noticeableChange(double previousRaw, double currentRaw) {
        double reference = Math.max(Math.abs(previousRaw), detectionThreshold);
        return Math.abs(currentRaw - previousRaw) >= justNoticeableDifference * reference;
    }

    // ─────────────────────────── 派生 ───────────────────────────

    /** 换一个阈限 —— 嗅觉适应(闻久了闻不到)与视觉暗适应都长这样。 */
    public SensoryAcuity withDetectionThreshold(double newThreshold) {
        return new SensoryAcuity(newThreshold, gain, Math.max(saturation, newThreshold + 1e-6),
                justNoticeableDifference, latencyMillis, unit);
    }

    /** 换一个增益 —— 疲劳、酒精、年龄都会改它。 */
    public SensoryAcuity withGain(double newGain) {
        return new SensoryAcuity(detectionThreshold, newGain, saturation,
                justNoticeableDifference, latencyMillis, unit);
    }

    /** 动态范围的长度 —— 诊断面板与"这条通道还能分辨出多少级"这个问题会用到。 */
    public double dynamicRange() {
        return saturation - detectionThreshold;
    }

    public String describe() {
        return String.format("阈值 %.3f %s / 饱和 %.3f %s / 增益 %.2f / 可觉差 %.4f",
                detectionThreshold, unit, saturation, unit, gain, justNoticeableDifference);
    }

    // ─────────────────────────── 平台自带的五套标定 ───────────────────────────

    /**
     * 人耳 —— 0 dB SPL 起, 120 dB 饱和, 20–20000 Hz。
     *
     * <p>可觉差取 {@code 0.02}(约 0.17 dB): 真人能分辨约 1 dB 的响度差, 而仿真里
     * 手机铃声的音量档位是 0.1 一跳, 取 0.02 让"从 0.5 调到 0.6"是能被听出来的。
     * 这个数<b>刻意比真人稍宽</b> —— 太窄的可觉差会让每一次环境刷新都触发一次
     * "她注意到声音变了", 而那是噪声不是行为。
     */
    public static SensoryAcuity humanEar() {
        return new SensoryAcuity(0.0, 1.0, 120.0, 0.02, 8.0, "dB");
    }

    /** 人眼 —— 0.001 lux 起(星光), 100000 lux 饱和(正午直射)。 */
    public static SensoryAcuity humanEye() {
        return new SensoryAcuity(0.001, 1.0, 100_000.0, 0.08, 40.0, "lux");
    }

    /** 人鼻 —— 浓度归一化到 {@code [0, 1]}, 阈限取 0.02(接近"刚能闻到")。 */
    public static SensoryAcuity humanNose() {
        return new SensoryAcuity(0.02, 1.0, 1.0, 0.15, 400.0, "归一化浓度");
    }

    /** 人舌 —— 五味强度都是 {@code [0, 1]} 归一化。 */
    public static SensoryAcuity humanTongue() {
        return new SensoryAcuity(0.03, 1.0, 1.0, 0.10, 300.0, "归一化强度");
    }

    /**
     * 人皮 —— 温度用摄氏度, 压力用牛顿。
     *
     * <p>阈限取 {@code 0.5}(℃): 皮肤能分辨约 0.5℃ 的温差, 这正是"手有点凉"和
     * "手很凉"之间的那个台阶。低于它的温度变化她感觉不到 —— 而她<b>仍然会</b>因为
     * 内部保暖值跌破舒适带而觉得冷, 因为那是另一条路径(见 {@code TactileChannel}
     * 的"两个来源")。
     */
    public static SensoryAcuity humanSkin() {
        return new SensoryAcuity(0.5, 1.0, 45.0, 0.05, 20.0, "℃");
    }
}
