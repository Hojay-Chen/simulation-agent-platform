package com.luxera.companion.boundary.event;

import java.time.Duration;
import java.util.Optional;

/**
 * V2.2 §5.2 —— <b>A 类: 持续影响事件</b>。它不"触发"什么, 它<b>一直在</b>。
 *
 * <h2>这一类事件为什么不能用队列表达</h2>
 * 这是三种分类里最容易做错的一种。队列的语义是"取出即消费", 而持续影响的语义恰恰相反:
 * <b>它必须一直留在那里起作用, 直到条件改变</b>。
 *
 * <p>拿用户举的例子: "温度低应该让 human 的 body 持续降低保暖值"。
 * 如果把它塞进一个队列, 会发生什么:
 * <pre>
 *   tick 1: 取出"降温"事件 → 保暖值 -0.02 → 消费掉了
 *   tick 2: 队列空了 → 保暖值不变   ← 错。外面还是 3 度, 她还在变冷
 *   tick 3: 队列空了 → 保暖值不变   ← 错
 *   ...
 *   结论: 她永远不会觉得冷, 除非外面每秒钟都在"重新降温"一次
 * </pre>
 * 而"外面每秒钟重新降一次温"是荒谬的 —— 现实里的冷不是一个反复发生的事件, 是一个
 * <b>持续成立的状态</b>。
 *
 * <p>所以 A 类事件被 {@link ContinuousEffectLedger} 收下之后, 变成一条
 * <b>带有效期的账目</b>, 由每个 tick 反复结算。队列在这里是错的工具。
 *
 * <h2>"持续"到什么程度: 三种终止条件</h2>
 * <table border="1">
 *   <tr><th>{@link #expiresAt()} / {@link #duration()}</th><th>含义</th><th>例子</th></tr>
 *   <tr>
 *     <td>有 {@code duration}, 有 {@code expiresAt}</td>
 *     <td>到期自动失效</td>
 *     <td>"外面下雨" → 雨停了这条影响就该停</td>
 *   </tr>
 *   <tr>
 *     <td>有 {@code duration}, 无 {@code expiresAt}</td>
 *     <td>从入账时刻起算一段固定时长</td>
 *     <td>"她刚跑完步, 接下来 20 分钟心率偏高"</td>
 *   </tr>
 *   <tr>
 *     <td>两者都无</td>
 *     <td><b>永久, 直到被显式撤销</b></td>
 *     <td>"她身上穿着羽绒服" —— 只有脱下来才会停</td>
 *   </tr>
 * </table>
 *
 * <h2>"撤销"长什么样</h2>
 * 靠 {@link #cancellationKey()} —— 一个<b>同族影响之间的身份</b>。账本按它去重与替换:
 * <ul>
 *   <li>同 key 的新影响<b>替换</b>旧影响(换了件更厚的衣服, 薄的自动失效);</li>
 *   <li>key 为 {@code null} 表示"这条不参与替换", 各算各的(下雪和刮风是两件事)。</li>
 * </ul>
 *
 * <p>这个设计直接对应用户描述的"多穿衣服也是一个 event, 能够持续影响 body 的保暖值":
 * 穿羽绒服产生一条 {@code cancellationKey = "body.thermal-insulation"} 的影响,
 * 它把上一件衣服的那条挤掉; 脱衣服则是一条 {@code magnitude = 0} 的同 key 影响 ——
 * <b>不是"删除"</b>, 因为"她脱掉了"和"她从没穿过"是两件不同的事, 历史里都该留着。
 */
public interface StateEffectEvent extends WorldEvent {

    /**
     * 这条影响的<b>强度与方向</b>。
     *
     * <p>量纲由 {@link #effectChannel()} 决定, 本身是一个无量纲的标量:
     * 保暖通道上 {@code +0.85} 是羽绒服, {@code -0.02} 是室外 3 度。
     * 正负号是"加"还是"减", 不是"好"还是"坏" —— 账本不做价值判断, 它只做加法。
     */
    double magnitude();

    /**
     * 这条影响作用在<b>哪条通道</b>上。
     *
     * <p>用字符串而不是枚举, 理由同 {@link EventTypeId}(P4)。
     * 但这里<b>必须</b>用平台上约定的标准通道名, 因为不同通道的数值会被加在一起 ——
     * 两个插件各自发明 {@code "warmth"} 和 {@code "thermal"} 会得到两条互不干扰的账,
     * 表现是"羽绒服穿了但没用"。标准通道清单见 {@code CoreEventCatalog.channels()}。
     */
    String effectChannel();

    /**
     * 从<b>入账时刻</b>起算的影响时长。空表示"不按时间到期"。
     *
     * <p>刻意与 {@link #expiresAt()} 并存而不是二选一: 它们的差别是"以谁为基准算"。
     * {@code duration} 是相对的(从入账起 20 分钟), {@code expiresAt} 是绝对的
     * (雨停的时刻)。环境刷新产生的影响天然是绝对的(它知道预报几点停),
     * 而一个 Action 的后果天然是相对的(穿衣服穿多久取决于她什么时候脱)。
     */
    default Duration duration() {
        return null;
    }

    /**
     * 这条影响在哪个绝对时刻失效。空 + 空的 {@code duration} = 永久。
     *
     * <p>见 {@link #duration()} 说明为什么两者并存。
     */
    default java.time.Instant expiresAt() {
        return null;
    }

    /**
     * 同族影响的身份, 用于替换。{@code null} = 不参与替换。
     *
     * <p>见类注释末尾"撤销长什么样"。
     */
    default String cancellationKey() {
        return null;
    }

    /**
     * 这条影响<b>是不是一种改变</b> —— 即它相对"上一条同 key 的影响"有没有变化。
     *
     * <h2>这个方法存在的理由: 实时事件是从"差异"里长出来的</h2>
     * 用户的原话: "environment 导致保暖值变化, 这里变化产生的 diff 和低于适中值的程度
     * 也能形成一个实时 event 让 agent 能够感知然后决定做什么反应的"。
     *
     * <p>也就是说, A 类事件本身<b>不</b>直接惊动她 —— 外面 3 度不会让她跳起来。
     * 惊动她的是账本算出来的<b>差值</b>和<b>偏离度</b>。那个计算发生在
     * {@code Body} 的阈值检测里(§3.2.4), 不在事件里。所以本接口不提供
     * "变化率"之类的字段 —— 变化是<b>两次结算之间</b>的事, 单个事件看不到自己引起的差异。
     *
     * <p>保留这个方法只是给账本一个"这条影响是否值得记为一次变更"的提示, 默认恒真。
     */
    default boolean isChange() {
        return true;
    }

    /** 便于日志与调试: 这条影响的一行摘要。 */
    default String effectDescribe() {
        return typeId() + " " + effectChannel() + " " + (magnitude() >= 0 ? "+" : "")
                + magnitude()
                + Optional.ofNullable(cancellationKey()).map(k -> " key=" + k).orElse("");
    }
}
