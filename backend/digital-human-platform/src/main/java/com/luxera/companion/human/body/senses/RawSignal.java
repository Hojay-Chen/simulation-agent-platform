package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.2.2 —— <b>一条物理刺激的原始读数</b>: 感官通道的输入。
 *
 * <h2>为什么要有这一层, 而不是直接把 {@code WorldEvent} 喂给通道</h2>
 * 这是用户"将来接入真实机器人"这个要求在代码里最要紧的一处落点。
 *
 * <p>如果通道的入口签名是 {@code receive(PhoneSoundEmitted event)}, 那么:
 * <ul>
 *   <li>通道就<b>认识世界侧的事件类型</b>了 —— 而 {@code human/} 不许 import
 *       {@code world/}(P3, §8.2.7 的 ArchUnit 会红);</li>
 *   <li>更糟的是, 它认识的是<b>我们自己模拟器</b>造出来的事件类型。将来接上真实的
 *       麦克风, 那个事件类型根本不存在 —— 于是"接真实机器人"变成"重写感官层"。</li>
 * </ul>
 *
 * <p>所以通道的入口是<b>物理量本身</b>: 声压级、照度、浓度、五味强度、温度与压力。
 * 这些东西在仿真里和在真实机器人上<b>是同一批物理量</b> —— 西蒙的麦克风和真麦克风
 * 测的都是声压级。世界侧的事件(如 {@code device.phone.ringtone-started.v1})到
 * 原始读数之间隔着一个<b>适配器</b>, 而那个适配器是整个感官层里唯一需要为
 * "换数据源"而改动的代码 —— 它甚至不在本包里, 因为它是世界侧的事。
 *
 * <pre>{@code
 *   今天:  Phone.AudioSystem ──SoundStimulusParrot──> RawSignal.Sound ──> AuditoryChannel
 *   将来:  麦克风阵列 ────────PCM→声压级/主频──────> RawSignal.Sound ──> AuditoryChannel
 *          (中间那截换了, 右边一个字没改)
 * }</pre>
 *
 * <h2>为什么是 sealed, 而不是一个开放接口</h2>
 * 这一点与 P4 并不冲突, 反而正是 P4 的两半:
 * <ul>
 *   <li><b>封闭的那一半是物理事实</b>: 人只有五种感官。这个封闭性不是设计选择,
 *       所以它值得被编译器检查 —— 一个 {@code receive(RawSignal)} 的 switch 必须
 *       覆盖五种, 漏一种就编译不过。这与 {@code CoreEventCatalog.Modalities} 是
 *       同一类封闭性, 与 {@code Channels} 那类"随时会加一条"的开放集合不同;</li>
 *   <li><b>开放的那一半是每一种感官内部的取值</b>: {@link Sound#frequencyHz()}
 *       之外还会有哪些声学特征(混响、方位、音色)是<b>永远在长</b>的。所以这里
 *       刻意把载荷做成 record 的字段而不是枚举常量 —— 加一个 {@code reverberationMs}
 *       不需要改任何 switch。</li>
 * </ul>
 *
 * <p>反过来说, 一个第三方想要"第六感"(比如磁感、痛觉之外的平衡感)会撞墙。这是
 * <b>刻意的</b>: 那时该做的决定是"它属于触觉的一个子类, 还是一种真正的新感官",
 * 而那是一个需要改 {@code CoreEventCatalog.Modalities} 的架构决定, 不是一个
 * 插件作者该在半夜自己拍板的事。
 *
 * <h2>每个读数都带 {@code observedAt}</h2>
 * 因为感官有<b>传导延迟</b>({@link SensoryAcuity#latencyMillis()})。同一秒发生的
 * 两件事, 她先后感觉到的那一次排序靠它。少这个字段, "先看到闪电再听到雷声"
 * 这种事就没法表达。
 */
public sealed interface RawSignal
        permits RawSignal.Sound, RawSignal.Light, RawSignal.Odor, RawSignal.Taste, RawSignal.Contact {

    /** 这个读数走哪条感官通道 —— 取值见 {@link CoreEventCatalog.Modalities}。 */
    String modality();

    /** 读数被采集到的时刻(不是被她感觉到的时刻 —— 后者要加上传导延迟)。 */
    Instant observedAt();

    /**
     * 主强度的<b>原始值</b>, 量纲由通道自己解释(声压级是 dB, 照度是 lux)。
     *
     * <p>它存在是为了让 {@link SensoryAcuity} 有一个统一的入口去做阈限判断 ——
     * 否则每加一种读数, 灵敏度逻辑就要重写一遍。
     *
     * <p>为什么不用 {@code double primaryMagnitude()} 代替具体字段: 因为通道的
     * {@code interpret()} 需要的是<b>具体的物理量</b>(频率、方位、材质), 而不仅是
     * 一个标量。标量只够做"够不够格被感知", 不够做"这是什么声音"。
     */
    double rawMagnitude();

    /**
     * 这个读数来自哪个世界对象 —— 手机、窗外的雨、她身上那件羽绒服。
     *
     * <p>可以为 {@code null}: 内部生理越界没有外部来源(她不是"因为某样东西"才冷的)。
     * 与 {@code WorldEvent.sourceObjectId()} 同一个约定, 理由也相同 —— 它是
     * <b>String 而不是对象引用</b>, 因为一条读数不该把活对象钉在内存里。
     */
    default String sourceObjectId() {
        return null;
    }

    /** 一行摘要, 给日志与诊断面板。 */
    String describe();

    // ═══════════════════════════ 五种读数 ═══════════════════════════

    /**
     * 听觉读数 —— <b>声压级 + 主频</b>。
     *
     * <p>这两个字段是"未来接真实传感器"的最小集: 任何麦克风阵列都能给出它们,
     * 而它们已经足够支撑"这是什么声音"这个判断的骨架(低频+高能量 = 闷响,
     * 高频+窄带 = 铃声)。
     *
     * @param soundPressureDb 声压级, 0(听阈)–120(痛阈) dB
     * @param frequencyHz     主频, 20–20000 Hz。取 0 表示"宽带噪声, 没有主频"
     * @param durationMillis  持续时长
     * @param sourceObjectId  声源对象 id, 可为空
     * @param observedAt      采集时刻
     */
    record Sound(double soundPressureDb, double frequencyHz, double durationMillis,
                 String sourceObjectId, Instant observedAt) implements RawSignal {

        public Sound {
            Objects.requireNonNull(observedAt, "听觉读数必须带采集时刻 —— 感官有传导延迟, 排序依赖它");
            if (soundPressureDb < 0.0) {
                throw new IllegalArgumentException(
                        "声压级不能为负, 收到 " + soundPressureDb + " —— 负的 dB 在物理上是可能的(极安静), "
                                + "但那属于'低于听阈', 通道会把它衰减成 0, 不需要用一个负数表达");
            }
            if (frequencyHz < 0.0) {
                throw new IllegalArgumentException("主频不能为负, 收到 " + frequencyHz);
            }
            if (durationMillis < 0.0) {
                throw new IllegalArgumentException("时长不能为负, 收到 " + durationMillis);
            }
        }

        /** 便捷: 一个瞬时声音。 */
        public static Sound of(double db, double hz, Instant at) {
            return new Sound(db, hz, 0.0, null, at);
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.AUDITORY;
        }

        @Override
        public double rawMagnitude() {
            return soundPressureDb;
        }

        /** 是不是"人说话"所在的那一段频带 —— 用于区分话音与机械声, <b>不是</b>用来识别内容。 */
        public boolean inSpeechBand() {
            return frequencyHz >= 300.0 && frequencyHz <= 3400.0;
        }

        @Override
        public String describe() {
            return String.format("声音 %.1f dB @ %.0f Hz %s (%.0f ms)",
                    soundPressureDb, frequencyHz, inSpeechBand() ? "[话音频带]" : "", durationMillis);
        }
    }

    /**
     * 视觉读数 —— <b>照度 + 色温 + 方位</b>。
     *
     * @param illuminanceLux     照度, 0.001(星光)–100000(正午) lux
     * @param colorTemperatureK  色温, 1700(烛光)–10000(阴天) K。<b>这是"这是不是屏幕"的
     *                           第一个线索</b>: 手机屏约 6500 K 且照度陡增
     * @param azimuthDegrees     方位角, {@code [-180, 180]}, 0 = 正前方。
     *                           超出视野的读数会被通道衰减成 0 —— "她没看见"的可解释来源
     * @param sourceObjectId     光源对象 id, 可为空
     * @param observedAt         采集时刻
     */
    record Light(double illuminanceLux, double colorTemperatureK, double azimuthDegrees,
                 String sourceObjectId, Instant observedAt) implements RawSignal {

        public Light {
            Objects.requireNonNull(observedAt, "视觉读数必须带采集时刻");
            if (illuminanceLux < 0.0) {
                throw new IllegalArgumentException("照度不能为负, 收到 " + illuminanceLux);
            }
            if (colorTemperatureK <= 0.0) {
                throw new IllegalArgumentException(
                        "色温必须为正, 收到 " + colorTemperatureK + " —— 单位是开尔文, 0 K 是没有光");
            }
            if (azimuthDegrees < -180.0 || azimuthDegrees > 180.0) {
                throw new IllegalArgumentException(
                        "方位角必须在 [-180, 180] 度内, 收到 " + azimuthDegrees + " —— "
                                + "超出这个范围的方位无法与视野比较, 而那正是本字段存在的理由");
            }
        }

        /** 便捷: 正前方一个点光源。 */
        public static Light of(double lux, Instant at) {
            return new Light(lux, 6500.0, 0.0, null, at);
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.VISUAL;
        }

        @Override
        public double rawMagnitude() {
            return illuminanceLux;
        }

        @Override
        public String describe() {
            return String.format("光 %.1f lux @ %.0f K 方位 %.0f°", illuminanceLux, colorTemperatureK,
                    azimuthDegrees);
        }
    }

    /**
     * 嗅觉读数 —— <b>浓度 + 种类标签</b>。
     *
     * <p>{@code kind} 刻意是<b>字符串</b>而不是枚举: "饭香/烟味/雨后土腥味"是一个会一直
     * 增长的词表, 而且它是<b>主观类别</b>(同样的分子在不同人那里闻起来不同)。把它做成
     * 枚举就等于宣称"世界上只有这几种味道", 而 P4 禁止的正是这种宣称。
     *
     * @param kind        气味类别标签, 如 {@code "food"} / {@code "smoke"} / {@code "petrol"}
     * @param concentration 归一化浓度 {@code [0, 1]}
     */
    record Odor(String kind, double concentration, String sourceObjectId, Instant observedAt)
            implements RawSignal {

        public Odor {
            Objects.requireNonNull(observedAt, "嗅觉读数必须带采集时刻");
            Objects.requireNonNull(kind, "气味必须有一个类别标签 —— 没有标签的浓度无法解释'她闻到了什么'");
            if (kind.isBlank()) {
                throw new IllegalArgumentException("气味类别标签不能是空白串");
            }
            if (concentration < 0.0 || concentration > 1.0) {
                throw new IllegalArgumentException(
                        "气味浓度必须归一化到 [0, 1], 收到 " + concentration);
            }
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.OLFACTORY;
        }

        @Override
        public double rawMagnitude() {
            return concentration;
        }

        @Override
        public String describe() {
            return String.format("气味 %s 浓度 %.2f", kind, concentration);
        }
    }

    /**
     * 味觉读数 —— <b>五味强度</b>。
     *
     * <p>五个分量而不是一个"好不好吃": 好吃是<b>评价</b>, 五味是<b>读数</b>。
     * 评价属于 Mind(而且取决于她饿不饿、这是谁做的), 读数属于舌头。
     *
     * <p>表里只有"吃饭"这一个活动会产生它 —— 见 {@code GustatoryChannel} 的说明。
     */
    record Taste(double sweetness, double saltiness, double sourness,
                 double bitterness, double umami, Instant observedAt) implements RawSignal {

        public Taste {
            Objects.requireNonNull(observedAt, "味觉读数必须带采集时刻");
            for (double v : new double[]{sweetness, saltiness, sourness, bitterness, umami}) {
                if (v < 0.0 || v > 1.0) {
                    throw new IllegalArgumentException(
                            "五味强度必须归一化到 [0, 1], 收到 " + v + " —— 未归一化的读数会让"
                                    + "'她尝了一口'这件事的强度无法与其他通道比较");
                }
            }
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.GUSTATORY;
        }

        @Override
        public double rawMagnitude() {
            return overallIntensity();
        }

        /** 整体味觉强度 —— 五味的平方和开方, 而不是简单相加(相加会让"五味俱全"永远满强度)。 */
        public double overallIntensity() {
            return Math.min(1.0, Math.sqrt(sweetness * sweetness + saltiness * saltiness
                    + sourness * sourness + bitterness * bitterness + umami * umami));
        }

        @Override
        public String describe() {
            return String.format("味觉 甜%.2f 咸%.2f 酸%.2f 苦%.2f 鲜%.2f",
                    sweetness, saltiness, sourness, bitterness, umami);
        }
    }

    /**
     * 触觉读数 —— <b>温度 + 压力 + 材质</b>, 外加外来的痛觉。
     *
     * <h2>它在整个感官层里的位置最特殊</h2>
     * 因为触觉是唯一一条<b>既有外部来源又有内部来源</b>的通道:
     * <ul>
     *   <li>外部: 手机震了一下、碰到热水杯、被人拍了一下 —— 走这个 record;</li>
     *   <li>内部: 保暖值跌破舒适带而"她觉得冷" —— 不走这个 record, 走
     *       {@link SensoryChannel#feel(SensoryStimulus)}(她并没有碰到任何东西)。</li>
     * </ul>
     * 但两条路径的产物<b>是同一个类型</b>({@link SensoryStimulus}), 所以 Mind 侧
     * 看不出区别 —— 这正是要的效果: 真人也分不清"觉得冷"是外面真的冷还是自己发烧了。
     *
     * @param celsius    接触面温度, ℃
     * @param newtons    接触压力, N。0 表示"没有接触", 只有温度读数
     * @param material   材质标签(字符串, 理由同 {@link Odor#kind()})
     * @param pain       这次接触造成的痛觉 {@code [0, 1]}, 0 = 不疼
     */
    record Contact(double celsius, double newtons, String material, double pain,
                   String sourceObjectId, Instant observedAt) implements RawSignal {

        public Contact {
            Objects.requireNonNull(observedAt, "触觉读数必须带采集时刻");
            Objects.requireNonNull(material, "接触必须有材质标签 —— 没有它无法解释'她被什么碰到了'");
            if (newtons < 0.0) {
                throw new IllegalArgumentException("接触压力不能为负, 收到 " + newtons);
            }
            if (pain < 0.0 || pain > 1.0) {
                throw new IllegalArgumentException("痛觉必须归一化到 [0, 1], 收到 " + pain);
            }
        }

        /** 便捷: 只是"碰到了", 不疼。 */
        public static Contact touch(double celsius, double newtons, String material, Instant at) {
            return new Contact(celsius, newtons, material, 0.0, null, at);
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.TACTILE;
        }

        /**
         * 触觉的主强度取<b>痛觉与压力的较大者</b>, 而不是温度。
         *
         * <p>理由是温度在触觉里走的是另一条路: 皮肤对温度的<b>绝对</b>读数并不敏感
         * (32℃ 的水和 34℃ 的水你分不出来), 敏感的是<b>温差</b> —— 而温差需要两个读数
         * 才能算出来, 不属于单条读数的主强度。所以温度只作为 {@code interpret()} 里的
         * 上下文, 由通道自己比较前后两次读数。
         */
        @Override
        public double rawMagnitude() {
            return Math.max(pain, Math.max(0.0, newtons) / 10.0);
        }

        public boolean isPainful() {
            return pain > 0.0;
        }

        @Override
        public String describe() {
            return String.format("接触 %.1f℃ %.1fN 材质=%s%s", celsius, newtons, material,
                    isPainful() ? String.format(" 痛觉%.2f", pain) : "");
        }
    }
}
