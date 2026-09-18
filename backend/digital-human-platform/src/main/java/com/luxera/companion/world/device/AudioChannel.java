package com.luxera.companion.world.device;

/**
 * V2.2 §4.3.3 —— <b>手机的四路音频通道</b>。
 *
 * <h2>为什么这里<b>允许</b>用枚举</h2>
 * 设计文档 P4 的禁令是"不许用编译期 enum 做<b>领域扩展机制</b>", 不是"不许有枚举"。
 * 本枚举落在 P4 明确允许的那一类: <b>固定基础设施</b>。判别标准是三条, 本枚举三条全中:
 * <table border="1">
 *   <tr><th>判别问题</th><th>本枚举的答案</th></tr>
 *   <tr>
 *     <td>新增一个取值会改变 Agent 能做什么吗?</td>
 *     <td><b>不会。</b>她不会因为手机多了一路"低音炮音量"而多出一种行为</td>
 *   </tr>
 *   <tr>
 *     <td>这个集合是硬件事实还是领域事实?</td>
 *     <td><b>硬件事实。</b>iOS 与 Android 的铃声设置里就是这四路(通知/铃声/闹钟/媒体),
 *         它们来自 {@code AVAudioSession} / {@code AudioManager} 的既有分类, 不是我们发明的</td>
 *   </tr>
 *   <tr>
 *     <td>第三方需要往这里加成员吗?</td>
 *     <td><b>不需要。</b>第三方加的是"手机应用", 而应用不能新增音频通道 ——
 *         它只能选择往哪一路发声</td>
 *   </tr>
 * </table>
 *
 * <p>反面对照: {@code DeviceType} / {@code ApplicationType} / {@code NotificationKind}
 * 三条禁令全踩 —— 加一种设备就是加一种 Agent 能做的事, 而且第三方必须能加。
 * 那种东西在本设计里只能是"接口 + {@code @DomainType} + 注册表"。
 *
 * <h2>四路的真实差异不是"名字不同"</h2>
 * 它们的差异体现在三处, 每一处都在代码里被真的用到:
 * <ol>
 *   <li><b>默认值不同</b>({@link #typicalLevel()}) —— 她自己设闹钟时不会设太小声,
 *       所以闹钟通道的典型值是 0.9 而通知是 0.6;</li>
 *   <li><b>世界看到的事件不同</b> —— 通知走 {@code device.phone.notification-raised.v1},
 *       铃音走 {@code ringtone-started.v1}, 闹钟走 {@code alarm-fired.v1},
 *       媒体走 {@code sound-emitted.v1}。见 {@link AudioSystem.Default#play} 里的通道分派;</li>
 *   <li><b>静音语义不同</b> —— 静音模式关掉前两路, 但闹钟通常<b>仍然会响</b>
 *       (真实手机的行为)。这个差异由 {@link NotificationPolicy} 表达, 不在本枚举里。</li>
 * </ol>
 */
public enum AudioChannel {

    /**
     * 软件通知音量 —— 收到聊天消息、应用推送时走这一路。
     *
     * <p>它是整条"她能不能听见消息"链路里被读得最频繁的那个数:
     * {@code NotificationSystem} 判断"要不要响"时读的就是它。
     */
    NOTIFICATION("通知音量", 0.6),

    /** 铃声音量 —— 来电。它与通知的区别是<b>不会自己停</b>。 */
    RINGTONE("铃声音量", 0.8),

    /**
     * 闹钟音量 —— 她自己设的闹钟。
     *
     * <p>典型值最高(0.9)不是巧合: 一个会把自己叫不醒的闹钟是没用的, 所以真人会把它调大。
     * 这个"她自己的选择"落在 {@code Alarm} 的默认音量上, 而不是落在代码常量里。
     */
    ALARM("闹钟音量", 0.9),

    /** 媒体音量 —— 音乐、视频、语音消息的播放。 */
    MEDIA("媒体音量", 0.7);

    private final String label;
    private final double typicalLevel;

    AudioChannel(String label, double typicalLevel) {
        this.label = label;
        this.typicalLevel = typicalLevel;
    }

    /** 中文标签 —— 给诊断面板与 LLM 的能力描述用。 */
    public String label() {
        return label;
    }

    /**
     * 这一路的典型音量。
     *
     * <p>它<b>不是默认值</b> —— 默认值是 {@code Phone} 在装配时给的, 而这个是"真人大致会怎么设"
     * 的参考, 供诊断面板显示"她的通知音量 0.6, 属于正常"以及给测试一个合理的起点。
     * 把两者混起来会得到"用户改过音量之后, 系统认为她改错了"这种荒谬的行为。
     */
    public double typicalLevel() {
        return typicalLevel;
    }

    /** 是不是"打断性"的通道 —— 通知、来电、闹钟会打断她, 媒体播放不打断。 */
    public boolean interruptive() {
        return this != MEDIA;
    }

    /**
     * 按名字找一路通道 —— 大小写不敏感。
     *
     * <p>存在的理由是<b>能力调用来自 LLM</b>: 它会把 {@code "notification"} /
     * {@code "NOTIFICATION"} / {@code "通知音量"} 都试一遍。用 {@code valueOf} 的话,
     * 一次大小写不对就是一个 500(或者一个被吞掉的异常), 而真相只是"它写成了小写"。
     *
     * <p>返回 {@code Optional} 而不是抛: "不认识这个通道"是一个<b>可以如实回报</b>的
     * 结果({@code ActionResult.rejected}), 不是程序故障 —— 见 {@code Capability} 的注释。
     */
    public static java.util.Optional<AudioChannel> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = raw.trim();
        for (AudioChannel channel : values()) {
            if (channel.name().equalsIgnoreCase(trimmed) || channel.label.equals(trimmed)) {
                return java.util.Optional.of(channel);
            }
        }
        return java.util.Optional.empty();
    }

    /** 平台认识的全部通道 key —— 拼进能力描述里给 LLM 看。 */
    public static java.util.List<String> knownKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (AudioChannel channel : values()) {
            keys.add(channel.name().toLowerCase(java.util.Locale.ROOT));
        }
        return java.util.List.copyOf(keys);
    }
}
