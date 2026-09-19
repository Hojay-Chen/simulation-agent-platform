package com.luxera.companion.bootstrap;

import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.human.body.clothing.Coat;
import com.luxera.companion.human.body.clothing.DownJacket;
import com.luxera.companion.human.body.clothing.TShirt;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.world.device.AudioSystem;
import com.luxera.companion.world.device.BatterySystem;
import com.luxera.companion.world.device.Device;
import com.luxera.companion.world.device.NotificationSystem;
import com.luxera.companion.world.device.ScreenSystem;
import com.luxera.companion.world.digital.Place;
import com.luxera.companion.world.environment.Environment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * V2.2 §8.6.2 / §8.6.3 第 1 步 —— <b>这个运行时认识哪些类型, 在这一处说全</b>。
 *
 * <h2>它解决的问题: 类型名曾经散落在每一个生产者的构造函数里</h2>
 * V2.2 把"类型"从枚举常量变成了数据({@code @DomainType} 注解 + 注册表)。
 * 这解决了"三方应用不能自己扩展类型系统", 但它引入了一个新的、更隐蔽的失效:
 * <b>一个类被标注了、却没有被注册</b>。
 *
 * <p>那样的类在编译期完全正常, 在单元测试里也完全正常(测试通常直接 {@code new} 它),
 * 只有在一件事上不同: <b>它写进数据库之后就再也读不回来</b>。
 * 读回来的是一条 {@code _untyped} 的替身 —— 一个没有字段的空壳,
 * 而它的症状是"她昨天收到过一条通知, 今天重启后那条通知变成了一件不明事件"。
 *
 * <h2>为什么是"逐个调用生产者的 registerTypes(...)", 而不是另外两条路</h2>
 * <table border="1">
 *   <tr><th>写法</th><th>为什么不用</th></tr>
 *   <tr>
 *     <td>扫 classpath 上的 {@code @DomainType}</td>
 *     <td>看起来最省事, 但它让"这个类型属于谁"消失。而 §8.6.2 的
 *         {@code Cancellation} 事故正是这一条的后果: 一个被删掉的事件类,
 *         因为扫描仍然扫到了它的 {@code .class} 文件(旧构建产物), 于是它
 *         <b>继续在注册表里</b>, 继续被反序列化出来, 而源码里已经没有人认领它。
 *         <b>扫描把一个"谁负责"的问题变成了一个"文件在不在"的问题</b></td>
 *   </tr>
 *   <tr>
 *     <td>在这里手写类型名字符串</td>
 *     <td>那就是把枚举挪了个地方 —— 加一个事件类要改两处, 而第二处没有任何
 *         机制提醒你(名字是字符串, 编译器不认识)</td>
 *   </tr>
 *   <tr>
 *     <td><b>逐个调生产者的入口</b>(本类)</td>
 *     <td>每一个类型都有<b>一个明确的归属人</b>: 它由哪个类生产, 就由那个类声明。
 *         删掉那个生产者的调用行, 它下面的类型一起消失 —— 这是"删干净"该有的样子。
 *         而漏掉一个生产者这件事由 §8.6.6 那条守卫兜住(见
 *         {@code V22BoundaryArchitectureTest} 的登记守卫)</td>
 *   </tr>
 * </table>
 *
 * <h2>顺序为什么是这样</h2>
 * 严格说, 表内的顺序<b>没有</b>语义依赖 —— {@code register} 是往一个 map 里放,
 * 谁先谁后不影响结果。下面这个顺序按的是 {@code plan.* → world.* → human.* → system.*}
 * 的<b>阅读顺序</b>, 让日志和失败信息读起来像一句关于这个世界的陈述,
 * 而不是一份随手的清单。真正的顺序约束在 {@link #assemble()} 的调用者那里:
 * 它必须发生在<b>任何 store 之前</b>(§8.6.3 第 2 步)。
 */
@Slf4j
public final class DomainTypeAssembly {

    private DomainTypeAssembly() {
    }

    /**
     * 一个生产者 —— "谁声明了这些类型"。
     *
     * <p>{@code owner} 写的是<b>人读的那半句</b>(它是什么), {@code entryPoint} 是机器调的那半句。
     * 两者都要: 只留 {@code entryPoint} 的话, 失败信息里会出现
     * {@code com.luxera.companion.world.device.BatterySystem::registerTypes}
     * 这样一个能定位但读不懂"它是干什么的"的东西。
     */
    private record Producer(String owner, ToIntFunction<DomainTypeRegistry> entryPoint) {
    }

    /**
     * 生产者的登记清单 —— <b>§8.6.6 那条守卫断言的就是它</b>。
     *
     * <p>这份清单必须与 {@code @DomainType} 的分布一致: 核心包下每一个带
     * {@code @DomainType} 的类, 都必须能从下面某一行的调用里拿到自己的名字。
     * 加了一个新事件类而没加进这里 → 守卫红, 且消息里直接给出类名。
     */
    private static final List<Producer> PRODUCERS = List.of(
            // ── 计划与生活 ──────────────────────────────────────────────
            new Producer("计划事件 (item-scheduled / due / finished / interrupted / revision-created)",
                    PlanEvents::registerTypes),
            new Producer("活动类型 (12 种 life.activity.*)",
                    ActivityFactory::registerTypes),

            // ── 世界: 环境与地点 ────────────────────────────────────────
            new Producer("环境事件 (温度 / 湿度 / 天气 / 风 / 空气质量 / 日照 / 快照)",
                    Environment::registerTypes),
            new Producer("地点事件 (location-changed)",
                    Place::registerTypes),

            // ── 世界: 设备 ──────────────────────────────────────────────
            new Producer("设备本体 (device.phone.unavailable)",
                    Device::registerTypes),
            new Producer("电池系统 (battery-changed)",
                    BatterySystem::registerTypes),
            new Producer("音频系统 (sound-emitted / ringtone-started / alarm-fired)",
                    AudioSystem::registerTypes),
            new Producer("通知系统 (notification-raised / notification-dropped)",
                    NotificationSystem::registerTypes),
            new Producer("屏幕系统 (screen-changed)",
                    ScreenSystem::registerTypes),

            // ── 她的身体: 可以穿在身上的东西 ────────────────────────────
            new Producer("衣物 (t-shirt / coat / down-jacket)",
                    registry -> TShirt.registerTypes(registry)
                            + Coat.registerTypes(registry)
                            + DownJacket.registerTypes(registry)),

            // ── 系统 ────────────────────────────────────────────────────
            new Producer("持续影响的撤除 (system.effect-cancelled)",
                    ContinuousEffectLedger::registerTypes));

    /**
     * 造一个空的注册表并把上面清单里的类型全部登记进去。
     *
     * <h2>为什么"造"和"登记"是同一个方法, 而不是两个</h2>
     * 因为它们之间<b>不允许</b>有任何东西。§8.6.3 第 2 步说得很具体:
     * 一个用空注册表造出来的 {@code DomainPayloadCodec} 会让所有写下去的载荷变成
     * {@code _untyped} —— 而它看起来完全正常。
     *
     * <p>把它拆成 {@code newRegistry()} 与 {@code registerInto(registry)} 两个公开方法,
     * 就等于给出了一个"先拿到空表、以后再填"的合法姿态。这里把它合成一步:
     * <b>拿到 {@link DomainTypeRegistry} 的唯一方式, 就是拿到一个已经登记好的它。</b>
     *
     * <p>返回值只给注册表本身, 不给类型个数 —— 个数由 {@code registry.size()} 回答,
     * 而那是同一个事实的第二个来源。两个来源就会分叉。
     */
    public static DomainTypeRegistry assemble() {
        DomainTypeRegistry registry = new DomainTypeRegistry();
        for (Producer producer : PRODUCERS) {
            int n = producer.entryPoint().applyAsInt(registry);
            log.debug("[Sim] 登记 {} → {} 个类型", producer.owner(), n);
        }
        if (!registry.conflicts().isEmpty()) {
            // 冲突不是致命错误(先注册的赢), 但它是"两个类都认为自己叫这个名字" ——
            // 于是一定有一个类的数据会被读成另一个类。这一行日志是它唯一的现场。
            log.warn("[Sim] 类型名冲突 {} 处 (先注册的赢, 后一个的数据会被读成前一个): {}",
                    registry.conflicts().size(), registry.conflicts());
        }
        return registry;
    }

    /**
     * 登记清单的人话版本 —— 守卫失败时把它打进消息里。
     *
     * <p>为什么失败信息里要带这个: 读消息的人下一个动作是"去装配层加一行",
     * 而他需要知道<b>现有的是哪几行</b>才能决定加在哪。
     * 只报"某某类没被登记"的话, 他得先去把文件读一遍。
     */
    public static List<String> producerLabels() {
        List<String> out = new ArrayList<>(PRODUCERS.size());
        for (Producer producer : PRODUCERS) {
            out.add(producer.owner());
        }
        return List.copyOf(out);
    }

    /** 生产者个数 —— 守卫用它把"清单有多长"说成一个数。 */
    public static int producerCount() {
        return PRODUCERS.size();
    }
}
