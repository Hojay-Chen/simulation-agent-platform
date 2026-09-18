package com.luxera.companion.world.environment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §4.4.1 —— <b>某一刻、某个地点的环境快照</b>: 气温、湿度、风、光照、天气。
 *
 * <h2>它是"事实", 不是"影响"</h2>
 * 这是本类型与它引发的那些事件之间最重要的分工。快照说的是:
 * <pre>
 *   现在 3℃, 湿度 0.62, 风速 4.1 m/s, 阴 —— 这是一个<b>事实</b>
 * </pre>
 * 它<b>不说</b>"她很冷"。她的冷由 Body 的稳态模型算出来(§3.2): 同样的 3℃ 对
 * 穿羽绒服的她与穿短袖的她是两件事, 而世界不知道她穿了什么。
 *
 * <p>所以本类型里没有 {@code comfortLevel}、没有 {@code isCold}、没有
 * {@code shouldWearCoat} —— 一旦有了, 世界就被迫知道她的身体与她的衣柜,
 * 而 Human 与 World 零交互(P3)就破了。
 *
 * <h2>为什么字段是 13 个而不是把 {@code WeatherCondition} 拆出去</h2>
 * 因为它们描述的是<b>同一时刻的同一件事</b>: "雨"没有湿度就没有意义, "晴天"没有光照
 * 也无法折算到她身上。拆成两个对象的后果是它们会出现时间戳不一致的版本
 * (光照是 10:00 的、天气是 10:07 的), 而下游必须回答"以哪个为准" ——
 * 那是一道只有把两者合起来才能消失的问题。
 *
 * <h2>为什么必须用 {@link Builder} 而不是 13 个位置参数</h2>
 * 因为其中<b>有 9 个是 {@code double}</b>。位置参数下, 把湿度传给风速、把压强传给
 * {@code uvIndex} 都会<b>静默通过编译</b>, 而症状是"她觉得今天特别潮湿"这种
 * 永远查不到源头的行为偏差。流式构造器让每个值都必须写出字段名 ——
 * 代价是几行样板, 换来的是这类错误从"运行期谜题"变成"根本写不出来"。
 *
 * <p>与 {@code CapabilityDescriptor.Builder} 同一手法, 但理由更强:
 * 那里的字段类型不同, 编译器还能帮上一点忙; 这里全是 {@code double}, 它一点忙都帮不上。
 *
 * <h2>与 §4.4.1 的两处差异(在交付报告里已记录)</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>文档 §4.4.1 有吗</th><th>为什么要加</th></tr>
 *   <tr>
 *     <td>{@link #aqi()}</td>
 *     <td><b>没有</b></td>
 *     <td>但目录里有 {@code environment.air-quality-changed.v1}(载荷含 {@code aqi}),
 *         而快照里没有 AQI 的来源 —— 那条事件会拿不到数据。这是一个文档缺口,
 *         补字段而不是删事件, 因为"空气差让她有点闷"是一个真实且有用的影响</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #sunrise()} / {@link #sunset()}</td>
 *     <td><b>没有</b></td>
 *     <td>同理: {@code environment.daylight-changed.v1} 的载荷要
 *         {@code daylightHours, sunrise, sunset}, 而 §4.4.1 的字段里没有任何一项
 *         能算出日照时长。这里存两个时刻, 日照时长由 {@link #daylightHours()} 算出来 ——
 *         <b>不存第三个字段</b>, 因为存下来就会有两个真相</td>
 *   </tr>
 * </table>
 *
 * @param temperatureCelsius        气温(摄氏度)
 * @param humidity                  相对湿度 {@code [0, 1]}
 * @param windSpeedMetersPerSecond  风速(米/秒)
 * @param precipitationMmPerHour    降水量(毫米/小时)
 * @param cloudCover                云量 {@code [0, 1]}
 * @param illuminanceLux            光照(勒克斯)。它在数值上跨越 6 个数量级
 *                                  (月光 0.1、室内 300、正午 100000), 所以对它的比较
 *                                  <b>不能用绝对差</b> —— 见 {@link Delta#illuminanceRatio()}
 * @param uvIndex                   紫外线指数, 通常 {@code [0, 11+]}
 * @param pressureHpa               气压(百帕)
 * @param aqi                       空气质量指数。见类注释里的差异说明
 * @param condition                 天气分类。见 {@link WeatherCondition} ——
 *                                  它是本设计里少数几个<b>允许</b>用枚举的领域
 * @param sunrise                   日出时刻。可为空(数据源没给) —— 见类注释的差异说明
 * @param sunset                    日落时刻。可为空。两者必须同时存在或同时为空
 * @param observedAt                这份数据是<b>什么时候观测到的</b>。它不等于"什么时候
 *                                  拿到的" —— 气象站的数据可能延迟 20 分钟,
 *                                  而这个区别会影响"她 8:00 出门时其实在下雨"这类判断
 */
public record EnvironmentSnapshot(
        double temperatureCelsius,
        double humidity,
        double windSpeedMetersPerSecond,
        double precipitationMmPerHour,
        double cloudCover,
        double illuminanceLux,
        double uvIndex,
        double pressureHpa,
        int aqi,
        WeatherCondition condition,
        Instant sunrise,
        Instant sunset,
        Instant observedAt) {

    /**
     * 舒适带的下限(摄氏度)。
     *
     * <p>它是一个<b>约定</b>, 而不是"她觉得舒服的温度" —— 后者是她的身体状态算出来的。
     * 本常量只用于两处: 公式化的风速体感折算, 以及台账权重的量级估算。
     * 把它误当成"她冷了"的判据, 就等于让世界替她的身体做判断。
     */
    public static final double COMFORT_LOW_CELSIUS = 18.0;

    /** 舒适带的上限(摄氏度)。 */
    public static final double COMFORT_HIGH_CELSIUS = 26.0;

    public EnvironmentSnapshot {
        if (Double.isNaN(temperatureCelsius)) {
            throw new IllegalArgumentException("气温不能是 NaN");
        }
        humidity = clampRatio(humidity, "湿度");
        cloudCover = clampRatio(cloudCover, "云量");
        if (Double.isNaN(windSpeedMetersPerSecond) || windSpeedMetersPerSecond < 0) {
            throw new IllegalArgumentException(
                    "风速不能为负(收到 " + windSpeedMetersPerSecond
                            + ") —— 风向由别处表达, 风的大小没有负数");
        }
        if (Double.isNaN(precipitationMmPerHour) || precipitationMmPerHour < 0) {
            throw new IllegalArgumentException("降水量不能为负, 收到 " + precipitationMmPerHour);
        }
        if (Double.isNaN(illuminanceLux) || illuminanceLux < 0) {
            throw new IllegalArgumentException("光照不能为负, 收到 " + illuminanceLux);
        }
        if (Double.isNaN(pressureHpa) || pressureHpa <= 0) {
            throw new IllegalArgumentException("气压必须为正, 收到 " + pressureHpa);
        }
        if (aqi < 0) {
            throw new IllegalArgumentException("AQI 不能为负, 收到 " + aqi);
        }
        Objects.requireNonNull(condition, "环境快照必须带天气分类 —— 想表达'不知道'请用 UNKNOWN");
        Objects.requireNonNull(observedAt, "环境快照必须带观测时刻 —— 仿真时钟下不许读墙上时钟");
        if ((sunrise == null) != (sunset == null)) {
            // 只有一个时刻是<b>坏数据</b>而不是"部分已知": 单独一个日出时刻算不出日照时长,
            // 而一个"日出在 06:12 但不知道几点日落"的日长会被下游默认成 12 小时 ——
            // 那个默认值会静默地污染情绪通道好几个小时
            sunrise = null;
            sunset = null;
        }
        if (sunrise != null && sunset.isBefore(sunrise)) {
            // 日落早于日出只可能来自时区/年份的错误, 保留它会让 daylightHours() 变成负数
            sunrise = null;
            sunset = null;
        }
    }

    private static double clampRatio(double value, String what) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException(what + "不能是 NaN");
        }
        if (value < 0) {
            throw new IllegalArgumentException(what + "不能为负, 收到 " + value);
        }
        if (value > 1) {
            throw new IllegalArgumentException(
                    what + "是比例(0..1), 收到 " + value + " —— 想表达 62% 请写 0.62");
        }
        return value;
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 一个空的构造器 —— 13 个字段全部显式写出比猜位置安全, 见类注释。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 一个最简快照: 只给气温与观测时刻, 其余取"温和、无风、无雨、晴天"的中性值。 */
    public static EnvironmentSnapshot mild(double temperatureCelsius, Instant observedAt) {
        return builder()
                .temperatureCelsius(temperatureCelsius)
                .observedAt(observedAt)
                .build();
    }

    /**
     * 快照的流式构造器。
     *
     * <p>默认值刻意是<b>中性而不是零</b>: 湿度 0.5、云量 0.5、光照 3000 lux、
     * 气压 1013 hPa、AQI 50。用 0 当默认值会让"忘了填湿度"表现成"沙漠级干燥",
     * 而那个错误会一路传到她的体感温度上, 且看不出是谁的错。
     */
    public static final class Builder {
        private double temperatureCelsius = 20.0;
        private double humidity = 0.5;
        private double windSpeedMetersPerSecond = 0.0;
        private double precipitationMmPerHour = 0.0;
        private double cloudCover = 0.5;
        private double illuminanceLux = 3000.0;
        private double uvIndex = 0.0;
        private double pressureHpa = 1013.25;
        private int aqi = 50;
        private WeatherCondition condition = WeatherCondition.UNKNOWN;
        private Instant sunrise;
        private Instant sunset;
        private Instant observedAt;

        private Builder() {
        }

        public Builder temperatureCelsius(double v) {
            this.temperatureCelsius = v;
            return this;
        }

        public Builder humidity(double v) {
            this.humidity = v;
            return this;
        }

        public Builder windSpeed(double v) {
            this.windSpeedMetersPerSecond = v;
            return this;
        }

        public Builder precipitation(double v) {
            this.precipitationMmPerHour = v;
            return this;
        }

        public Builder cloudCover(double v) {
            this.cloudCover = v;
            return this;
        }

        public Builder illuminanceLux(double v) {
            this.illuminanceLux = v;
            return this;
        }

        public Builder uvIndex(double v) {
            this.uvIndex = v;
            return this;
        }

        public Builder pressureHpa(double v) {
            this.pressureHpa = v;
            return this;
        }

        public Builder aqi(int v) {
            this.aqi = v;
            return this;
        }

        public Builder condition(WeatherCondition v) {
            this.condition = v;
            return this;
        }

        /**
         * 日照窗口 —— 一次给两个时刻。
         *
         * <p>刻意<b>不</b>分成 {@code sunrise(...)} 与 {@code sunset(...)} 两个方法:
         * 只调用其中一个会得到一个必然被归一化成"都为空"的快照, 而那个归一化是
         * 静默的。一个"必须一次给两个"的方法让这件事在写的时候就看得见。
         */
        public Builder daylight(Instant sunrise, Instant sunset) {
            this.sunrise = sunrise;
            this.sunset = sunset;
            return this;
        }

        public Builder observedAt(Instant v) {
            this.observedAt = v;
            return this;
        }

        public EnvironmentSnapshot build() {
            Objects.requireNonNull(observedAt,
                    "快照必须带观测时刻 —— 缺了它的快照无法被排序, 也无法重放");
            return new EnvironmentSnapshot(temperatureCelsius, humidity, windSpeedMetersPerSecond,
                    precipitationMmPerHour, cloudCover, illuminanceLux, uvIndex, pressureHpa, aqi,
                    condition, sunrise, sunset, observedAt);
        }
    }

    // ─────────────────────────── 派生量 ───────────────────────────

    /**
     * 体感温度 —— <b>算出来的, 不存下来</b>。
     *
     * <p>为什么不存: 存下来的话, 它就与"气温 + 风 + 湿度"这三个字段构成了两个真相。
     * 某一天有人只更新了气温而忘了更新体感温度, 于是"外面 30℃ 而体感 12℃"这样的
     * 荒谬组合会进到她的身体模型里, 而且看不出是谁的错。
     *
     * <p>公式分两段, 与真实世界的做法一致:
     * <ul>
     *   <li><b>冷 + 有风</b> → 风寒指数(简化版): 风把贴着皮肤的那层暖空气吹走;
     *       4 m/s 的 3℃ 感觉像 0℃ 左右;</li>
     *   <li><b>热 + 潮湿</b> → 湿度让汗没法蒸发, 体感比气温更高。</li>
     * </ul>
     */
    public double feelsLikeCelsius() {
        if (temperatureCelsius <= 10.0 && windSpeedMetersPerSecond > 1.3) {
            // 风寒: 风速每增加 1 m/s, 体感在 10℃ 附近约低 0.6℃(量级近似)
            return temperatureCelsius - Math.min(12.0, (windSpeedMetersPerSecond - 1.3) * 0.6)
                    - (10.0 - temperatureCelsius) * 0.05;
        }
        if (temperatureCelsius >= 26.0 && humidity > 0.4) {
            // 闷热: 湿度每高 10 个百分点, 体感约高 0.8℃
            return temperatureCelsius + (humidity - 0.4) * 8.0;
        }
        return temperatureCelsius;
    }

    /**
     * 日照时长(小时)。
     *
     * <p>数据源没给日出日落时返回 {@code 12.0} —— 一个中性的、不会让情绪通道
     * 突然偏向某一侧的值。刻意<b>不</b>返回 0: "没数据"被当成"极夜"会让她在
     * 盛夏的上海进入季节性情绪低谷, 而那是一条查不到源头的偏差。
     */
    public double daylightHours() {
        if (sunrise == null || sunset == null) {
            return 12.0;
        }
        return Duration.between(sunrise, sunset).toMinutes() / 60.0;
    }

    /** 有没有日照数据 —— 让调用方能区分"12 小时"与"不知道"。 */
    public boolean hasDaylightWindow() {
        return sunrise != null && sunset != null;
    }

    public Optional<Instant> sunriseIfKnown() {
        return Optional.ofNullable(sunrise);
    }

    public Optional<Instant> sunsetIfKnown() {
        return Optional.ofNullable(sunset);
    }

    /** 在下雨/下雪吗 —— 看降水量而不是天气分类(分类可能滞后于实际)。 */
    public boolean precipitating() {
        return precipitationMmPerHour > 0.0;
    }

    /** 结冰的温度吗。 */
    public boolean freezing() {
        return temperatureCelsius <= 0.0;
    }

    /**
     * 偏离舒适带多少度 —— 低于下限为负, 高于上限为正, 带内为 0。
     *
     * <p>台账权重由它折算(见 {@code Environment.TemperatureChanged}), 而它本身
     * <b>不是</b>"她冷了"的判据: 一个刚跑完步的人会觉得 10℃ 很舒服。
     */
    public double deviationFromComfortBand() {
        if (temperatureCelsius < COMFORT_LOW_CELSIUS) {
            return temperatureCelsius - COMFORT_LOW_CELSIUS;
        }
        if (temperatureCelsius > COMFORT_HIGH_CELSIUS) {
            return temperatureCelsius - COMFORT_HIGH_CELSIUS;
        }
        return 0.0;
    }

    /**
     * 与上一次快照的差异 —— <b>环境事件的产生机制就是这个</b>。
     *
     * <p>用户对这件事的要求是: "environment …… 可以定期执行比如每分钟或每 10 分钟
     * 更新一次形成一个 event"。也就是说: 每个 tick <b>不</b>算一条事件,
     * 只有"与上一次相比真的变了"的部分才成为事件, 而"变了多少算变"这件事
     * 由 {@link Environment} 决定(它是策略, 不是数据)。
     */
    public Delta deltaFrom(EnvironmentSnapshot previous) {
        if (previous == null) {
            // 第一次观测: 一切都是"新的"。用 firstObservation 标出来而不是
            // 让所有差值都等于 0 —— 否则首帧会被判成"什么都没变", 于是她
            // 永远收不到第一条环境数据
            return Delta.withoutPrevious();
        }
        return new Delta(
                this.temperatureCelsius - previous.temperatureCelsius,
                this.feelsLikeCelsius() - previous.feelsLikeCelsius(),
                this.humidity - previous.humidity,
                this.windSpeedMetersPerSecond - previous.windSpeedMetersPerSecond,
                this.precipitationMmPerHour - previous.precipitationMmPerHour,
                this.cloudCover - previous.cloudCover,
                previous.illuminanceLux <= 0 ? 0.0 : (this.illuminanceLux / previous.illuminanceLux),
                this.uvIndex - previous.uvIndex,
                this.pressureHpa - previous.pressureHpa,
                this.aqi - previous.aqi,
                this.daylightHours() - previous.daylightHours(),
                this.condition != previous.condition,
                false);
    }

    /**
     * 两次快照之间的差异。
     *
     * <h2>为什么光照用比例而不是差值</h2>
     * 光照跨越 6 个数量级: 从室内的 300 lux 到正午的 100000 lux。<b>差值</b>在那里
     * 没有任何意义(差 299700 与差 50000 都"很大"), 而<b>比例</b>有意义 ——
     * 300 → 150 是"暗了一半"(她该开灯了), 100000 → 50000 也是"暗了一半"(但那是
     * 云飘过, 她不需要开灯)。处境的判断留给下游, 这里只给一个量纲正确的数。
     *
     * @param illuminanceRatio 光照比例(新/旧)。旧值为 0 时给 0 —— 见 {@link #withoutPrevious}
     * @param firstObservation 这是不是第一次观测。为真时所有差值都无意义
     */
    public record Delta(double temperatureCelsius,
                        double feelsLikeCelsius,
                        double humidity,
                        double windSpeed,
                        double precipitation,
                        double cloudCover,
                        double illuminanceRatio,
                        double uvIndex,
                        double pressureHpa,
                        int aqi,
                        double daylightHours,
                        boolean conditionChanged,
                        boolean firstObservation) {

        /**
         * 没有上一份快照时的差值 —— 一切都是"新的"。
         *
         * <p>方法名刻意<b>不</b>叫 {@code firstObservation()}: 那正是 record 的分量名,
         * 而 Java 不允许一个静态方法与取值方法同名同参(编译期就会报
         * 「accessor method must be public」—— 一条与真实原因相距很远的错误)。
         * 一个叫 {@code withoutPrevious} 的名字同时说清了它什么时候被用。
         */
        static Delta withoutPrevious() {
            return new Delta(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, true);
        }

        /** 气温变化超过阈值了吗 —— 阈值由调用方给, 见 {@link Environment}。 */
        public boolean temperatureChangedBy(double thresholdCelsius) {
            return firstObservation || Math.abs(temperatureCelsius) >= thresholdCelsius;
        }

        public boolean humidityChangedBy(double threshold) {
            return firstObservation || Math.abs(humidity) >= threshold;
        }

        public boolean windChangedBy(double thresholdMps) {
            return firstObservation || Math.abs(windSpeed) >= thresholdMps;
        }

        /**
         * 光照变化超过阈值了吗 —— 用<b>比例</b>判断, 见 record 注释。
         *
         * <p>{@code 0.75} 意味着"暗了 25% 或亮了 33%": 这是一个会被她注意到的量级,
         * 而 5% 的波动(云飘过)不该惊动任何人。
         */
        public boolean illuminanceChangedBy(double ratioThreshold) {
            if (firstObservation) {
                return true;
            }
            if (illuminanceRatio <= 0) {
                return true;
            }
            return Math.abs(1.0 - illuminanceRatio) >= ratioThreshold;
        }

        public boolean airQualityChangedBy(int threshold) {
            return firstObservation || Math.abs(aqi) >= threshold;
        }

        public boolean daylightChangedBy(double thresholdHours) {
            return firstObservation || Math.abs(daylightHours) >= thresholdHours;
        }

        /** 开始降水了(从 0 变成 > 0)。这是"雨声"那条实时刺激的产生条件。 */
        public boolean precipitationStarted() {
            return !firstObservation && precipitation > 0.0;
        }

        /** 降水变大了(已经在下, 而且下得更猛) —— 它不是开始, 所以不产生实时刺激。 */
        public boolean precipitationIntensified(double thresholdMm) {
            return !firstObservation && precipitation >= thresholdMm;
        }

        /** 一次刷新里到底有没有值得投出去的东西。 */
        public boolean anythingSignificant() {
            return firstObservation || conditionChanged
                    || Math.abs(temperatureCelsius) > 0
                    || Math.abs(humidity) > 0
                    || Math.abs(windSpeed) > 0
                    || Math.abs(precipitation) > 0
                    || Math.abs(aqi) > 0;
        }

        /** 一行摘要 —— 日志与诊断用。 */
        public String describe() {
            if (firstObservation) {
                return "首次观测";
            }
            return "Δ(t " + String.format("%+.2f", temperatureCelsius)
                    + "℃, rh " + String.format("%+.3f", humidity)
                    + ", wind " + String.format("%+.2f", windSpeed)
                    + ", rain " + String.format("%+.2f", precipitation)
                    + ", lux×" + String.format("%.2f", illuminanceRatio)
                    + ", aqi " + (aqi >= 0 ? "+" : "") + aqi
                    + (conditionChanged ? ", 天气变了" : "") + ")";
        }
    }

    // ─────────────────────────── 摘要 ───────────────────────────

    /** 一行摘要 —— 日志、诊断面板与事件载荷的说明都用它。<b>不含任何关于她的判断</b>。 */
    public String describe() {
        return String.format(Locale.ROOT,
                "%.1f℃(体感 %.1f℃) 湿度 %.0f%% 风 %.1fm/s 降水 %.1fmm/h %s 光照 %.0flux AQI %d",
                temperatureCelsius, feelsLikeCelsius(), humidity * 100,
                windSpeedMetersPerSecond, precipitationMmPerHour, condition.label(),
                illuminanceLux, aqi);
    }

    @Override
    public String toString() {
        return describe();
    }

    // ═══════════════════════════ 天气分类 ═══════════════════════════

    /**
     * 天气分类 —— <b>本设计里少数几个允许用枚举的领域概念之一</b>。
     *
     * <h2>它为什么踩不进 P4 的禁令</h2>
     * 判别标准还是那三条(P4), 逐条对:
     * <table border="1">
     *   <tr><th>判别问题</th><th>本枚举的答案</th></tr>
     *   <tr>
     *     <td>新增一种天气会改变 Agent 能做什么吗?</td>
     *     <td><b>不会。</b>多一种"雨夹雪"不会让她多出一个动作 ——
     *         她做的事仍然是"穿衣服 / 带伞 / 不出门", 那些由她的计划决定</td>
     *   </tr>
     *   <tr>
     *     <td>它是分类事实还是领域扩展点?</td>
     *     <td><b>分类事实。</b>气象学本身就把天气分成有限几类, 这是外部世界的既有分法,
     *         不是我们发明的扩展点</td>
     *   </tr>
     *   <tr>
     *     <td>第三方需要往这里加成员吗?</td>
     *     <td><b>不需要。</b>第三方接的是"环境数据提供方"
     *         ({@link EnvironmentProvider}), 而它的输出必须落在这几类里 ——
     *         因为下游(她的视觉与听觉通道)本来也只区分这几类</td>
     *   </tr>
     * </table>
     *
     * <p>反面对照: {@code DeviceType} / {@code ApplicationType} 与
     * {@code NotificationKind} 三条全踩 —— 加设备就是加她能做的事, 而且必须能由
     * 第三方加。所以那些东西在本设计里只能是"接口 + {@code @DomainType} + 注册表"。
     *
     * <h2>为什么留一个 {@link #UNKNOWN}</h2>
     * 因为 {@link #tryParse} 要处理真实 API 的返回值, 而"API 给了一个我们没见过的分类"
     * 必须有一个<b>如实</b>的去处。把它默认成 {@code CLEAR} 会让"数据源换了字段名"
     * 表现成"永远晴天", 而那是一条会安静地毁掉一整季情绪数据的偏差。
     */
    public enum WeatherCondition {

        /** 晴。 */
        CLEAR("晴", false, 1.0),

        /** 多云。 */
        PARTLY_CLOUDY("多云", false, 0.8),

        /** 阴。 */
        OVERCAST("阴", false, 0.45),

        /** 雨。 */
        RAIN("雨", true, 0.25),

        /** 雪。 */
        SNOW("雪", true, 0.3),

        /** 雾。 */
        FOG("雾", false, 0.5),

        /** 雷雨 —— 与"雨"的区别是它会打断她(雷声), 所以另有处理。 */
        THUNDERSTORM("雷雨", true, 0.2),

        /** 数据源给了一个我们没见过的分类。见类注释"为什么留一个 UNKNOWN"。 */
        UNKNOWN("未知", false, 0.6);

        private final String label;
        private final boolean precipitating;
        private final double daylightFactor;

        WeatherCondition(String label, boolean precipitating, double daylightFactor) {
            this.label = label;
            this.precipitating = precipitating;
            this.daylightFactor = daylightFactor;
        }

        /** 中文标签 —— 给诊断面板与 LLM 用。 */
        public String label() {
            return label;
        }

        /**
         * 这个分类本身意味着降水吗。
         *
         * <p>它是<b>提示而不是权威</b>: 真正的判据是
         * {@link EnvironmentSnapshot#precipitationMmPerHour()} 里的毫米数,
         * 因为分类会滞后于实际("雨停了但天气分类还没更新")。
         * 两者同时存在是刻意的 —— 一个"只看分类"的实现会在雨停后 10 分钟仍然投雨声。
         */
        public boolean precipitating() {
            return precipitating;
        }

        /**
         * 这个天气下, 户外的光照大致是晴天的几成 ——
         * 给"没有光照数据但知道天气"的数据源一个可用的近似。
         */
        public double daylightFactor() {
            return daylightFactor;
        }

        /**
         * 按名字找分类 —— 大小写不敏感, 也认中文标签与常见别名。
         *
         * <p>存在理由与 {@code AudioChannel.tryParse} 相同: 数据来自外部服务,
         * 而外部服务会把同一件事写成 「Sunny」/「clear」/「晴」。
         * 用 {@code valueOf} 的话, 一次大小写不对就等于丢掉这一整份快照。
         */
        public static Optional<WeatherCondition> tryParse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String trimmed = raw.trim();
            for (WeatherCondition condition : values()) {
                if (condition.name().equalsIgnoreCase(trimmed) || condition.label.equals(trimmed)) {
                    return Optional.of(condition);
                }
            }
            // 常见别名 —— 只列<b>确实见过</b>的写法, 不做模糊匹配: 模糊匹配会让
            // "overcast" 与 "over" 这类前缀关系产生难以预测的结果
            return switch (trimmed.toLowerCase(Locale.ROOT)) {
                case "sunny", "clear-sky" -> Optional.of(CLEAR);
                case "cloudy", "mostly-cloudy", "partly-cloudy" -> Optional.of(PARTLY_CLOUDY);
                case "drizzle", "shower", "moderate-rain" -> Optional.of(RAIN);
                case "sleet", "light-snow" -> Optional.of(SNOW);
                case "thunder", "storm" -> Optional.of(THUNDERSTORM);
                case "mist", "haze" -> Optional.of(FOG);
                default -> Optional.empty();
            };
        }

        /** 平台认识的全部取值 —— 拼进能力描述与诊断面板。 */
        public static List<String> known() {
            List<String> keys = new java.util.ArrayList<>();
            for (WeatherCondition condition : values()) {
                keys.add(condition.name().toLowerCase(Locale.ROOT));
            }
            return List.copyOf(keys);
        }
    }
}
