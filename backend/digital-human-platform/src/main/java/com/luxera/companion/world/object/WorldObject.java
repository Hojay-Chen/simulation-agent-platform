package com.luxera.companion.world.object;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.WorldEvent;

/**
 * V2.2 §4.2 —— <b>世界里一切东西的根接口</b>。设备、地点、环境、衣柜、未来的机器人都实现它。
 *
 * <h2>Agent 永远拿不到一个 {@code WorldObject} 引用</h2>
 * 用户对这条的表述是"human 和 world 完全没有交互"。在代码层面它落在两处:
 * <ol>
 *   <li><b>模块边界:</b> {@code human/} 包不 import {@code world/} 包(§9 验收标准 F,
 *       由 ArchUnit 钉死)。这不是"约定不写", 是物理上拿不到;</li>
 *   <li><b>接口形状:</b> Agent 能看到的是一个对象贡献的 {@code Capability}("它能做什么"),
 *       而不是这个对象本身。这条正是 {@link #id()} 与 {@link #typeId()} 存在的原因 ——
 *       它们是<b>可以安全交给日志、数据库与事件</b>的那部分, 而对象的行为只能经
 *       {@code ActionFabric} 触发。
 * </ol>
 *
 * <h2>为什么 {@link #typeId()} 返回 {@code EventTypeId} 而不是 Java {@code Class}</h2>
 * <table border="1">
 *   <tr><th>方案</th><th>第三方接入时会发生什么</th></tr>
 *   <tr>
 *     <td>{@code Class<?> type()}</td>
 *     <td>调用方必须 {@code import} 那个类 —— 而第三方类<b>在编译宿主时还不存在</b>。
 *         于是"新增一个 Device"变成一个必须改宿主源码的动作</td>
 *   </tr>
 *   <tr>
 *     <td>字符串 {@code "phone"}</td>
 *     <td>退化成各写各的: {@code phone} / {@code Phone} / {@code PHONE} 同时在库里。
 *         它们之间的失配是静默的, 表现为"这个对象读不回来"</td>
 *   </tr>
 *   <tr>
 *     <td><b>{@code EventTypeId}</b></td>
 *     <td>运行时值对象, 有确定语法(可校验、可索引、可拼进日志), 又能被第三方构造。
 *         与 {@code @DomainType} 注解由 {@code DomainTypeRegistry} 互相转换</td>
 *   </tr>
 * </table>
 *
 * <p>复用语事件类型同一套标识的理由不只是"省一个类": <b>对象的类型名与它产生的事件的
 * 命名空间必须能对上</b>({@code device.phone} 的对象产生 {@code device.phone.*} 的事件),
 * 两套独立的标识系统必然会在某一天漂移, 而漂移的表现是"事件过滤按对象类型筛不出东西"。
 *
 * <h2>三个方法, 一个都不多</h2>
 * 刻意<b>不</b>在这里放 {@code capabilities()} —— 尽管多数对象都有能力。理由:
 * {@code DigitalWorld} 的成员(环境、地点)对 Agent 是<b>只读</b>的, 给它们一个空的能力集合
 * 会让"环境有没有能力"这个问题永远有一个"有, 但是空的"的答案, 而不是"这个问题不成立"。
 * 能力属于 {@code DigitalDeviceWorld} 那一侧, 由 {@code Device} / {@code DeviceApplication}
 * 各自声明。
 */
public interface WorldObject {

    /**
     * 这个对象的身份。
     *
     * <p>刻意返回强类型而不是 {@code String} —— 见 {@link ObjectId} 类注释里
     * "为什么不直接用 String id"。
     */
    ObjectId id();

    /**
     * 类型标识 —— <b>不是 Java class, 是可序列化的领域类型</b>。
     *
     * <p>实现类通常写 {@code return TYPE;} 一个 {@code static final} 常量, 并配上
     * {@code @DomainType("device.phone")} 注解, 让它能被 {@code DomainTypeRegistry} 注册、
     * 被 {@code PolymorphicSerializer} 写进数据库再读回来。
     */
    EventTypeId typeId();

    /**
     * 人类可读的名字, 用于界面与 LLM 的 context: "她的手机"、"实验室"、"家里"。
     *
     * <p>它与 {@link #id()} 的分工是: id 给机器, displayName 给人(以及给 LLM 拼提示词)。
     * <b>不要拿 displayName 做查找</b> —— 她会把手机改名, 而改名不该让历史事件找不到来源。
     */
    String displayName();

    /**
     * 一行摘要, 给日志与诊断面板用。
     *
     * <p>默认实现刻意只打 id 与类型。实现类应当覆盖它并补上关键坐标,
     * 但<b>绝不能</b>写聊天正文 —— 世界对象不该持有正文, 也就无从写起(见 §9 验收标准 E)。
     */
    default String describe() {
        return typeId() + "(" + id() + ") \"" + displayName() + "\"";
    }

    /**
     * 这个对象是不是会产生世界的刺激 —— 即它有没有能力把 {@link WorldEvent} 投进
     * {@code EventFabric}。
     *
     * <p>默认 {@code true}: 世界里绝大多数对象(virtual 的桌子、灯、衣柜)在状态变化时
     * 都会投一条 {@code object.state-changed.v1}。默认返回 true 是为了让第三方<b>不需要
     * 思考这个问题</b>就能接入 —— 一个"我该不该声明自己能投事件"的问题,
     * 对插件作者来说问错了人(答案在宿主的现象学里, 不在他的实现里)。
     */
    default boolean emitsEvents() {
        return true;
    }
}
