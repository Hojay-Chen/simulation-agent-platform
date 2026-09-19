package com.luxera.companion.world.device;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.boundary.event.EventFabric;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.3 —— <b>手机的音频系统</b>: 四路独立音量 + 发声。
 *
 * <h2>用户对它的原话</h2>
 * <blockquote>
 *   我们正常手机有软件通知声音大小、手机铃声声音大小、闹钟声音大小
 * </blockquote>
 * 这三路(加上媒体音量一共四路)各自独立, 见 {@link AudioChannel}。
 *
 * <h2>为什么它必须是对象而不是工具方法</h2>
 * 因为音量是<b>她会改的状态</b>: 她把通知音量调小了、她开会时按了静音。
 * 这些状态决定了"她能不能听见", 而"她能不能听见"决定了整条认知链的输入。
 * 一个无状态的 {@code AudioUtils.play(channel, volume)} 会让这些状态无处安放。
 *
 * <h2>发声的产物是<b>一条世界事件</b>, 不是一次直接进入 Mind 的调用</h2>
 * 用户设计的边界是一条"类消息队列": <b>手机只是把刺激交给它所在的世界</b>,
 * 世界再经 {@code EventFabric} 送进 Human。所以 {@link #play} 的最后一件事是
 * {@code eventFabric.publish(...)}, <b>而不是</b>某个 {@code mind.hear(sound)}。
 *
 * <h2>一路通道一条事件 —— 本实现对 §4.3.3 的一处修正</h2>
 * 设计文档 §4.3.3 的示例里, {@code AudioSystem.play()} 无条件投
 * {@code device.phone.sound-emitted.v1}。而 {@code CoreEventCatalog} 里为三路通道
 * 各自定义了一条更具体的语义事件(通知/铃音/闹钟), 并且<b>通知那一条的载荷里就带着
 * 音量</b>({@code volume: double})。
 *
 * <p>如果两者都投, 结果是<b>她的耳朵里一次通知响了两声</b> —— 两条 B 类听觉刺激,
 * 而现实里只响了一声。所以本实现的规则是:
 *
 * <table border="1">
 *   <tr><th>{@link AudioChannel}</th><th>{@link #play} 投递的事件</th><th>为什么</th></tr>
 *   <tr>
 *     <td>{@code NOTIFICATION}</td>
 *     <td><b>不投</b>(交给 {@link NotificationSystem} 投
 *         {@code device.phone.notification-raised.v1})</td>
 *     <td>那条事件的载荷里还有"震了没有"和"几条信号", AudioSystem 不知道这两个数。
 *         让它投一条半成品、再让 NotificationSystem 投一条完整的, 就是两声</td>
 *   </tr>
 *   <tr>
 *     <td>{@code RINGTONE}</td>
 *     <td>{@code device.phone.ringtone-started.v1}</td>
 *     <td>目录里指定的生产者就是本系统</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ALARM}</td>
 *     <td>{@code device.phone.alarm-fired.v1}</td>
 *     <td>目录里写的是 {@code AlarmSystem}; 本设计不单独建那个类, 理由见
 *         {@link AlarmFired} 的注释 —— 闹钟不是一个"系统", 是"在 ALARM 通道上放一段声音"</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MEDIA}</td>
 *     <td>{@code device.phone.sound-emitted.v1}</td>
 *     <td>这一路没有被目录单独命名, 用的是 §4.3.3 给的通用条。
 *         目录自己的注释写着"<b>目录不是白名单, 只是已知清单</b>", 所以这不违反 P4</td>
 *   </tr>
 * </table>
 *
 * <p>这个分派写成一个 {@code switch} 是<b>允许的</b>: {@code AudioChannel} 是固定枚举
 * (见其类注释里三条判别), 分派的是硬件事实, 不是"世界上有哪些种类的应用"。
 * 第三方要扩展的不是这里 —— 它往哪一路发声, 由它自己的 {@code AudioStimulus} 决定。
 */
public interface AudioSystem {

    /**
     * 某一路通道的音量。
     *
     * <p>这是整条通知链上被读得最频繁的一个数: {@link NotificationSystem} 判断
     * "要不要响"时读它, {@link VibrationSystem} 判断"震不震"时参考它, 诊断面板显示它。
     */
    AudioVolume volumeOf(AudioChannel channel);

    /**
     * 调整一路音量 —— <b>她自己可以调</b>(这是一个 Action, 见 {@code device.phone.set-volume})。
     *
     * <p>之所以是一个 Action 而不是一个 setter: 用户明确要求设备的方法"可以以 skill、
     * 工具、MCP 等形式供 agent 使用"。她调音量是<b>她做的一件事</b>, 它该被审计、该失败
     * (手机没电时调不了)、该在她的记忆里留下痕迹。
     */
    void setVolume(AudioChannel channel, AudioVolume volume);

    /**
     * 现在是不是"全都静音"了。
     *
     * <p>静音模式 = 四路音量<b>全部</b>为 0, 但振动仍可能开着 —— 这两件事是正交的,
     * 合起来判断会得到"她把手机静音了所以既不出声也不震"这个错误结论。
     */
    boolean isSilent();

    /**
     * 让扬声器在<b>指定的仿真时刻</b>发出这个声音, <b>并以世界事件陈述它</b>。
     *
     * <h3>它刻意<b>返回</b>有效音量而不是 {@code void}</h3>
     * 文档草稿给的是 {@code void}, 但调用方几乎总要问"到底出声了没有、多大声" ——
     * 通知系统要把这个数写进 {@code notification-raised} 的载荷, 诊断面板要显示它。
     * 返回 {@code void} 会让每个调用方各写一遍
     * {@code if (volumeOf(ch).isMuted()) ... else ...}, 而其中一份迟早会漏掉
     * {@code baseVolume} 这一层乘法。
     *
     * <h3>为什么时刻是参数, 且<b>没有</b>无时刻的重载</h3>
     * 与 {@link VibrationSystem#vibrate(VibrationSystem.VibrationPattern, java.time.Instant)}
     * 是同一条纪律: <b>时刻是参数, 不是从系统时钟读出来的</b> ——
     * 否则一次时间加速的重放会得到与首次运行不同的时间戳。
     *
     * <p>这里曾经有过一个 {@code play(stimulus)} 重载, 它内部调的是
     * {@code Instant.now()}。它被<b>删掉</b>而不是"加注释保留", 因为一个便利重载
     * 总是会被用得比主路径多: 谁不想少传一个参数呢? 而它每被用一次,
     * 世界就多一条带墙上时钟的历史。删掉之后, 调用方手里的时刻来源是明确的 ——
     * {@link NotificationSystem} 有 {@code NotificationRequest.occurredAt()},
     * {@link Phone} 有这一 tick 的 {@code now}, 闹钟有它自己的到点时刻。
     *
     * @return 这次发声的<b>有效响度</b>(通道音量 × 基础音量)。若没出声则返回
     *         {@link AudioVolume#MUTED}, 且<b>不会有任何事件被投递</b>。
     *         "没发出声音 = 没有事件"这条纪律是刻意的: 一条
     *         "手机以 0 音量响了一下"的事件会让她在梦里被自己的手机吵醒
     */
    AudioVolume play(AudioStimulus stimulus, Instant at);

    /** 四路音量的快照 —— 给诊断面板与状态序列化用。 */
    Map<AudioChannel, AudioVolume> volumes();

    // ═══════════════════════════ 刺激 ═══════════════════════════

    /**
     * 一条要播放的音频刺激 —— <b>声学参数, 不含语义</b>。
     *
     * @param channel     走哪一路通道。它决定用哪个音量、以及世界看到哪条事件
     * @param soundProfile 音色标识, 如 {@code "chat-notification"} / {@code "alarm-clock"}。
     *                    <b>刻意是字符串而不是枚举</b>: 音色是<b>应用</b>的属性,
     *                    第三方应用要能自带一个平台不认识的音色(见 {@code DeviceApplication.Descriptor#notificationSound()})
     * @param sourceRef   这条声音"是关于什么的"坐标: 来电账号 id、闹钟标签、曲目 id。
     *                    <b>它绝不能是正文</b> —— 手机响的那一刻, 她本来也只知道"有声音"。
     *                    可以为空("媒体播放"就没有具体对象)
     * @param baseVolume  这个音色自身的基础响度, {@code [0, 1]}。有效响度 =
     *                    通道音量 × 本值。同一条通知用不同音色听起来响度不同, 这是真实行为
     * @param duration    响多久。<b>通知是短促的, 来电是持续的</b> —— 时长决定了她
     *                    有没有机会"回过神来去看"
     */
    record AudioStimulus(AudioChannel channel, String soundProfile, String sourceRef,
                         double baseVolume, Duration duration) {

        public AudioStimulus {
            Objects.requireNonNull(channel, "音频刺激必须指明通道 —— 通道决定用哪一路音量");
            soundProfile = soundProfile == null || soundProfile.isBlank() ? "default" : soundProfile;
            if (Double.isNaN(baseVolume) || baseVolume < 0 || baseVolume > 1) {
                throw new IllegalArgumentException(
                        "基础音量必须在 0..1 之间, 收到 " + baseVolume + " —— "
                                + "它是乘法链的一环, 越界会让有效响度越界");
            }
            duration = duration == null ? Duration.ofMillis(800) : duration;
            if (duration.isNegative()) {
                throw new IllegalArgumentException("音频时长不能为负, 收到 " + duration);
            }
        }

        public static AudioStimulus of(AudioChannel channel, String soundProfile,
                                       String sourceRef, double baseVolume, Duration duration) {
            return new AudioStimulus(channel, soundProfile, sourceRef, baseVolume, duration);
        }

        /**
         * 一条应用通知的声音。
         *
         * <p>默认时长 800ms, 基础音量 1.0 —— 应用的"通知音"本身是录好的一段短音,
         * 响度差别由<b>手机的通道音量</b>决定, 不由应用自说自话。
         */
        public static AudioStimulus notification(String soundProfile) {
            return new AudioStimulus(AudioChannel.NOTIFICATION, soundProfile, null,
                    1.0, Duration.ofMillis(800));
        }

        /** 来电铃声 —— <b>长</b>, 因为它不该自己停(真实的来电会一直响到被接或被挂)。 */
        public static AudioStimulus ringtone(String callerAccountId) {
            return new AudioStimulus(AudioChannel.RINGTONE, "ringtone-default", callerAccountId,
                    1.0, Duration.ofSeconds(30));
        }

        /** 闹钟 —— 长, 而且响到她自己按掉。 */
        public static AudioStimulus alarm(String alarmLabel) {
            return new AudioStimulus(AudioChannel.ALARM, "alarm-default", alarmLabel,
                    1.0, Duration.ofSeconds(60));
        }

        /** 媒体播放 —— 给第三方播放器用。 */
        public static AudioStimulus media(String trackRef, double baseVolume, Duration duration) {
            return new AudioStimulus(AudioChannel.MEDIA, "media-default", trackRef,
                    baseVolume, duration);
        }
    }

    // ═══════════════════════════ 事件 ═══════════════════════════

    /**
     * {@code device.phone.sound-emitted.v1} —— <b>扬声器发出了一个声音</b>(物理事实)。
     *
     * <p>它是"没有专属语义事件的那一路"的兜底, 当前由媒体播放使用。
     * 载荷里没有 {@code sourceRef} —— 因为她听见的只有声音; "那是谁打的电话"
     * 要等她<b>去看</b>才知道, 而那一步走的是 {@code chat.read-messages}。
     */
    @DomainType("device.phone.sound-emitted")
    record SoundEmitted(String phoneId, String channel, String soundProfile,
                        double effectiveVolume, long durationMillis, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "sound-emitted");

        public SoundEmitted {
            Objects.requireNonNull(phoneId, "声音事件必须说明是哪台设备发出的");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            channel = channel == null ? AudioChannel.MEDIA.name() : channel;
            soundProfile = soundProfile == null ? "default" : soundProfile;
        }

        public static SoundEmitted of(String phoneId, AudioChannel channel, String soundProfile,
                                      double effectiveVolume, Duration duration, Instant at) {
            return new SoundEmitted(phoneId, channel.name(), soundProfile, effectiveVolume,
                    duration.toMillis(), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        @Override
        public String modality() {
            return "auditory";
        }

        @Override
        public String describe() {
            return "设备 " + phoneId + " 以 " + AudioVolume.of(effectiveVolume)
                    + " 播放了 " + soundProfile;
        }
    }

    /**
     * {@code device.phone.ringtone-started.v1} —— <b>有人打电话来, 铃声响了</b>。
     *
     * <h2>它与通知的三点不同</h2>
     * <ol>
     *   <li><b>不会自己停</b> —— 所以 {@link #urgency()} 更高(0.85), 而且
     *       {@link #ongoing()} 为真、带 {@link #foldingKey()}: 持续响的铃声不该
     *       每一秒往队列里塞一条;</li>
     *   <li>走 {@code RINGTONE} 通道, 用的是她给铃声设的音量(通常比通知大);</li>
     *   <li>载荷里<b>有</b>来电账号 id —— 这不是泄露: 她本来就看得见来电显示。</li>
     * </ol>
     */
    @DomainType("device.phone.ringtone-started")
    record RingtoneStarted(String phoneId, String callerAccountId, double volume, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "ringtone-started");

        public RingtoneStarted {
            Objects.requireNonNull(phoneId, "铃声事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        public static RingtoneStarted of(String phoneId, String callerAccountId,
                                         double volume, Instant at) {
            return new RingtoneStarted(phoneId, callerAccountId, volume, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        @Override
        public String modality() {
            return "auditory";
        }

        @Override
        public double urgency() {
            return 0.85;
        }

        /** 铃声是持续的 —— 队列按 {@code (modality, 来源)} 折叠它。 */
        @Override
        public boolean ongoing() {
            return true;
        }

        @Override
        public String foldingKey() {
            return "ringtone:" + phoneId;
        }

        @Override
        public String describe() {
            return "设备 " + phoneId + " 来电铃声(" + (callerAccountId == null ? "未知来电" : callerAccountId)
                    + ") 音量 " + AudioVolume.of(volume);
        }
    }

    /**
     * {@code device.phone.alarm-fired.v1} —— <b>她自己设的闹钟响了</b>。
     *
     * <h2>为什么不单独建一个 {@code AlarmSystem}</h2>
     * {@code CoreEventCatalog} 把本事件的生产者写成 {@code world.device.phone.AlarmSystem},
     * 而 §8.4 的包结构里并没有这个类。本设计的判断是: <b>闹钟不是一个"系统",
     * 而是"在 ALARM 通道上放一段声音"</b> —— 它没有自己的硬件、没有自己的策略、
     * 也没有独立于音量的行为。为它建一个类会得到一个只做转发的空壳,
     * 而空壳的代价是"闹钟这件事到底谁负责"从此有两个答案。
     *
     * <p>闹钟的<b>调度</b>因此归 {@code Phone}(它知道世界时间), 发声归
     * {@link AudioSystem}(它知道音量), 事件陈述归这里。三者各一件事, 没有转发层。
     *
     * <p>{@code urgency} 给 0.9: 闹钟是<b>她自己要求被叫醒</b>的, 她不该错过它。
     * 这是全平台最高的几个值之一, 而它高得有理由 —— 与"火警 1.0"同一量级的语义。
     */
    @DomainType("device.phone.alarm-fired")
    record AlarmFired(String phoneId, String alarmLabel, double volume, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "alarm-fired");

        public AlarmFired {
            Objects.requireNonNull(phoneId, "闹钟事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            alarmLabel = alarmLabel == null || alarmLabel.isBlank() ? "闹钟" : alarmLabel;
        }

        public static AlarmFired of(String phoneId, String alarmLabel, double volume, Instant at) {
            return new AlarmFired(phoneId, alarmLabel, volume, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        @Override
        public String modality() {
            return "auditory";
        }

        @Override
        public double urgency() {
            return 0.9;
        }

        @Override
        public String describe() {
            return "设备 " + phoneId + " 闹钟响(" + alarmLabel + ") 音量 " + AudioVolume.of(volume);
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 默认实现 —— <b>四路音量 + 按通道分派世界事件</b>。
     *
     * <h2>为什么它是嵌套类而不是像 {@code DefaultEventFabric} 那样的独立文件</h2>
     * 因为 {@code world/device/} 的文件清单是设计文档 §8.4 定下的, 而本实现除了被
     * {@link Phone} 装配之外<b>没有第二个消费者</b>。一旦出现第二个实现
     * (蓝牙耳机没有 {@code RINGTONE} 通道、车机有自己的音量策略), 它就该升级成独立文件 ——
     * 那时"多一个文件"才是有信息的。
     *
     * <h2>线程模型</h2>
     * 与 {@code Capability} 的约定一致: 同一个 agent 的能力调用是串行的(单线程 tick),
     * 所以这里<b>不加锁</b>。一个需要锁的设备说明它的状态被两个 agent 共享了,
     * 而那本身就是设计错误。
     *
     * <h2>它依赖的是 {@code EventFabric}, 而<b>不是</b> {@code World} 聚合根</h2>
     * 这是本实现里一处刻意的依赖方向选择。手机是 {@code World} 持有的零件,
     * 让零件回头抓聚合根会形成<b>双向依赖</b> —— 而双向依赖的第一个后果就是
     * ArchUnit 的边界规则会在将来某一天炸掉, 第二个后果更实际:
     * 想单独测试"手机没电时通知还响不响"就必须先造一个完整的世界。
     *
     * <p>而 {@code EventFabric} 是<b>边界层</b>的接口({@code boundary/event}),
     * 它的类注释写得很清楚: "世界侧通往 Human 的唯一入口 …… 世界侧要投递时,
     * 先知道自己投给谁 —— 这也正是 {@code humanId()} 存在的理由"。
     * 也就是说, 一部<b>属于她的</b>手机拿到的正是她那一条通道 —— 语义完全对得上,
     * 依赖方向也正确({@code world → boundary}, 没有环)。
     *
     * <p>真正需要聚合根的那些事(有哪些设备、推进谁、注册哪些能力)留在
     * {@code World} 那一侧, 设备只管"陈述发生在自己身上的事"。
     */
    @Slf4j
    final class Default implements AudioSystem {

        private final EventFabric eventFabric;
        private final String phoneId;
        private final Map<AudioChannel, AudioVolume> volumes = new EnumMap<>(AudioChannel.class);

        /**
         * @param eventFabric    这台设备所属的那个人的世界事件通道。设备只往这里投事件,
         *                       不持有整个世界 —— 见类注释
         * @param initialVolumes 初始四路音量。装配代码通常给
         *                       {@link AudioChannel#typicalLevel()} 对应的值 ——
         *                       一个"出厂默认"的手机, 而不是一台全部静音的手机
         */
        public Default(EventFabric eventFabric, String phoneId,
                       Map<AudioChannel, AudioVolume> initialVolumes) {
            this.eventFabric = Objects.requireNonNull(eventFabric,
                    "音频系统必须有一个投递通道 —— 发声要有地方陈述成世界事件");
            this.phoneId = Objects.requireNonNull(phoneId, "音频系统必须知道自己在哪台设备上");
            // 每一路都必须有初值: 一个"没设过的通道"在 play 时会读到 null,
            // 而那个 NPE 会出现在"她第一次收到消息"这条最重要的路径上
            for (AudioChannel channel : AudioChannel.values()) {
                AudioVolume volume = initialVolumes == null ? null : initialVolumes.get(channel);
                volumes.put(channel, volume != null ? volume : AudioVolume.of(channel.typicalLevel()));
            }
        }

        @Override
        public AudioVolume volumeOf(AudioChannel channel) {
            Objects.requireNonNull(channel, "音频通道不能为空");
            return volumes.get(channel);
        }

        @Override
        public void setVolume(AudioChannel channel, AudioVolume volume) {
            Objects.requireNonNull(channel, "音频通道不能为空");
            Objects.requireNonNull(volume, "音量不能为空 —— 想静音请用 AudioVolume.MUTED");
            AudioVolume previous = volumes.put(channel, volume);
            // INFO 而不是 DEBUG: 改音量是"她做的一件事", 而"她为什么没听见"这个问题
            // 的答案十次里有三次就在这条日志里
            log.info("[AudioSystem/{}] {} 从 {} 调到 {}", phoneId, channel.label(), previous, volume);
        }

        @Override
        public boolean isSilent() {
            return volumes.values().stream().allMatch(AudioVolume::isMuted);
        }

        @Override
        public AudioVolume play(AudioStimulus stimulus, Instant at) {
            Objects.requireNonNull(stimulus, "音频刺激不能为空");
            Objects.requireNonNull(at, "发声必须带时刻 —— 仿真时钟下不许读墙上时钟");
            AudioVolume channelVolume = volumeOf(stimulus.channel());

            // ① 没音量就没声音 —— 也就不该有任何事件。见 play 的 @return 说明:
            //    "手机以 0 音量响了一下"不该把她吵醒
            if (channelVolume.isMuted() || stimulus.baseVolume() <= 0) {
                log.debug("[AudioSystem/{}] {} 通道音量为 {}, 不发声也不投事件",
                        phoneId, stimulus.channel().label(), channelVolume);
                return AudioVolume.MUTED;
            }

            // ② 有效响度 = 通道音量 × 音色基础音量
            AudioVolume effective = channelVolume.scaled(stimulus.baseVolume());

            // ③ 按通道陈述世界事件 —— 一路通道一条, 见接口注释里的表
            switch (stimulus.channel()) {
                case NOTIFICATION ->
                    // 刻意什么都不投: 这一路的事件由 NotificationSystem 投,
                    // 因为它还要带上"震了没有"与"几条信号"。两条都投 = 她听到两声
                        log.debug("[AudioSystem/{}] 通知音已发出({}), 事件由 NotificationSystem 陈述",
                                phoneId, effective);
                case RINGTONE -> eventFabric.publish(RingtoneStarted.of(
                        phoneId, stimulus.sourceRef(), effective.level(), at));
                case ALARM -> eventFabric.publish(AlarmFired.of(
                        phoneId, stimulus.sourceRef(), effective.level(), at));
                case MEDIA -> eventFabric.publish(SoundEmitted.of(
                        phoneId, stimulus.channel(), stimulus.soundProfile(),
                        effective.level(), stimulus.duration(), at));
            }
            return effective;
        }

        @Override
        public Map<AudioChannel, AudioVolume> volumes() {
            return Map.copyOf(volumes);
        }

        /** 四路音量的一行摘要 —— 诊断面板与日志用。 */
        public String describeVolume() {
            return AudioChannel.NOTIFICATION.label() + " " + volumeOf(AudioChannel.NOTIFICATION)
                    + " / " + AudioChannel.RINGTONE.label() + " " + volumeOf(AudioChannel.RINGTONE)
                    + " / " + AudioChannel.ALARM.label() + " " + volumeOf(AudioChannel.ALARM)
                    + " / " + AudioChannel.MEDIA.label() + " " + volumeOf(AudioChannel.MEDIA);
        }

        /** 平台认识的全部通道 —— 给能力描述与诊断用。 */
        public static List<AudioChannel> channels() {
            return List.of(AudioChannel.values());
        }
    }

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>本接口的三条事件, 由本接口自己登记</b> —— 装配层调用。
     *
     * <h2>为什么是三条一起, 而不是每一条各登记各的</h2>
     * 因为"哪一路通道投哪一条事件"这张表就在本文件的类注释里, 而它是<b>互斥的五选三</b>:
     * 通知音由 {@code NotificationSystem} 投(那里还要带上"震了没有"), 剩下三路
     * 各自一条。分开登记不会让任何一处变清楚 —— 这三条的类型名与它们的载荷形状
     * 本来就只在这一个文件里, 而它们必须一起被读懂(见 {@code emit} 里那个 switch)。
     *
     * <p>换句话说: 这里登记的不是"三条恰好同在一处的类型", 而是
     * <b>扬声器这一个子系统能陈述的全部事实</b>。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条。可重复调用: 同一个类登记两次是一次空操作
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(SoundEmitted.class);
        registry.register(RingtoneStarted.class);
        registry.register(AlarmFired.class);
        return 3;
    }
}
