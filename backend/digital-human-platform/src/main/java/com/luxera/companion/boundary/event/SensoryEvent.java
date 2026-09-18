package com.luxera.companion.boundary.event;

/**
 * V2.2 §5.2 —— <b>B 类: 感官实时事件</b>。它需要她<b>现在</b>就反应, 或者至少现在就知道。
 *
 * <h2>与 A 类的根本差别</h2>
 * <table border="1">
 *   <tr><th></th><th>A 类 {@link StateEffectEvent}</th><th>B 类 本接口</th></tr>
 *   <tr>
 *     <td>时间性</td><td>一直成立</td><td>发生在某一刻</td>
 *   </tr>
 *   <tr>
 *     <td>数据归宿</td><td>{@link ContinuousEffectLedger}(账目, 反复结算)</td>
 *     <td>{@link RealtimeEventQueue}(队列, 取出即消费)</td>
 *   </tr>
 *   <tr>
 *     <td>对 Mind</td><td>不直接惊动; 由 Body 结算后的<b>差异</b>才可能惊动</td>
 *     <td><b>直接</b>要求处理</td>
 *   </tr>
 *   <tr>
 *     <td>用户的例子</td><td>"温度低持续降低保暖值"</td>
 *     <td>"手机铃响、电话铃声响起、传来臭味"</td>
 *   </tr>
 * </table>
 *
 * <h2>正确用法: A 类<b>长出</b> B 类, 而不是重复投递</h2>
 * 用户把这条链说得很清楚:
 * <pre>
 *   environment 逐渐降温  ──A──>  保暖值下降  ──>  低于适中值  ──B──>  "觉得冷"
 *        (持续影响)                 (账本结算)          (阈值检测)      (实时事件)
 * </pre>
 * <b>只有最后那一步产生 B 类事件。</b>中间那步(保暖值下降)刻意<b>不</b>产生实时事件 ——
 * 否则每 10 分钟的环境刷新都会惊动她一次, 而"她感到冷"这件事一天只会发生一两次。
 *
 * <p>这个设计是用户原话的直接落地: "像前面 environment 导致保暖值变化, 这里变化产生的
 * diff 和低于适中值的程度也能形成一个实时 event"。注意他用的词是 <b>diff</b> 和
 * <b>低于适中值的程度</b> —— 两者都是"结算之后才知道的", 所以生产者必然是 Body,
 * 不是 Environment。
 *
 * <h2>{@link #modality()} 是什么, 不是什么</h2>
 * 它是<b>哪一个感官通道</b>被刺激了 —— 听觉、视觉、嗅觉、味觉、触觉之一。
 * 它<b>不是</b>"这件事有多重要": 同样是听觉刺激, 手机响一声和救护车呼啸而过的
 * 处置优先级完全不同, 而那个差别取决于<b>她正在做什么</b>, 取决于
 * {@code AttentionService} 的处境计算(§3.4.4)。
 *
 * <p>所以本接口<b>没有</b> {@code salience()} 方法 —— 那是刻意的。见
 * {@link WorldEvent} 类注释里"最少的三个方法"一节。
 *
 * <h2>B 类事件会不会丢</h2>
 * 会, 而且应当会。{@link RealtimeEventQueue} 有容量上限, 溢出时丢掉的是
 * {@code urgency} 最低的那条。这不是缺陷 —— 一个真人也不会注意到手机上每一条横幅。
 * 但"丢了什么"必须可查, 否则行为分析会得出错误结论。所以队列在丢弃时记一条
 * {@code system.stimulus-dropped.v1}。
 */
public interface SensoryEvent extends WorldEvent {

    /**
     * 被刺激的感官通道: {@code auditory} / {@code visual} / {@code olfactory} /
     * {@code gustatory} / {@code tactile}。
     *
     * <p>用字符串不用枚举, 理由同 {@link EventTypeId}。但这里的取值集合是
     * <b>物理封闭</b>的(人只有这五种感官), 所以 {@code CoreEventCatalog} 会把它当
     * 标准通道来校验 —— 一个拼错的 {@code "auditoryy"} 会让刺激投进一个没有接收者的通道,
     * 表现是"她没听见", 且没有任何报错。
     */
    String modality();

    /**
     * 这个刺激有多急 —— <b>用于队列定序, 不是用于决策</b>。
     *
     * <h2>它为什么必须由世界给, 而不是由 Mind 算</h2>
     * 这条看起来与"salience 是她的属性"矛盾, 其实不矛盾, 因为两者不是一回事:
     * <table border="1">
     *   <tr><th></th><th>{@code urgency}</th><th>salience(在 Mind 侧算)</th></tr>
     *   <tr>
     *     <td>回答的问题</td>
     *     <td>"这条刺激<b>能不能等</b>"</td>
     *     <td>"这条刺激对她<b>此刻</b>有多重要"</td>
     *   </tr>
     *   <tr>
     *     <td>例子</td>
     *     <td>火警 1.0, 手机横幅 0.3 —— 换成谁都一样</td>
     *     <td>同样的手机横幅: 她在等面试结果时 0.9, 她在睡觉时 0.05</td>
     *   </tr>
     *   <tr>
     *     <td>谁算</td>
     *     <td>产生刺激的设备/环境。它知道自己的性质, 不知道她的处境</td>
     *     <td>Mind。它知道她的处境, 不需要知道刺激的物理性质</td>
     *   </tr>
     * </table>
     *
     * <p>{@link RealtimeEventQueue} 的定序用的是 {@code urgency} —— 必须如此, 因为
     * 队列是<b>世界侧</b>的结构, 它在 Mind 拿到之前就要决定"先递哪一条"。队列不能等 Mind
     * 算完重要程度再排序, 那是一个循环依赖。
     *
     * <h2>取值范围</h2>
     * {@code [0, 1]}。默认 {@code 0.5} = "不特别急也不特别不急"。
     * 实现者若拿不准该给多少, <b>就给默认值</b> —— 一个瞎猜的 0.9 会比默认值更糟,
     * 因为它会把真正紧急的刺激挤出队列头部。
     */
    default double urgency() {
        return 0.5;
    }

    /**
     * 这条刺激是不是<b>已经开始</b>了 —— 用于区分"门铃响了"和"门铃一直在响"。
     *
     * <p>一个持续存在的刺激源(隔壁装修的电钻声)不应该每一 tick 都往队列里塞一条 ——
     * 那样队列会被同一件事灌满。生产者应当在<b>开始</b>时发一条 {@code ongoing=false}
     * (即"刚开始"), 之后若持续存在则发 {@code ongoing=true}, 由队列按
     * {@code (modality, 来源)} 折叠。
     *
     * <p>默认 {@code false} —— 绝大多数刺激是瞬时的。
     */
    default boolean ongoing() {
        return false;
    }

    /**
     * 折叠键: 同一条刺激的多次投递靠它合并。{@code null} = 不折叠, 每次都算新刺激。
     *
     * <p>见 {@link #ongoing()}。例: 电钻声的折叠键可以是 {@code "auditory:装修"}。
     */
    default String foldingKey() {
        return null;
    }

    /** 便于日志与调试: 这条刺激的一行摘要。<b>不含正文</b> —— 刺激本来也没有正文。 */
    default String stimulusDescribe() {
        return typeId() + " via " + modality() + " urgency=" + urgency()
                + (ongoing() ? " (ongoing)" : "");
    }
}
