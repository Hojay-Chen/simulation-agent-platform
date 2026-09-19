package com.luxera.companion.world.device;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.boundary.event.EventFabric;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.4 —— <b>通知系统</b>: 收到一个通知请求, 按本机策略决定怎么响。
 *
 * <h2>它是 §2.2 那条链的第 ⑦ 步</h2>
 * <pre>
 *   ①用户发消息  ②聊天平台落库+免打扰判定  ③产生 NotificationSignal(<b>无正文</b>)
 *   ④WebSocket 推到 ChatApplication        ⑤ChatApplication 收到
 *   ⑥交给 Phone ──→ <b>本接口 receive()</b>  ⑦按本机策略: 声音/振动/亮屏
 *   ⑧投一条"手机响了"的世界事件(仍无正文)   ⑨EventFabric → Human
 * </pre>
 *
 * <h2>逐条通知, <b>不聚合</b></h2>
 * 用户对这条给了明确要求:
 * <blockquote>
 *   每一条消息都会根据聊天软件自己的通知策略进行通知, 常见的聊天软件都是每条消息都通知,
 *   而不是把多条消息聚合成一条消息来通知
 * </blockquote>
 * 所以本接口的 {@link #receive} 是<b>一次一条</b>的, 没有任何"攒够 N 条再响一次"的入口。
 * 常见的"你收到 3 条新消息"式优化在这里是<b>错误的</b>: 它会让"她手机响了几下"这个
 * 可观测的事实被抹掉, 而"她为什么没注意到第三条"这类问题从此无法回答。
 *
 * <h2>被策略拦下时必须留下痕迹</h2>
 * 通知没响时本实现会投 {@code device.notification-dropped.v1}。
 * <b>她感知不到它, 但行为分析需要它</b> —— 没有它, "她没回消息"会被推断成"她不在乎",
 * 而真相是"她把手机静音了"。这与 {@code RealtimeEventQueue} 丢弃刺激时记一条
 * {@code system.stimulus-dropped.v1} 是同一个论证。
 *
 * <h2>未读数与"响不响"是两件事</h2>
 * 静音时通知<b>不响但未读仍然 +1</b> —— 她打开聊天软件时会看到红点。
 * 把两者绑在一起(静音就不计数)会得到一个会丢消息的手机, 那不是真实行为。
 */
public interface NotificationSystem {

    /**
     * 收到一个通知请求 —— <b>是否真的响由本机的策略决定</b>(见 {@link NotificationPolicy})。
     *
     * <p>实现必须保证: 无论响不响, 未读计数都要 +1。
     */
    void receive(NotificationRequest request);

    /** 当前策略。 */
    NotificationPolicy policy();

    /** 她(或一个 Action)改了策略 —— 例如"开会了, 调成静音"。 */
    void setPolicy(NotificationPolicy policy);

    /** 某个应用名下还没看的通知数 —— 她打开应用时看到的那个红点。 */
    int unreadCountOf(String applicationKey);

    /** 全部未读。 */
    int totalUnread();

    /**
     * 清掉某个应用的未读 —— 她<b>真的看了</b>之后才清。
     *
     * <p>刻意由调用方显式调用而不是在本接口内自动清: "手机响了"和"她看了"之间隔着
     * 一次 {@code chat.read-messages}, 而那次调用发生在<b>另一个对象</b>上。
     * 自动清未读等于替她做了"已读"这个决定。
     */
    void clearUnread(String applicationKey);

    // ═══════════════════════════ 需要的手机状态 ═══════════════════════════

    /**
     * 通知系统需要从手机上读到的两件事: <b>位置</b>与<b>电量</b>。
     *
     * <h2>为什么是一个小接口而不是两个 {@code Supplier}</h2>
     * 因为这两个数回答的是同一个问题 —— "这条通知到得了她那里吗": 手机在另一个房间
     * (位置)或手机没电了(电量)。用一个有名字的接口, 装配代码里写的是
     * {@code new NotificationSystem.Default(eventFabric, phoneId, this, ...)} 而不是
     * {@code new Default(eventFabric, phoneId, this::proximity, this::power, ...)} ——
     * 后者在阅读时无法回答"这两个参数是什么关系"。
     *
     * <p>由 {@code Phone} 实现本接口, 于是"手机的状态"这件事只有一处真相。
     */
    interface SignalContext {

        /** 手机现在离她多近 —— 取值见 {@link Device.Proximity}。 */
        String proximity();

        /** 手机还有电吗 —— 关机的手机不会响。 */
        Device.PowerState power();
    }

    // ═══════════════════════════ 事件 ═══════════════════════════

    /**
     * {@code device.phone.notification-raised.v1} —— <b>手机因为某条通知信号而响了</b>。
     *
     * <h2>载荷里没有正文, 也没有"谁发的"</h2>
     * 这不是疏忽, 是设计的结果: 产生它的那一刻, 世界里根本不存在这两个信息 ——
     * 通知信号本身就不带正文, 而会话坐标被刻意拦在了本事件之外。
     * <b>那是一部真手机在响时她所知道的全部。</b>
     *
     * <p>用户的设计意图: 「平台不需要向 agent 暴露用户的备注体系」, 而"是谁发的"
     * 属于她自己构建的关系网({@code RelationshipGraph.resolve(accountId)}),
     * 它只能在她<b>主动去看</b>之后才登场。
     *
     * <h2>关于 {@code perceptibility} 这个字段</h2>
     * {@code CoreEventCatalog} 里本事件的载荷是
     * {@code phoneId, applicationKey, volume, vibration, signalCount}。本实现<b>多加了一个</b>
     * {@code perceptibility}, 理由是一条会被真实触发的场景:
     * <pre>
     *   她睡觉时把手机放在客厅(proximity = "other-room")
     *   → 手机响了, 但隔了两道墙
     *   → 如果载荷里只有 volume, 下游只能认为"她一定听见了"
     *   → 于是"她没回消息"永远无法被解释成"她没听见"
     * </pre>
     * {@code EventTypeId} 自己的规则写着"<b>加一个可选字段不升版本</b>", 所以这是一次
     * 向后兼容的补充 —— 但目录里的载荷说明<b>应当被同步更新</b>(这是实现时发现的一处
     * 文档与实现的分叉, 已记录在交付说明里)。
     */
    @DomainType("device.phone.notification-raised")
    record NotificationRaised(String phoneId,
                              String applicationKey,
                              double volume,
                              boolean vibration,
                              int signalCount,
                              double perceptibility,
                              Instant occurredAt) implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "notification-raised");

        public NotificationRaised {
            Objects.requireNonNull(phoneId, "通知事件必须说明是哪台设备在响");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            applicationKey = applicationKey == null ? "unknown" : applicationKey;
            if (perceptibility < 0 || perceptibility > 1) {
                throw new IllegalArgumentException(
                        "感知因子必须在 0..1 之间, 收到 " + perceptibility);
            }
        }

        public static NotificationRaised of(String phoneId, String applicationKey, double volume,
                                            boolean vibration, int signalCount,
                                            double perceptibility, Instant at) {
            return new NotificationRaised(phoneId, applicationKey, volume, vibration,
                    signalCount, perceptibility, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        /**
         * 出过声就是听觉刺激, 只震过就是触觉刺激 —— <b>通道随事实走</b>。
         *
         * <h3>为什么不能固定写 {@code "auditory"}</h3>
         * "静音+振动"是她手机上最常见的一种状态(开会时人人如此)。如果把这种情形下的事件
         * 标成听觉, 行为分析会看到一条"听觉刺激"而她毫无反应 —— 于是得出的结论是
         * "她听觉迟钝"或"她故意不理", 而真相是<b>那台手机根本没出声</b>。
         * 通道是一次事实声明, 事实变了它就得跟着变。
         *
         * <p>也不会出现"既是听觉又是触觉"的情形: 声与震同时发生时, 她的注意力首先被
         * 声音抓走, 而队列里一条刺激只能有一个通道 —— 硬要表达两者会让
         * {@code RealtimeEventQueue} 的去重与折叠逻辑失去意义。
         */
        @Override
        public String modality() {
            return volume > 0 ? "auditory" : "tactile";
        }

        /**
         * 0.55 —— 比默认(0.5)略高。
         *
         * <p>理由: 通知是<b>有人主动找她</b>, 而这件事天生比"环境里的一个声音"更值得看一眼。
         * 但它远低于来电(0.85): 一条聊天消息可以等, 一个电话通常不能。
         *
         * <p>刻意<b>不</b>按"是不是重要的人发的"调整 —— 那个信息在事件里根本不存在,
         * 而且那是 salience 的事, 由 Mind 结合处境算(§3.4.4)。
         */
        @Override
        public double urgency() {
            return 0.55;
        }

        /**
         * <b>不折叠</b> —— 每条通知都是独立的一次响声。
         *
         * <p>{@code foldingKey} 保持 {@code null} 是刻意的: 用户明确要求逐条通知。
         * 若按应用折叠, "她连发五条消息"会在队列里变成一条, 而现实里手机响了五次。
         */
        @Override
        public String foldingKey() {
            return null;
        }

        @Override
        public String describe() {
            return "手机 " + phoneId + " 因 " + applicationKey + " 响了一声(音量 "
                    + AudioVolume.of(volume) + (vibration ? ", 同时震动" : "") + ", 感知 "
                    + perceptibility + ")";
        }
    }

    /**
     * {@code device.notification-dropped.v1} —— <b>通知被本机策略拦下了</b>(静音/没电)。
     *
     * <p>她感知不到这条事件(它的 urgency 刻意给 0.2, 意味着它排在几乎所有刺激后面),
     * 但行为分析需要它。见接口注释"被策略拦下时必须留下痕迹"。
     */
    @DomainType("device.notification-dropped")
    record NotificationDropped(String phoneId, String reason, int droppedCount, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device", "notification-dropped");

        public NotificationDropped {
            Objects.requireNonNull(phoneId, "丢弃事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
            droppedCount = Math.max(1, droppedCount);
        }

        public static NotificationDropped of(String phoneId, String reason, int count, Instant at) {
            return new NotificationDropped(phoneId, reason, count, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        /**
         * 触觉而不是听觉 —— 这条事件<b>没有声音</b>, 她听不到它。
         *
         * <p>给它一个听觉通道会污染"她刚才听见了什么"的可观测事实:
         * 行为分析会看到"有一条听觉刺激但她没反应", 从而推断"她听力有问题",
         * 而真相是那条刺激从来没响过。通道的选择在这里是一次<b>事实声明</b>。
         */
        @Override
        public String modality() {
            return "tactile";
        }

        /** 0.2 —— 它几乎不该打断她, 它的价值在事后分析里。 */
        @Override
        public double urgency() {
            return 0.2;
        }

        @Override
        public String describe() {
            return "手机 " + phoneId + " 的通知被拦下(" + reason + "), 累计 " + droppedCount;
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 默认实现: <b>读策略 → 决定响法 → 投一条事件</b>。
     *
     * <h2>为什么它是嵌套类</h2>
     * 与 {@link AudioSystem.Default} 同一个理由: 文件清单由 §8.4 定死, 而这个实现
     * 目前只有 {@link Phone} 一个消费者。出现第二个实现时它就该独立成文件。
     */
    @Slf4j
    final class Default implements NotificationSystem {

        private final EventFabric eventFabric;
        private final String phoneId;
        private final AudioSystem audio;
        private final VibrationSystem vibration;
        private final ScreenSystem screen;
        private final SignalContext signalContext;

        /** 应用 key → 未读数。用 LinkedHashMap 是为了诊断面板上的顺序稳定。 */
        private final Map<String, Integer> unread = new LinkedHashMap<>();

        private NotificationPolicy policy;

        public Default(EventFabric eventFabric, String phoneId, AudioSystem audio,
                       VibrationSystem vibration, ScreenSystem screen,
                       SignalContext signalContext) {
            this(eventFabric, phoneId, audio, vibration, screen, signalContext,
                    NotificationPolicy.DEFAULT);
        }

        public Default(EventFabric eventFabric, String phoneId, AudioSystem audio,
                       VibrationSystem vibration, ScreenSystem screen,
                       SignalContext signalContext, NotificationPolicy policy) {
            this.eventFabric = Objects.requireNonNull(eventFabric,
                    "通知系统必须有一个投递通道 —— 手机响了这件事要有地方陈述");
            this.phoneId = Objects.requireNonNull(phoneId, "通知系统必须知道自己在哪台设备上");
            this.audio = Objects.requireNonNull(audio, "通知系统必须能读到音频系统 —— 响不响看它");
            this.vibration = Objects.requireNonNull(vibration, "通知系统必须能读到振动系统");
            this.screen = Objects.requireNonNull(screen, "通知系统必须能读到屏幕系统");
            this.signalContext = Objects.requireNonNull(signalContext, "通知系统必须能读到手机的位置与电量");
            this.policy = Objects.requireNonNull(policy, "初始通知策略不能为空");
        }

        // ─────────────────────────── 收到通知 ───────────────────────────

        @Override
        public void receive(NotificationRequest request) {
            Objects.requireNonNull(request, "通知请求不能为空");
            String app = request.applicationKey();
            Instant now = request.occurredAt();

            // ① 手机没电 = 它根本不会响。这不是"策略拦下", 是一次真实的物理不可用,
            //    所以理由记成 no-power 而不是 muted —— 两者的解释完全不同
            if (!signalContext.power().usable()) {
                countUnread(app);
                publishDropped(app, "no-power", now);
                return;
            }

            NotificationPolicy current = policy;
            boolean willSound = current.soundAllowed()
                    && audio.volumeOf(AudioChannel.NOTIFICATION).audible();
            boolean willVibrate = current.vibrationAllowed() && vibration.isEnabled();

            // ② 声和震都不行 —— 通知在她那里等于没发生, 但未读仍然 +1
            if (!willSound && !willVibrate) {
                countUnread(app);
                publishDropped(app, current.ringer() == NotificationPolicy.RingerMode.SILENT
                        ? "silent" : "no-sound-and-no-vibration", now);
                return;
            }

            // ③ 出声 —— 注意 AudioSystem 对 NOTIFICATION 通道<b>不投事件</b>,
            //    它只负责让扬声器真的响; 世界看到的那条由下面第 ⑥ 步陈述
            AudioVolume effective = AudioVolume.MUTED;
            if (willSound) {
                // 传 now 而不是让 AudioSystem 自己读时钟 —— 见 play(stimulus, at) 的说明
                effective = audio.play(AudioSystem.AudioStimulus.notification(request.soundProfile()), now);
            }

            // ④ 震动 —— 静音模式下她仍然可能通过震动察觉, 这是真实手机的行为。
            //    把 now 传进去而不是让它自己去读时钟: 见 VibrationSystem.vibrate 的说明
            if (willVibrate) {
                vibration.vibrate(VibrationSystem.VibrationPattern.SHORT_DOUBLE, now);
            }

            // ⑤ 亮屏 —— 与响铃模式无关: 静音时屏幕照样会亮, 而她可能正好在看。
            //    注意这里只亮屏<b>不解锁</b>: 锁屏上只看得到"某应用来了通知", 看不到正文,
            //    那正是"通知不携带内容"在物理设备上的对应物
            if (current.wakeScreenAllowed() && screen.isLocked()) {
                screen.wake(ScreenSystem.Reasons.NOTIFICATION, now);
            }

            // ⑥ 未读 +1, 并投出"手机响了"这一条 —— 它<b>不含正文, 也不含谁发的</b>
            int total = countUnread(app);
            eventFabric.publish(NotificationRaised.of(phoneId, app, effective.level(), willVibrate,
                    total, perceptibilityOf(willSound, willVibrate), now));
        }

        private int countUnread(String applicationKey) {
            // 未读是"她还没看"的计数, 与响不响无关 —— 见接口注释
            return unread.merge(applicationKey, 1, Integer::sum);
        }

        /**
         * 这次响动到她那里还剩多少 —— <b>取声与震里更强的那个</b>, 而不是两者相加。
         *
         * <h2>为什么不是相加</h2>
         * 相加会得到"响了 + 震了 = 1.75"这种越界的数; 即使归一化, 它表达的也是
         * "两倍容易被察觉", 而注意力不是这么工作的 —— 她要么被声音抓走, 要么被震动抓走,
         * 不会被"两倍"抓走。{@code max} 表达的是"至少有一条通道送到了"。
         *
         * <p>这个值直接进 {@code notification-raised} 的 {@code perceptibility} 字段,
         * 而下游(行为分析、"她为什么没看见"的解释器)读的就是它。
         */
        private double perceptibilityOf(boolean sounded, boolean vibrated) {
            String proximity = signalContext.proximity();
            double bySound = sounded ? Device.Proximity.factorOf(proximity) : 0.0;
            double byVibration = vibrated ? vibration.perceptibility(proximity) : 0.0;
            return Math.max(bySound, byVibration);
        }

        private void publishDropped(String applicationKey, String reason, Instant at) {
            int count = unreadCountOf(applicationKey);
            log.debug("[NotificationSystem/{}] {} 的通知被拦下({}), 未读仍为 {}",
                    phoneId, applicationKey, reason, count);
            eventFabric.publish(NotificationDropped.of(phoneId, reason + ":" + applicationKey, count, at));
        }

        // ─────────────────────────── 策略与未读 ───────────────────────────

        @Override
        public NotificationPolicy policy() {
            return policy;
        }

        @Override
        public void setPolicy(NotificationPolicy newPolicy) {
            Objects.requireNonNull(newPolicy, "通知策略不能为空 —— 想静音请用 RingerMode.SILENT");
            NotificationPolicy previous = this.policy;
            this.policy = newPolicy;
            // INFO: "她把手机调成静音了"是解释"她为什么没回应"的关键事实之一
            log.info("[NotificationSystem/{}] 通知策略从 {} 变为 {}",
                    phoneId, previous.describe(), newPolicy.describe());
        }

        @Override
        public int unreadCountOf(String applicationKey) {
            return unread.getOrDefault(applicationKey, 0);
        }

        @Override
        public int totalUnread() {
            return unread.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void clearUnread(String applicationKey) {
            Integer removed = unread.remove(applicationKey);
            if (removed != null && removed > 0) {
                log.debug("[NotificationSystem/{}] {} 的 {} 条未读已清零", phoneId, applicationKey, removed);
            }
        }

        /**
         * 未读快照 —— 给诊断面板与状态序列化用。
         *
         * <p>刻意用 {@code LinkedHashMap} + {@code Collections.unmodifiableMap} 而不是
         * {@code Map.copyOf}: 后者不保证迭代顺序, 而"哪个应用的未读在前"在诊断面板上
         * 会被当成变化。
         */
        public Map<String, Integer> unreadSnapshot() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(unread));
        }
    }

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>通知子系统的两条事件, 由本接口自己登记</b> —— 装配层调用。
     *
     * <h2>为什么是这两条一起</h2>
     * 因为它们是同一个决定的两种结局: {@link NotificationRaised}(响了)与
     * {@link NotificationDropped}(被策略拦下了)。只登记前者会让"她为什么没听见"
     * 这个问题在重启之后完全失踪 —— 而静音、免打扰、深夜策略都是<b>正常行为</b>,
     * 不是异常, 它们必须与"响了"一样可读回来。
     *
     * <h2>为什么这条清单不能靠"扫 device.phone 前缀"推出来</h2>
     * 因为两条的名字不在同一个命名空间下: {@code device.phone.notification-raised}
     * 是"这一台手机响了", 而 {@code device.notification-dropped} 是"通知这件事被拦下了"
     * —— 后者对音箱、手表、未来的任何通知源都成立, 所以它少一层 {@code phone}。
     * 按前缀猜的装配会安静地漏掉它, 于是"她那天群里 200 条消息为什么一条都没打扰她"
     * 只剩一个"未读 200"的数字, 没有那 200 条是怎么被挡住的记录。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条。可重复调用: 同一个类登记两次是一次空操作
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(NotificationRaised.class);
        registry.register(NotificationDropped.class);
        return 2;
    }
}
