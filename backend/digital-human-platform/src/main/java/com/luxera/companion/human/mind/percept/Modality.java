package com.luxera.companion.human.mind.percept;

import com.luxera.companion.registry.CoreEventCatalog;

import java.util.Optional;

/**
 * V2.2 §3.4.3 / §5.4.7 —— <b>一次感知走的是哪条感官通道</b>。
 *
 * <h2>先说清楚: 这里为什么是一个合法的 enum</h2>
 * 这个代码库里"能不能用 enum"只有一条判据(§1.3 P4, 在 {@code DomainType} 与
 * {@link com.luxera.companion.registry.CoreEventCatalog.Category} 的 javadoc 里各写过一遍):
 *
 * <blockquote>
 *   这个东西的取值集合是由<b>本设计的内部逻辑</b>决定的, 还是由<b>外部世界的多样性</b>决定的?
 * </blockquote>
 *
 * <p>由外部多样性决定的不能是 enum —— 因为"第三方要加一种, 得改我们的源码"就是失败。
 * 那么感官通道属于哪一种? <b>属于前者, 而且比 {@code Category} 还要更硬</b>:
 *
 * <table border="1">
 *   <tr><th>候选</th><th>取值集合由谁决定</th><th>能不能是 enum</th></tr>
 *   <tr>
 *     <td>事件类型</td><td>世界上会发生什么事 —— 无穷</td>
 *     <td>❌ 这就是 {@code EventTypeId} 必须是值对象的理由</td>
 *   </tr>
 *   <tr>
 *     <td>计划种类</td><td>她想做什么 —— 无穷</td>
 *     <td>❌ 这就是 {@code PlanIntent} 是接口的理由</td>
 *   </tr>
 *   <tr>
 *     <td><b>感官通道</b></td>
 *     <td><b>人的身体 —— 恰好五种。不是"我们平台支持五种", 是"人只有五种"</b></td>
 *     <td>✅</td>
 *   </tr>
 * </table>
 *
 * <p>这条论证必须写清楚, 否则下一个人会拿同一句话去论证
 * {@code Modality} 也该是值对象 —— 而那是错的。判据问的不是"这个集合会不会变大",
 * 问的是"<b>变大的话, 是外部世界变了, 还是我们的设计变了</b>"。
 * 世界上新增一亿种设备, 也不会让人长出第六种感官; 而如果真有人接入了
 * 一种新传感器(比如磁感应), 那不是"第三方扩展", 那是<b>修改"人"的定义</b> ——
 * 它必须改平台的源码, 这是正确的, 因为"人有没有磁感应"不是第三方能替我们回答的问题。
 *
 * <h2>那为什么 {@code boundary} 那侧用的是 {@code String}?</h2>
 * {@link com.luxera.companion.boundary.event.SensoryEvent#modality()} 返回的是
 * {@code "auditory"} 这样的字符串, 而不是这个枚举。这不是不一致, 是<b>两层各自该有的形状</b>:
 * <ul>
 *   <li>{@code boundary} 是<b>数据/序列化层</b>。它要能承载"某个我们还不知道的通道"而不崩 ——
 *       一条来自未来设备的刺激, 应当在投递时被记录、被诊断面板看见, 而不是让整条链路抛异常;</li>
 *   <li>这里是<b>解释层</b>。"我听到了"这件事的前提是"我有耳朵"。一个通道名翻译不成
 *       {@code Modality} 的刺激, 对<b>她</b>而言根本不存在。</li>
 * </ul>
 * 所以 {@link Perception} 在翻译时对未知通道名<b>不抛异常</b>, 而是产出一个
 * {@link #UNKNOWN}(见下), 由调用方决定是丢弃还是记录。
 *
 * <h2>{@code UNKNOWN} 为什么存在 —— 它不是一个偷懒的兜底</h2>
 * 初看它像是"给异常找了个更安静的家", 那正是坏事。它存在的理由是具体的:
 * <b>统计口径</b>。没有它, 一台新设备发来的刺激要么让整条链路抛异常(不可接受), 要么被
 * 静默丢弃 —— 而"她今天没反应"在行为分析里会与"她那台设备发的东西她根本接不到"
 * 看起来一模一样。有了它, 这条刺激仍然进得了日志和计数器, 只是永远进不了工作认知。
 *
 * <p>它的 {@link #weight()} 是 0, 所以它对注意力的贡献也是 0 —— 但它<b>仍然会被记下</b>。
 * 这是"可解释"胜过"干净"的一次具体取舍。
 */
public enum Modality {

    /** 听觉 —— 音高、响度、时长。她认得出自己手机铃声的音色。 */
    AUDITORY("听觉", CoreEventCatalog.Modalities.AUDITORY, 1.00),

    /**
     * 视觉 —— 照度、色温、方位。
     *
     * <p>权重最高(1.15)是有依据的: 视觉是人的主导通道, 一个亮度足够的变化
     * 几乎总会夺走注意力。这不是审美偏好, 是 {@link AttentionService} 打分的输入之一,
     * 而它必须有一个来源 —— 这个来源是生理学, 不是调参。
     */
    VISUAL("视觉", CoreEventCatalog.Modalities.VISUAL, 1.15),

    /** 嗅觉 —— 浓度、类别。传播慢、定位差, 但情绪唤起强。 */
    OLFACTORY("嗅觉", CoreEventCatalog.Modalities.OLFACTORY, 0.70),

    /** 味觉 —— 五味强度。它几乎总是内生的(吃东西), 极少来自外部世界。 */
    GUSTATORY("味觉", CoreEventCatalog.Modalities.GUSTATORY, 0.60),

    /** 触觉 —— 压力、温度、材质、痛。疼痛的紧迫度极高, 所以基础权重不低。 */
    TACTILE("触觉", CoreEventCatalog.Modalities.TACTILE, 0.90),

    /**
     * 翻译不了的通道 —— 见类注释"UNKNOWN 为什么存在"。
     *
     * <p>它<b>不</b>对应 {@code CoreEventCatalog.Modalities} 里的任何值, 所以
     * {@link #channel()} 对它返回空。
     */
    UNKNOWN("未知通道", null, 0.00);

    /** 中文标签 —— 进日志与 LLM context, 不参与任何判断。 */
    private final String label;

    /** 在 {@code boundary} 那侧对应的字符串; {@code null} 表示"这一侧没有对应值"。 */
    private final String channel;

    /** 显著度基础权重 —— 见 {@link Perception#salienceOf}. */
    private final double weight;

    Modality(String label, String channel, double weight) {
        this.label = label;
        this.channel = channel;
        this.weight = weight;
    }

    public String label() {
        return label;
    }

    /**
     * 这个通道在 {@code boundary} 那侧的字符串名(如 {@code "auditory"})。
     *
     * <p>{@link #UNKNOWN} 返回空 —— 而"空"这件事本身是有信息的: 它意味着
     * 这条刺激的通道名<b>不在平台的标准集合里</b>, 因此它多半是拼错或来自未来的设备。
     */
    public Optional<String> channel() {
        return Optional.ofNullable(channel);
    }

    public double weight() {
        return weight;
    }

    /** 这个通道是不是"人的五种感官"之一。{@link #UNKNOWN} 不是。 */
    public boolean standard() {
        return channel != null;
    }

    /**
     * 把 {@code boundary} 那侧的通道名翻译成本枚举。
     *
     * <p><b>刻意不抛异常</b> —— 理由见类注释。一个拼错的 {@code "auditoryy"}
     * 会让这条刺激落进 {@link #UNKNOWN}, 于是它被记进日志却进不了注意 ——
     * 而如果这里抛异常, 一台配置写错的第三方设备就能让整个 tick 崩掉。
     * 一个表现得像"她没听见"的故障, 好过一个"她整个人停机"的故障。
     */
    public static Modality fromChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return UNKNOWN;
        }
        String normalized = channel.trim().toLowerCase(java.util.Locale.ROOT);
        for (Modality m : values()) {
            if (m.channel != null && m.channel.equals(normalized)) {
                return m;
            }
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return label;
    }
}
