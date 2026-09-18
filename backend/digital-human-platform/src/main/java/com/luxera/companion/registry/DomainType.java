package com.luxera.companion.registry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * V2.2 §1.3 P4 —— <b>一个类在数据里的名字</b>。
 *
 * <h2>它替代了什么</h2>
 * 在 V2.1 之前, "这是哪一种事件 / 哪一种活动 / 哪一种设备"是靠在枚举里加常量来表达的:
 * <pre>{@code
 * // 旧写法 —— 每加一种就要改一次平台源码
 * public enum EventType { MESSAGE_RECEIVED, TEMPERATURE_CHANGED, PHONE_RANG, ... }
 * }</pre>
 * 于是第三方软件想接入一个"宠物喂食器响了"的事件, 就必须让平台作者去改枚举、重新编译、
 * 重新发版。<b>一个声称"让三方平台软件自己实现接口对接"的架构, 不能有一个只有平台作者
 * 才能扩展的类型系统。</b>
 *
 * <p>本注解把"类型名"从<b>编译期常量</b>变成<b>数据</b>: 三方实现类自己声明
 * {@code @DomainType("petfeeder.bowl-emptied")}, 注册进 {@link DomainTypeRegistry},
 * 于是它的 JSON 就能被反序列化回正确的类 —— 全程不需要平台认识这个类型。
 *
 * <h2>名字的形状是有约束的</h2>
 * 取值形如 {@code namespace.name} (版本由 {@link #version()} 单独给),
 * 命名空间通常就是<b>提供这个类型的应用</b>:
 * <table border="1">
 *   <tr><th>注解值</th><th>谁提供</th></tr>
 *   <tr><td>{@code environment.temperature-changed}</td><td>平台自带的 digital world</td></tr>
 *   <tr><td>{@code device.phone.ring-started}</td><td>平台自带的手机</td></tr>
 *   <tr><td>{@code chat.message-notified}</td><td><b>聊天平台</b>接入后产生</td></tr>
 *   <tr><td>{@code petfeeder.bowl-emptied}</td><td><b>某个第三方应用</b>(平台源码里没有这个词)</td></tr>
 * </table>
 *
 * <p>和 {@code EventTypeId} 的关系: 注解给的是<b>声明处</b>的写法(人和编译器读的),
 * {@code EventTypeId} 是<b>运行时</b>的三元组(注册表和数据库读的)。两者由
 * {@link DomainTypeRegistry} 互相转换, 而不是各写一遍。
 *
 * <h2>{@code @Inherited} 是刻意的</h2>
 * 第三方常见做法是写一个抽象基类 {@code AbstractPetDevice}, 让具体设备继承它。
 * 标了 {@code @Inherited}, 子类即使忘了再标一次也能被扫到 —— 而"忘了标注解"
 * 导致的失败是"这个类型在数据库里读不回来", 一个只在重启后才暴露的故障。
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DomainType {

    /**
     * 类型名, 形如 {@code namespace.name}。
     *
     * <p>建议用<b>短横线小写</b>({@code temperature-changed})而不是驼峰 ——
     * 这个名字会出现在 JSON、数据库列、诊断面板和日志里, 而其中有些地方对大小写不友好。
     */
    String value();

    /**
     * 主版本号。
     *
     * <p>什么时候该加这个数: 当载荷的<b>含义</b>变了(字段重命名、单位从摄氏度改成华氏度)。
     * 只是加一个新字段<b>不用</b>加版本 —— 老数据读进新类时那个字段是 null, 而
     * 那正是"她当时没有这个信息"的诚实表达。
     *
     * <p>为什么要有版本而不是直接改名: 改名会让库里的历史事件永远读不回来。
     * 而本设计的一个核心承诺是"旧版本永不删除"(见 {@code plan.revision-created.v1}),
     * 那也要求历史可读。
     */
    int version() default 1;

    /**
     * 一句话说明这个类型是什么。
     *
     * <p>它不是注释 —— 它会被诊断面板和 LLM 的工具清单读走。所以写"能被人看懂的一句话",
     * 不要写"TODO"或"见代码"。
     */
    String description() default "";
}
