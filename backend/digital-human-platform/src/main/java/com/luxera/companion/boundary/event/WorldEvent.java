package com.luxera.companion.boundary.event;

import java.time.Instant;

/**
 * V2.2 §5.2 —— <b>世界里发生过的一件事</b>。
 *
 * <h2>它描述"发生了什么", 不描述"该怎么办"</h2>
 * 这条分界是整个事件体系的地基。{@code environment.temperature-changed.v1} 说的是
 * "外面 3 度了"; 它<b>不说</b>"她该加衣服"。{@code device.phone.notification-raised.v1}
 * 说的是"手机响了"; 它<b>不说</b>"她该去看"。
 *
 * <p>为什么这条分界值钱: 一旦事件里带上"该怎么办", 世界就被迫知道 Human 的决策逻辑 ——
 * 而 Human 与 World 零交互(P3)是本设计第一条不可协商的规则。世界只能陈述事实, 因为
 * <b>世界本来就不该有意见</b>。意见是 Mind 的事。
 *
 * <h2>它是接口, 不是基类, 也不是实体</h2>
 * <table border="1">
 *   <tr><th>它是什么</th><th>为什么</th></tr>
 *   <tr>
 *     <td>接口, 不是抽象类</td>
 *     <td>因为一个事件常常同时属于多个类别 —— 降温既是
 *         {@link StateEffectEvent}(持续影响保暖值)又是潜在冷刺激的来源。
 *         Java 只有单继承, 用抽象类会逼着作者二选一, 而那个选择是<b>错的</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>不是 JPA 实体</td>
 *     <td>事件是<b>值</b>, 不是行。同一条事件在内存里被传阅、被多个结构消费
 *         (进 {@link ContinuousEffectLedger} 也进 {@link RealtimeEventQueue}),
 *         持久化只是它生命周期的<b>其中一个</b>环节。把值对象做成实体, 会得到一个
 *         到处带着 {@code @Entity} 注解、离开 Session 就 {@code LazyInitializationException}
 *         的"值", 那不是值。</td>
 *   </tr>
 *   <tr>
 *     <td>不是 record</td>
 *     <td>因为 record 无法被继承, 而这里恰恰需要"一个事件实现多个能力接口"。
 *         record 适合最终的载荷({@code TemperatureChangedPayload}), 不适合事件本身。</td>
 *   </tr>
 * </table>
 *
 * <h2>最少的三个方法</h2>
 * 只有 {@link #typeId()}、{@link #occurredAt()}、{@link #sourceObjectId()} 是必须的。
 * 刻意<b>不</b>在这里放 {@code salience()} / {@code urgency()} / {@code importance()} ——
 * "这有多重要"是<b>她</b>的属性, 不是事件的属性(V2.2 §3.4.4 的边界规则:
 * "salience 是消息的属性, 处境是她的属性"的推论是: 同一个刺激对不同处境的人重要程度不同,
 * 所以那个数只能由 Mind 结合自身状态算出来, 不能由世界写在事件上)。
 *
 * <h2>载荷怎么放</h2>
 * <b>实现类是自由的</b>。V2.2 只要求实现类满足:
 * <ol>
 *   <li>能回答 {@link #typeId()} —— 通常是 {@code return TYPE;} 一个 static final 常量;</li>
 *   <li>能被 {@code PolymorphicSerializer} 序列化成 JSON 落进 {@code world_event.payload_json};</li>
 *   <li>反序列化时能按 typeId 从 {@code DomainTypeRegistry} 找回实现类。</li>
 * </ol>
 * 因此一个实现类可以就是一个 record:
 * <pre>{@code
 * public record TemperatureChanged(TemperatureChangedPayload payload, ...) implements WorldEvent, StateEffectEvent {
 *     public static final EventTypeId TYPE = EventTypeId.of("environment", "temperature-changed");
 *     @Override public EventTypeId typeId() { return TYPE; }
 *     ...
 * }
 * }</pre>
 *
 * <h2>不可变</h2>
 * 实现类<b>必须</b>不可变。事件一旦发生就不会变 —— 会变的是世界。
 * 一个可变的事件对象在三个结构之间被传阅时, 后一个消费者看到的将不是同一个事实。
 */
public interface WorldEvent {

    /**
     * 这个事件的类型。
     *
     * <p>实现类应当返回一个 {@code static final} 常量, <b>不要每次 new 一个</b> ——
     * 类型标识会被当作 Map 的键、被放进集合做去重, 每次都造新的会让
     * {@code contains} 依赖 {@code equals} 而不是引用相等, 在热路径上是白白的一层开销。
     */
    EventTypeId typeId();

    /**
     * 这件事发生在世界时间轴上的哪一刻。
     *
     * <p>用 {@link Instant} 而不是 {@code LocalDateTime}: 事件时间是一个<b>绝对</b>时刻,
     * 而 {@code LocalDateTime} 没有时区、无法跨进程比较。仿真时钟可以推进得比真实时间快
     * 或慢, 但"这一刻"必须能被无歧义地排序 —— {@link RealtimeEventQueue} 的整个
     * 定序策略依赖这一点。
     */
    Instant occurredAt();

    /**
     * 这件事是<b>哪个世界对象</b>引起的 —— 手机、房间外的天气、她身上那件羽绒服。
     *
     * <p>允许为 {@code null}: 有些事件没有单一来源({@code system.clock-tick.v1} 就不是
     * 某个对象"引起"的)。用 {@code String} 而不是强类型 WorldObject 引用, 是为了
     * 不让事件持有一个可能已被回收的领域对象 —— 事件是<b>历史记录</b>,
     * 历史不该把活对象钉在内存里。
     */
    String sourceObjectId();

    /**
     * 人类可读的一行摘要, 给日志和调试面板用。
     *
     * <p>默认实现只打类型与时间。实现类<b>应当覆盖它</b>并写上关键坐标(温度值、账号 id),
     * 但<b>绝不能</b>写聊天正文 —— 这是一条会被代码审查挡下的约定, 见下面的说明。
     *
     * <h3>为什么 "不许带正文" 不在这里用类型强制</h3>
     * V11 的 {@code EventEnvelope} 用构造函数抛异常的方式禁止了正文键名, 那是有效的,
     * 因为信封的 {@code references} 是一个自由 Map。但 {@code WorldEvent} 的载荷是
     * <b>强类型 record</b> —— 一个叫 {@code MessageContent} 的字段要被人显式写出来,
     * 而"显式写出来"这个动作在评审里是看得见的。真正的强制在别处:
     * <b>投递路径上根本没有正文可拿</b>(§8.2.4 验收标准 E)。
     * 在这里再加一层同义反复的运行时检查, 只会让每个事件类都多三行不知道在防什么的代码。
     */
    default String describe() {
        return typeId() + "@" + occurredAt()
                + (sourceObjectId() == null ? "" : " from=" + sourceObjectId());
    }
}
