package com.luxera.companion.bootstrap;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * V2.2 §8.6.4 / §8.6.5 —— 装配层的三个开关。
 *
 * <h2>为什么这里只有三个字段, 而不是"把能配的都配上"</h2>
 * 每多一个可配项, 就多一个"生产与测试跑的不是同一套参数"的可能, 而这类分歧
 * 的症状是"本地好好的, 线上她不睡觉" —— 排查它要从一份 yml 倒推回代码。
 * 所以这里的取舍是: <b>只暴露那些"生产与测试必须不同"的量</b>。
 *
 * <table border="1">
 *   <tr><th>字段</th><th>为什么它必须是可配的</th></tr>
 *   <tr>
 *     <td>{@link #enabled}</td>
 *     <td>测试必须能把它关掉 —— 见 §8.6.4 的两个后果(每个 {@code @SpringBootTest}
 *         都要求全部 repository 在场; tick 线程会在测试里跑起来, 把确定性变成概率)。
 *         这是三个字段里唯一一个<b>语义</b>开关, 另两个只是数值</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #tickMs}</td>
 *     <td>仿真时间前进的速度。真实运行要的是"她的一天按真实速度过", 而
 *         回放与压测要的是"一秒钟过完三天" —— 这两件事差三个数量级,
 *         写死任何一个都会让另一个没法用</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #environmentInterval}</td>
 *     <td>天气源是<b>真的外部服务</b>(§6 的 digital world 那一半)。
 *         它的刷新频率受限于对方的配额与礼貌, 而不是我们的计算能力 ——
 *         所以它必须能在不改代码的前提下调大</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 {@link #environmentInterval} 的默认值是 10 分钟</h2>
 * 因为真实天气在 10 分钟里几乎不变, 而每一次刷新是一次外呼。
 * 把它调到"每秒"不会让她更真实, 只会让天气源把我们封掉。
 * 这个值同时也是 {@code EnvironmentRefreshJob} 的对齐间隔 ——
 * 于是"刷新永远落在 12:00 / 12:10 / 12:20"这件事是由它决定的,
 * 而不是由"上一次什么时候跑的"决定的(那是会累积漂移的那个写法, 见 §8.5.0)。
 */
@ConfigurationProperties(prefix = "companion.sim")
public class SimulationProperties implements InitializingBean {

    /**
     * 装配层的总开关。**缺省为假** —— 这是一个刻意的默认值。
     *
     * <p>缺省为假而不是真, 理由与 §8.6.4 那条同源: 一个"缺省就自己动起来"的
     * 组件, 会让每一个没想过它的测试上下文都被它影响。让它缺省沉默,
     * 于是"它没在跑"与"它跑了但什么也没发生"在日志里**不再长得一样** ——
     * 前者是明确的默认状态, 后者才是需要被解释的现象。
     */
    private boolean enabled = false;

    /**
     * 一次心跳的间隔, 毫秒。同时也是 {@code @Scheduled} 的 {@code fixedDelay} 值 ——
     * <b>不是</b> {@code fixedRate}, 理由见 §8.6.5(补跑会把 {@code skippedTicks}
     * 这个读面抹掉, 而"她被跳过了多久"正是我们要能发现的东西)。
     *
     * <p>为什么是 {@code long} 毫秒而不是 {@code Duration}: 因为 Spring 的
     * {@code fixedDelayString} 收的就是毫秒数, 而这个值唯一的消费者就是它。
     * 多一层 {@code Duration} 只会多一处 {@code toMillis()} 与它的取整问题。
     */
    private long tickMs = 1_000L;

    /**
     * 环境刷新的间隔 —— 它决定的是 {@code EnvironmentRefreshJob} 的<b>对齐边界</b>,
     * 不只是"多久一次"。见 {@link #environmentInterval} 的类注释那一段。
     *
     * <p>用 {@code Duration} 而不是毫秒数: 它进的是 {@code floorTo(interval)}
     * 与时间轴运算, 那些地方 {@code Duration} 才是原生类型。
     */
    private Duration environmentInterval = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public Duration getEnvironmentInterval() {
        return environmentInterval;
    }

    public void setEnvironmentInterval(Duration environmentInterval) {
        this.environmentInterval = environmentInterval;
    }

    /**
     * 把"配错了"变成一次<b>启动失败</b>, 而不是一次运行时的怪现象。
     *
     * <h2>为什么这个校验值得写出来</h2>
     * 三个字段里有三个非法值, 而它们的症状都不是"报错", 是"看起来在跑但不对":
     * <pre>
     *   tickMs = 0      → @Scheduled 在启动时抛 IllegalArgumentException,
     *                     消息里只有一串数字, 与"companion.sim.tick-ms"这个词无关
     *   tickMs &lt; 0      → 同上
     *   interval = 0    → floorTo(0) 是未定义行为(除零), 而它<b>不一定</b>当场抛:
     *                     它可能只是让每一次 pulse 都判定"跨过边界了",
     *                     于是天气源被每秒打一次 —— 直到对方把我们封掉
     *   interval &lt; 0    → 时间轴往后退, 而她身上没有任何东西会因此报警
     * </pre>
     * 这四个值里最危险的是 {@code interval = 0}: 它<b>不报错</b>, 只是把
     * "每 10 分钟一次外呼"变成"每拍一次外呼"。所以这里全部拒绝,
     * 并且消息里直接写清楚是哪个属性、为什么。
     *
     * <h2>为什么这个钩子是可靠的</h2>
     * 因为 Spring 的 {@code ConfigurationPropertiesBindingPostProcessor} 在
     * {@code postProcessBeforeInitialization} 里绑定, 而 {@code afterPropertiesSet}
     * 在<b>那之后</b>才被调用 —— 也就是说这里读到的一定是 yml 里的值, 不是字段的初值。
     * 这一点值得写下来: 若绑定发生在初始化之后, 这个方法就会去校验那些默认值,
     * 于是<b>永远为真、永远不报错</b> —— 一个静默失效的校验比没有校验更坏,
     * 因为它会让人以为"配错了会被拦住"。
     */
    @Override
    public void afterPropertiesSet() {
        if (tickMs <= 0) {
            throw new IllegalStateException(
                    "companion.sim.tick-ms 必须是正数, 收到 " + tickMs
                            + " —— 一个非正的心跳间隔会让 @Scheduled 在启动时抛一个"
                            + "看不出与配置有关的错, 而那不是它该被发现的地方");
        }
        Objects.requireNonNull(environmentInterval, "companion.sim.environment-interval 不能为空");
        if (environmentInterval.isZero() || environmentInterval.isNegative()) {
            throw new IllegalStateException(
                    "companion.sim.environment-interval 必须是正的时长, 收到 " + environmentInterval
                            + " —— 零间隔不会报错, 它只会让每一次心跳都判定'该刷新了',"
                            + " 于是天气源被每秒打一次; 负间隔会让时间轴往后退而无人报警");
        }
    }

    @Override
    public String toString() {
        return "SimulationProperties{enabled=" + enabled
                + ", tickMs=" + tickMs
                + ", environmentInterval=" + environmentInterval + '}';
    }
}
