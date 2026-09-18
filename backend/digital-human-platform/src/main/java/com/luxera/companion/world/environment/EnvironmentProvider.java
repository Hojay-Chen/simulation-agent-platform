package com.luxera.companion.world.environment;

import com.luxera.companion.world.digital.Location;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * V2.2 §4.4.2 —— <b>环境数据从哪来</b>。
 *
 * <h2>它为什么是一个只有一行方法的功能接口</h2>
 * 因为"环境的真相在哪"这个问题有非常多答案, 而它们之间的差别<b>全在实现里</b>:
 * <table border="1">
 *   <tr><th>实现</th><th>用途</th><th>它做的事</th></tr>
 *   <tr>
 *     <td>真实气象 API 的适配器</td>
 *     <td>生产</td>
 *     <td>拿坐标与时刻去调外部服务, 把返回的 JSON 映射成
 *         {@link EnvironmentSnapshot}(映射失败就抛, 由上层记一条)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Cached}</td>
 *     <td>生产(包装)</td>
 *     <td>把真实适配器套住, 避免每 10 分钟把 API 打爆</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #fixed(EnvironmentSnapshot)}</td>
 *     <td>测试</td>
 *     <td>永远返回同一份快照 —— 用来测"降温 → 加衣服"整条链的下游</td>
 *   </tr>
 *   <tr>
 *     <td>真实机器人的传感器</td>
 *     <td>未来</td>
 *     <td>把温湿度传感器的读数直接包成快照</td>
 *   </tr>
 * </table>
 * 注意这张表里没有任何一列需要"平台认识实现类": 上面四个都是接口的实现,
 * 装配时才知道谁在跑。这正是 P6(平台提供默认实现但不垄断扩展点)在环境域的落点。
 *
 * <h2>为什么 <b>没有</b> {@code providerId()} 这个抽象方法</h2>
 * 因为把它做成抽象方法会让本接口<b>不再是功能接口</b>, 于是
 * {@code (location, time) -> snapshot} 这种最自然的写法(测试里最常用)就没法用了。
 * 它改成了一个默认方法: 有身份的实现覆盖它, 匿名实现得到一个合成的类名 ——
 * 而诊断面板上那一栏会显示成"匿名提供方", 这比"必须写一个只有测试才会看的 id"要好。
 *
 * <h2>契约: 拿不到数据时怎么办</h2>
 * <b>抛异常, 不要返回一份假快照。</b>这一条是刻意的, 也是最容易做错的一处:
 * <ul>
 *   <li>网络断了 → 抛。上层计入失败次数, 并<b>保留上一份快照</b>继续生效
 *       —— "外面还是 3 度"在她等 API 恢复的这 10 分钟里仍然是对的;</li>
 *   <li>返回一份全 0 的快照 → 她会经历一次"气温突然变成 0℃", 而那是纯粹的噪声;
 *       更糟的是它看起来像真数据, 没有地方会报警。</li>
 * </ul>
 * 所以"没有再抓一次"与"抓到了 0 度"必须能分开 —— 与
 * {@code EnvironmentSnapshot} 里"没有数据与数据是 0"那条纪律是同一条。
 */
@FunctionalInterface
public interface EnvironmentProvider {

    /**
     * 查某个坐标、某个时刻的环境。
     *
     * @param location 查哪儿。调用方要给<b>真实坐标</b>, 而不是"她大概在哪个城市" ——
     *                 区域近似由上游的 {@link Cached} 或装配代码负责
     * @param time     <b>仿真时刻</b>。刻意用仿真时间而不是墙上时钟: 如果她的一天被
     *                 加速 10 倍, "现在几点"这个问题的答案必须跟着快 —— 否则
     *                 她会经历一个"日出在半夜"的世界
     * @return 一份快照。<b>不返回 null</b> —— 拿不到就抛(见类注释的契约)
     * @throws RuntimeException 任何取数失败。上层按"这一轮没刷新"处理, 并保留旧快照
     */
    EnvironmentSnapshot query(Location location, Instant time);

    /**
     * 谁在提供数据 —— 诊断面板与失败日志用。
     *
     * <p>默认实现给"匿名提供方"而不是 {@code getClass().getSimpleName()}:
     * 后者的返回在 lambda 上是空串或一串 {@code $$Lambda$23/0x0000…}, 而把那种东西
     * 打进日志等于没打。需要可读 id 的实现请覆盖它, 或者用 {@link #named}。
     */
    default String providerId() {
        return "匿名提供方";
    }

    /** 一行摘要 —— 日志用。 */
    default String describe() {
        return "环境提供方[" + providerId() + "]";
    }

    // ═══════════════════════════ 现成的实现 ═══════════════════════════

    /**
     * 给一个 lambda 起个名字。
     *
     * <p>存在的理由: 测试与集成里最常见的写法是
     * {@code EnvironmentProvider.named("测试环境", (loc, t) -> snapshot)},
     * 而少了它, 那条提供方在日志里就永远是"匿名提供方" ——
     * 一旦线上出现两条匿名提供方在打架, 日志帮不上任何忙。
     */
    static EnvironmentProvider named(String providerId, EnvironmentProvider delegate) {
        Objects.requireNonNull(delegate, "要命名的提供方不能为空");
        String id = providerId == null || providerId.isBlank() ? "匿名提供方" : providerId;
        return new EnvironmentProvider() {
            @Override
            public EnvironmentSnapshot query(Location location, Instant time) {
                return delegate.query(location, time);
            }

            @Override
            public String providerId() {
                return id;
            }
        };
    }

    /** 一个总是返回同一份快照的提供方 —— 测试与离线演示用。 */
    static EnvironmentProvider fixed(EnvironmentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "固定提供方必须有一份快照");
        return named("固定环境", (location, time) -> snapshot);
    }

    /**
     * 从"坐标 + 时刻 → 快照"的函数造一个提供方。
     *
     * <p>与直接用 lambda 的区别只有一点: 它返回的对象有类型名可读
     * ({@code EnvironmentProvider} 的匿名实现), 在堆栈里比一个合成 lambda 好认。
     * 保留它是因为它让调用点看起来像在表达一个意图("这个提供方就是算出来的"),
     * 而不是在随手塞一个 lambda。
     */
    static EnvironmentProvider of(BiFunction<Location, Instant, EnvironmentSnapshot> supplier) {
        Objects.requireNonNull(supplier, "提供方的函数不能为空");
        return supplier::apply;
    }

    /**
     * <b>缓存包装</b> —— 把真实提供方套住, 避免每 10 分钟把气象 API 打爆。
     *
     * <h2>它为什么必须存在, 而不是"上层自己记一下"</h2>
     * 因为"要不要重抓"这件事的判断依据是<b>时间与地点</b>, 而那是本类独有的知识。
     * 让每个调用点自己记一份 cache, 会得到 N 份各自判断"过期了没有"的实现,
     * 而它们对"过期"的定义会各不相同 —— 表现是"这条链上一小时调了 200 次 API,
     * 那条链一次都没调"。
     *
     * <h2>它的失效条件是<b>两个</b>, 而不是一个</h2>
     * <ol>
     *   <li><b>时间</b>: 距离上次取值超过 {@code ttl};</li>
     *   <li><b>地点</b>: 查询点与缓存的那一次不是同一个城市(见
     *       {@link Location#isSameCityAs})。少了这一条会有一个很具体的 bug ——
     *       她坐高铁到了另一个城市, 而缓存还在把出发地的天气给她,
     *       直到 TTL 到期为止都是。那段时间里她"在下雨的城市里觉得天气很好"。</li>
     * </ol>
     *
     * <h2>失败的处置</h2>
     * 委托抛异常时, 本类<b>原样抛出</b>而不是返回旧快照。理由: "这一轮没刷新"与
     * "刷新了但数据没变"是两件事, 前者要被计数、要被看见(见接口注释的契约)。
     * 旧快照仍然在 {@code Environment} 那边继续生效 —— 它本来就是"上一次成功的结果"。
     */
    final class Cached implements EnvironmentProvider {

        /** 默认刷新间隔 —— 与用户要求一致: "定期执行比如每分钟或每 10 分钟更新一次"。 */
        public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

        private final EnvironmentProvider delegate;
        private final Duration ttl;

        private EnvironmentSnapshot cached;
        private Location cachedAtLocation;
        private Instant cachedAt;

        /** 用默认 TTL(10 分钟)。 */
        public Cached(EnvironmentProvider delegate) {
            this(delegate, DEFAULT_TTL);
        }

        public Cached(EnvironmentProvider delegate, Duration ttl) {
            this.delegate = Objects.requireNonNull(delegate, "被包装的提供方不能为空");
            this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        }

        @Override
        public EnvironmentSnapshot query(Location location, Instant time) {
            Objects.requireNonNull(location, "查环境必须给坐标");
            Objects.requireNonNull(time, "查环境必须给仿真时刻");
            if (usable(location, time)) {
                return cached;
            }
            EnvironmentSnapshot fetched = delegate.query(location, time);
            this.cached = fetched;
            this.cachedAtLocation = location;
            this.cachedAt = time;
            return fetched;
        }

        private boolean usable(Location location, Instant time) {
            if (cached == null || cachedAtLocation == null || cachedAt == null) {
                return false;
            }
            if (!cachedAtLocation.isSameCityAs(location)) {
                return false;
            }
            // 时间<b>倒退</b>也算失效: 仿真时钟可以回拨(重放、回滚),
            // 而"缓存比现在还新"是一个不该被信任的状态
            Duration age = Duration.between(cachedAt, time);
            return !age.isNegative() && age.compareTo(ttl) < 0;
        }

        /** 手动作废 —— 给她"换个地方"之后立刻重抓用。 */
        public void invalidate() {
            this.cached = null;
            this.cachedAtLocation = null;
            this.cachedAt = null;
        }

        /** 现在手上有没有一份可用的缓存 —— 诊断用。 */
        public Optional<EnvironmentSnapshot> peek(Location location, Instant time) {
            return usable(location, time) ? Optional.of(cached) : Optional.empty();
        }

        @Override
        public String providerId() {
            return "缓存(" + delegate.providerId() + ", TTL " + ttl.toMinutes() + " 分钟)";
        }
    }
}
