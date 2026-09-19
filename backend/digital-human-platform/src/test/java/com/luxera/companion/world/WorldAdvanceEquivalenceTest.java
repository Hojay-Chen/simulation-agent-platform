package com.luxera.companion.world;

import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.registry.EventHandlerRegistry;
import com.luxera.companion.world.device.AudioSystem;
import com.luxera.companion.world.device.BatterySystem;
import com.luxera.companion.world.device.Device;
import com.luxera.companion.world.device.NotificationSystem;
import com.luxera.companion.world.device.Phone;
import com.luxera.companion.world.device.ScreenSystem;
import com.luxera.companion.world.device.VibrationSystem;
import com.luxera.companion.world.digital.Location;
import com.luxera.companion.world.digital.Place;
import com.luxera.companion.world.environment.Environment;
import com.luxera.companion.world.environment.EnvironmentProvider;
import com.luxera.companion.world.environment.EnvironmentSnapshot;
import com.luxera.companion.world.environment.EnvironmentSnapshot.WeatherCondition;
import com.luxera.companion.world.object.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §8.5.10 —— <b>{@code advance} 的等价性</b>: 把一步拆成三跳, 世界必须一模一样。
 *
 * <h2>它要证明的是什么</h2>
 * §8.5.3 把 {@code World.advance} 拆成了 {@code advanceDevices} +
 * {@code fetchEnvironments} + {@code applyEnvironments}, 并保留了 {@code advance}
 * 本身(离线回放要用它, 已有测试也钉着它)。于是有一个必须被证明的承诺:
 * <b>"合成的那一条"与"拆开的那一条"会让世界走到同一个地方</b>。
 * 在这条测试之前, {@code world/} 下<b>一条测试都没有</b> —— 那个承诺是论证, 不是证据
 * (§8.5.10 的原话)。
 *
 * <h2>它怎么证明</h2>
 * 同一起点造两个世界, 喂同一个(纯函数式的)气象提供方, 然后:
 * <pre>{@code
 *   世界 A:  world.advance(elapsed, now)                      ← 合成的那一条
 *   世界 B:  advanceDevices(elapsed, now)                     ← 拆开的那一条,
 *            fetchEnvironments(now)                           取
 *            applyEnvironments(readings, now)                 用
 *                     ↓
 *            assert 两份摘要逐字段相等(见 splitStep: 它按 advance 里那个式子拼)
 * }</pre>
 * 每一步都比一次摘要, 循环结束后再比: 最终状态({@code describe()} 里的推进次数、
 * 投出条数、失败数)、每一个环境的现状({@code Environment.describe()} 里的快照 +
 * 刷新次数 + 失败次数)、以及<b>她收到的事件序列</b>。
 *
 * <h2>场景为什么这么搭(每一条都是为了让等价性不空转)</h2>
 * <ul>
 *   <li><b>步长 = 环境刷新间隔(10 分钟)</b>: 世界 A 的 {@code refreshDue} 门在
 *       "刚好到期"的那一刻打开, 于是两条路径刷的是<b>同一组时刻</b>。
 *       步长若小于间隔, A 会跳过若干次而 B 不会 —— 那测的就不是等价性了,
 *       而是"两条路径的节奏不同"这个已知且刻意的事实;</li>
 *   <li><b>提供方是 {@code now} 的纯函数</b>: 两个世界问到的数据必须一样,
 *       否则比较的是两个天气。相位每 10 分钟翻一格, 于是气温、降水、天气分类
 *       都会真的变——{@code TemperatureChanged} / {@code WeatherChanged} /
 *       {@code RainStarted} 都在这 24 步里出现;</li>
 *   <li><b>有一个相位取不到数据</b>({@code BLACKOUT_PHASE}): 失败那条路必须被走到。
 *       它是最容易在拆分里走样的一条 —— 世界那一侧的 {@code failedRefreshCount}
 *       与<b>环境自己</b>的 {@code failureCount} 分居两跳, 抄错一个数不会报任何异常;</li>
 *   <li><b>中途搬一次家</b>: {@code placeHumanAt} 会立刻查一次新地点的天气
 *       (一条<b>单跳</b>路径, 见它的第 ③ 步)。搬家之后两个世界的门表
 *       ({@code lastEnvironmentAttemptAt})必须仍然同构, 否则下一个边界就会分叉;</li>
 *   <li><b>手机的耗电速率被调快</b>(出厂值要 96 小时才掉到低位):
 *       设备那一半必须真的产生事件。两个世界都只产生"没有设备事件"的推进时,
 *       比较设备事件数就永远是在比两个 0;</li>
 *   <li><b>第 5 步故意不传 {@code elapsed}</b>: 走 {@code inferredStep} 那条推算路径 ——
 *       而"上一次推进时刻归谁管"正是拆分里最要命的那个判断(见
 *       {@code advanceDevices} 的注释: 归错了, 她的手机电池会一次掉 10 分钟的电)。</li>
 * </ul>
 *
 * <h2>这条测试<b>不</b>覆盖什么(如实写在这里, 免得它看起来覆盖了全部)</h2>
 * <ul>
 *   <li><b>并发。</b>这里两个世界都是单线程地被驱动的。§8.5.9 的真正主张是
 *       "取在刷新线程、用在仿真线程", 而这条测试<b>一句都没有证明</b>它 ——
 *       两条线程的可见性问题(那两张 copy-on-write 表)没有任何测试,
 *       它靠的是注释里写明的理由与将来的接线审查;</li>
 *   <li><b>投出去之后她怎么反应。</b>fabric 是真的
 *       ({@link DefaultEventFabric}), 但里面<b>没有注册任何 handler</b>、
 *       也没有 Human / HumanRuntimeContext / 认知链 / 台账结算 ——
 *       这条测试只证明"两个世界投出了同一条事件流", 不证明那条流被谁消费成了什么;</li>
 *   <li><b>节奏本身。</b>两个世界都在同一组时刻上被手动驱动, 所以
 *       {@code EnvironmentRefreshJob}(边界对齐、重入、失败处置)不在这里 ——
 *       那是 {@code runtime.EnvironmentRefreshJobTest} 的事;</li>
 *   <li><b>事件序列只比最近的那些。</b>{@code DefaultEventFabric} 的历史有上限
 *       ({@code HISTORY_LIMIT}); 本场景的事件数远低于它, 而代码里有一条
 *       兜底断言钉着这一点(见 {@code assertTrue(wholeTimeline.size() < HISTORY_LIMIT)});</li>
 *   <li><b>时间倒退</b>({@code advanceDevices} 里那条 WARN 分支)没有被覆盖 ——
 *       两条路径都会走它, 但没有一条被这一步触发。</li>
 * </ul>
 */
class WorldAdvanceEquivalenceTest {

    /** 仿真起点 —— 也是 {@code SimulationClock.floorTo} 的对齐基准。 */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 一步的长度 = 环境刷新间隔: 见类注释"场景为什么这么搭"的第一条。 */
    private static final Duration STEP = Duration.ofMinutes(10);

    /** 走多少步 —— 24 步 = 4 小时, 足够让气温/天气/电量都跨过阈值。 */
    private static final int STEPS = 24;

    /** 第几步搬家(1 起算)。 */
    private static final int MOVE_AT_STEP = 9;

    /** 哪一步不传 {@code elapsed}, 走"推算"那条路(1 起算)。 */
    private static final int INFERRED_STEP_AT = 5;

    /** 气象数据链"挂掉"的相位 —— 它由 {@code now} 决定, 于是两个世界同时经历它。 */
    private static final long BLACKOUT_PHASE = 3L;

    /** 提供方的相位长度(秒)—— 与 {@link #STEP} 一致, 于是每一步的天气都不同。 */
    private static final long PHASE_SECONDS = 600L;

    /** 一个世界 + 它那条通道。 */
    private record Setup(World world, EventFabric fabric, Place home, Place lab,
                         Environment homeEnvironment, Environment labEnvironment) {
    }

    @Test
    void walkingAdvanceAndWalkingTheThreeEntriesProduceTheSameWorld() {
        EnvironmentProvider provider = EnvironmentProvider.of((point, now) -> {
            if (phaseOf(now) == BLACKOUT_PHASE) {
                throw new IllegalStateException("气象 API 超时(测试里的一次确定性故障)");
            }
            return snapshotAt(now);
        });

        Setup whole = assembled(provider);
        Setup split = assembled(provider);

        int deviceEvents = 0;
        int environmentEvents = 0;

        for (int step = 1; step <= STEPS; step++) {
            Instant now = T0.plus(STEP.multipliedBy(step));
            Duration elapsed = step == INFERRED_STEP_AT ? null : STEP;

            World.AdvanceReport walked = whole.world().advance(elapsed, now);
            World.AdvanceReport spliced = splitStep(split.world(), elapsed, now);

            // ① 摘要逐字段相同 —— 这是 §8.5.10 点名要的那一条断言
            assertEquals(walked, spliced,
                    "第 " + step + " 步 @" + now + " 的摘要必须逐字段相同");

            deviceEvents += walked.deviceEvents();
            environmentEvents += walked.environmentEvents();

            if (step == MOVE_AT_STEP) {
                // 两个世界搬同样的家, 在同一个时刻 —— 走的是那条"到了就立刻查一次"的单跳路径
                whole.world().placeHumanAt("h1", whole.lab().id().value(), now, "去实验室");
                split.world().placeHumanAt("h1", split.lab().id().value(), now, "去实验室");
            }
        }

        // ② 非空转断言: 场景必须真的发生过我们想比的那些事。
        //    没有这三条, 一个"两边都什么都没做"的世界会让上面每一条断言都绿着
        assertTrue(deviceEvents > 0,
                "设备那一半必须真的产生过事件, 否则比较的是两个 0: 实际 " + deviceEvents);
        assertTrue(environmentEvents > 0,
                "环境那一半必须真的投出过事件: 实际 " + environmentEvents);
        assertTrue(whole.world().failedRefreshCount() > 0,
                "场景里必须真的取数失败过 —— 否则'失败也等价'那一半没被走到: 实际 "
                        + whole.world().failedRefreshCount());
        assertTrue(whole.world().failedRefreshCount() == split.world().failedRefreshCount());

        // ③ 最终状态: 推进次数、上一次推进时刻、投出条数、失败数
        assertEquals(whole.world().advanceCount(), split.world().advanceCount());
        assertEquals(whole.world().lastAdvanceAt(), split.world().lastAdvanceAt(),
                "上一次推进时刻归设备那一半 —— 两条路径必须落在同一个时刻上");
        assertEquals(whole.world().publishedEventCount(), split.world().publishedEventCount());
        assertEquals(whole.world().describe(), split.world().describe(), "世界一览必须一模一样");

        // ④ 每个环境的现状: 快照 + 刷新次数 + 失败次数(Environment.describe 这三样都带)
        assertEquals(whole.homeEnvironment().describe(), split.homeEnvironment().describe());
        assertEquals(whole.labEnvironment().describe(), split.labEnvironment().describe());
        assertTrue(whole.labEnvironment().refreshCount() > 1,
                "搬家之后的那些边界必须真的刷过实验室 —— 否则第 ③ 步的等价性是空的");

        // ⑤ 她收到的事件序列 —— 逐条相同
        List<String> wholeTimeline = timeline(whole.fabric());
        List<String> splitTimeline = timeline(split.fabric());
        assertTrue(wholeTimeline.size() < HISTORY_LIMIT,
                "事件数超过 fabric 的历史上限时, 这条比较会漏掉被挤掉的那些: "
                        + wholeTimeline.size());
        assertEquals(wholeTimeline, splitTimeline, "她看到的时间轴必须逐条相同");
    }

    // ─────────────────────────── 被测的那一步 ───────────────────────────

    /**
     * 把"拆开的那一条"走一步, 再按 {@code advance} 里那个式子拼出一份同形的摘要。
     *
     * <p>这个拼法是这条测试的被测方之一: 它与 {@code World.advance} 里的那一行
     * 是同一个式子, 谁改了那边而没有改这边, 这条测试就会红 ——
     * 而"两份摘要合起来的字段必须齐全"正是拆分时那个设计问题(§8.5.3)的答案。
     */
    private static World.AdvanceReport splitStep(World world, Duration elapsed, Instant now) {
        World.AdvanceReport deviceHalf = world.advanceDevices(elapsed, now);
        List<World.EnvironmentReading> readings = world.fetchEnvironments(now);
        World.EnvironmentReport environmentHalf = world.applyEnvironments(readings, now);
        return new World.AdvanceReport(deviceHalf.elapsed(), now,
                deviceHalf.deviceEvents(), deviceHalf.deviceFailures(),
                environmentHalf.refreshes(), environmentHalf.events(),
                environmentHalf.failures(), environmentHalf.humansNotified());
    }

    // ─────────────────────────── 装配 ───────────────────────────

    /**
     * 造一个世界: 两台地点(各自带一个环境) + 一台手机 + 一条通道, 她一开始在家。
     *
     * <p>用<b>真的</b> {@link Environment.Default} 与真的 {@link Phone}, 而不是替身:
     * 这条测试要证明的正是"世界这一侧的组合方式没变", 而替身会把被证的那部分替掉。
     * 唯一的替身是提供方 —— 它本来就是外部世界, 而这里需要一个确定性的它。
     */
    private static Setup assembled(EnvironmentProvider provider) {
        EventFabric fabric = new DefaultEventFabric("h1", new EventHandlerRegistry());

        Location homeAt = Location.of(31.2304, 121.4737, "Asia/Shanghai", "上海·家");
        Place home = Place.indoor("home", "上海的家", homeAt);
        Environment homeEnvironment = new Environment.Default(
                ObjectId.of(Environment.ID_NAMESPACE, "home-env"), home.id(), homeAt,
                provider, EnvironmentSnapshot.mild(20.0, T0));
        home.attachEnvironment(homeEnvironment);

        Location labAt = Location.of(31.2011, 121.4403, "Asia/Shanghai", "上海·实验室");
        Place lab = Place.indoor("lab", "实验室", labAt);
        Environment labEnvironment = new Environment.Default(
                ObjectId.of(Environment.ID_NAMESPACE, "lab-env"), lab.id(), labAt,
                provider, EnvironmentSnapshot.mild(21.0, T0));
        lab.attachEnvironment(labEnvironment);

        Phone phone = aPhone(fabric);
        // 出厂耗电率是 0.01/小时 —— 从满电掉到低位要 96 小时, 而这条测试只走 4 小时。
        // 调快它, 是为了让"电量跨阈值"这条设备事件真的发生(见类注释)
        phone.battery().setDrainPerHour(0.5);

        World world = new World()
                .addDevice(phone)
                .addPlace(home).addPlace(lab)
                .addEnvironment(homeEnvironment).addEnvironment(labEnvironment)
                .bind("h1", fabric);
        // 她一开始就在家 —— 这一下会做一次"到了就立刻查天气", 两个世界做的是同一件事
        world.placeHumanAt("h1", home.id().value(), T0, "装配");
        return new Setup(world, fabric, home, lab, homeEnvironment, labEnvironment);
    }

    /**
     * 一台出厂状态的手机 —— 六个部件显式拼起来, <b>不走 {@code Phone.standard(...)}</b>。
     *
     * <p>不走它的原因不是偏好, 是它现在<b>一调就抛</b>: {@code Phone.standard} 用
     * {@code null} 当通知系统去调那个"先造壳、再补通知系统"的公开构造器, 而那个构造器
     * 有一句 {@code Objects.requireNonNull(notifications, "手机必须有通知系统")}
     * ({@code world/device/Phone.java:176}, 调用点在 {@code :195}) ——
     * 于是它必然 {@code NullPointerException}。这是与本次拆分<b>无关</b>的既有缺陷
     * (全仓只有它自己一处装配点, 所以没有别的测试撞上过), 交付说明里单独报了上来,
     * 不在这里顺手改: 它在别人的文件里, 而"装配一台手机"的最终形态属于装配那一层。
     *
     * <p>这里把六个部件写成显式装配, 于是这条测试对"手机到底是什么"没有隐藏假设:
     * 四路典型音量、振动开、锁屏、满电 —— 与 {@code standard} 想要的一样。
     * 唯一"不像真货"的地方是那个 {@code SignalContext}: 通知系统问它"手机离她多近、
     * 还有没有电", 而这里给的是常量距离 + <b>真的</b>电池。它不影响本测试的任何一个断言
     * (本场景没有任何一条通知进来), 而它之所以要存在, 是因为通知系统<b>不肯</b>收一个
     * 空的上下文 —— 与手机那个构造器同一条风格。
     */
    private static Phone aPhone(EventFabric fabric) {
        ObjectId phoneId = ObjectId.of("world.device", "phone");
        AudioSystem audio = new AudioSystem.Default(fabric, phoneId.value(), Map.of());
        VibrationSystem vibration = new VibrationSystem.Default(true);
        ScreenSystem screen = new ScreenSystem.Default(fabric, phoneId.value(), true);
        BatterySystem battery = new BatterySystem.Default(fabric, phoneId.value(),
                Device.PowerState.FULL);
        NotificationSystem notifications = new NotificationSystem.Default(
                fabric, phoneId.value(), audio, vibration, screen,
                new NotificationSystem.SignalContext() {
                    @Override
                    public String proximity() {
                        return Device.Proximity.DESK;
                    }

                    @Override
                    public Device.PowerState power() {
                        return battery.power();
                    }
                });
        return new Phone(fabric, phoneId, "她的手机", audio, vibration, screen, battery,
                notifications, Device.Proximity.DESK);
    }

    // ─────────────────────────── 气象提供方(纯函数) ───────────────────────────

    /** 当前落在哪个 10 分钟相位 —— 由 {@code now} 决定, 于是两个世界永远看到同一个相位。 */
    private static long phaseOf(Instant now) {
        return ((now.getEpochSecond() - T0.getEpochSecond()) / PHASE_SECONDS) % 6;
    }

    /**
     * 这个相位里"外面"是什么样 —— <b>只依赖 {@code now}</b>。
     *
     * <p>这是整个测试可比的根基: 两个世界在同一个时刻问到的必须是同一份数据,
     * 否则比的是两份天气。相位之间的跳变刻意跨过各个阈值(气温 1.6~4.2℃、
     * 降水 0 → 3mm/h、晴 → 雨), 于是气温 / 天气分类 / 降水这几类事件都会真的发生。
     * (湿度、风速、AQI、日照刻意保持不变 —— 这条路要证的是"两条路径等价",
     * 不是"每一条事件类型都能被投出来"; 让它们也一起变只会让失败信息更难读。)
     */
    private static EnvironmentSnapshot snapshotAt(Instant now) {
        long phase = phaseOf(now);
        double temperature;
        double precipitation;
        WeatherCondition condition;
        switch ((int) phase) {
            case 0:
                temperature = 20.0;
                precipitation = 0.0;
                condition = WeatherCondition.CLEAR;
                break;
            case 1:
                temperature = 22.6;
                precipitation = 0.0;
                condition = WeatherCondition.CLEAR;
                break;
            case 2:
                temperature = 22.6;
                precipitation = 3.0;
                condition = WeatherCondition.RAIN;
                break;
            case 4:
                temperature = 18.4;
                precipitation = 1.2;
                condition = WeatherCondition.RAIN;
                break;
            default:
                temperature = 18.4;
                precipitation = 0.0;
                condition = WeatherCondition.PARTLY_CLOUDY;
                break;
        }
        return EnvironmentSnapshot.builder()
                .temperatureCelsius(temperature)
                .humidity(0.5)
                .windSpeed(1.0)
                .precipitation(precipitation)
                .cloudCover(condition == WeatherCondition.CLEAR ? 0.1 : 0.7)
                .illuminanceLux(3000.0)
                .aqi(50)
                .condition(condition)
                .observedAt(now)
                .build();
    }

    // ─────────────────────────── 比较用的小工具 ───────────────────────────

    /**
     * 她收到的时间轴 —— 每条事件一行文本(<b>newest-first</b>, 与 {@code recentEvents} 一致)。
     *
     * <p>比较用 {@code describe()} 而不是事件对象的 {@code equals}: {@code WorldEvent}
     * 是一个接口, 它<b>没有</b>要求实现类是 record(设备事件、环境事件、地点事件各是
     * 各自包里的类型)。而 {@code describe()} 是被接口文档要求"写上关键坐标"的那一面
     * (类型 + 时刻 + 来源 + 载荷里的坐标), 于是它既是一次真的比较, 也能在失败时
     * 直接读出"是哪一条、差在哪里" —— 一个 {@code assertEquals} 打在两个对象上时,
     * 报错信息里往往只有一个默认的 {@code toString}。
     */
    private static List<String> timeline(EventFabric fabric) {
        List<String> lines = new ArrayList<>();
        for (WorldEvent event : fabric.recentEvents(HISTORY_LIMIT)) {
            assertNotNull(event.occurredAt(), "世界事件必须带时刻");
            lines.add(event.describe());
        }
        return lines;
    }

    /** {@code DefaultEventFabric} 的历史上限 —— 见类注释最后一段的兜底断言。 */
    private static final int HISTORY_LIMIT = 256;
}
