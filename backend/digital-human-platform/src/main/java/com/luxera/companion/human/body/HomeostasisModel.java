package com.luxera.companion.human.body;

import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.human.body.clothing.ClothingSet;
import com.luxera.companion.human.body.clothing.ThermalInsulation;
import com.luxera.companion.registry.CoreEventCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §3.2.4 —— <b>稳态模型</b>: 把账本上那一堆持续影响, 推进成下一刻的她。
 *
 * <h2>它解决的第一个问题: 账本的量纲</h2>
 * 账本做的运算是"把某条通道上的 magnitude 加起来"(见 {@code ContinuousEffectLedger}),
 * 而"加起来之后怎么变成她的状态"是<b>另一个问题</b>, 并且只能由身体回答 ——
 * 因为只有身体知道"被推一下会动多少"。
 *
 * <p>这里有一个必须说清楚的约定, 它是整条链最容易出错的地方:
 * <table border="1">
 *   <tr><th>账目来源</th><th>{@code magnitude} 的含义</th><th>例子</th></tr>
 *   <tr>
 *     <td>{@code environment.temperature-changed.v1} 等环境强制</td>
 *     <td><b>每秒</b>把她推向某个方向的量(带符号)</td>
 *     <td>{@code -6.5e-4} = 在 0℃ 的户外, 未穿衣每秒失温 0.00065</td>
 *   </tr>
 *   <tr>
 *     <td>{@code body.clothing-changed.v1} 衣物</td>
 *     <td><b>无量纲</b>的保暖系数, 不是速率</td>
 *     <td>{@code +0.85} = 羽绒服(而不是"每秒回温 0.85")</td>
 *   </tr>
 *   <tr>
 *     <td>其它 {@code body.*} 通道上的影响</td>
 *     <td>该通道的每秒变化量</td>
 *     <td>{@code +0.001} = 这一口饭每秒补 0.001 的饥饿值</td>
 *   </tr>
 * </table>
 * 两者混在一起的后果在 {@code ThermalInsulation.asLedgerCoefficient()} 里已经写过:
 * <b>把 0.85 当成每秒 0.85, 她穿上羽绒服后会在一个 tick 内"暖到爆表"</b>。
 *
 * <h3>怎么区分: 靠 {@code cancellationKey}, 不靠通道名</h3>
 * 因为它们<b>同属 {@code body.warmth} 通道</b> —— 账本是按通道求和的, 它不会替我们分类。
 * 但账本保留了每条账目的来源({@code Settlement.why(channel)}), 而衣物的那条账目
 * 有一个全平台唯一的 key: {@code "body.thermal-insulation"}(见
 * {@link ClothingSet#INSULATION_CANCELLATION_KEY})。于是切分是确定的:
 * <pre>{@code
 *   保暖系数 = Σ { key == body.thermal-insulation 的 magnitude }
 *   外部强制 = Σ { 其余全部 magnitude }
 * }</pre>
 *
 * <h3>保暖为什么是<b>乘</b>在失温上的, 而不是加在总量上的</h3>
 * 这是本模型第二个关键决定, 而且它与账本类注释里那句"穿羽绒服(+0.85)和外面 3 度(-0.02)
 * 是可加的"<b>看起来矛盾</b> —— 所以必须解释清楚。
 *
 * <p>"可加"说的是账本<b>记账</b>的层面: 两条影响确实是两个独立的通量, 谁都别想覆盖谁,
 * 所以它们必须能同时挂在同一通道上。这一点我们完全遵守。
 *
 * <p>但"可加"不能直接当<b>物理运算</b>用, 因为衣服不产生热量, 它只是<b>减少散热</b>:
 * <pre>{@code
 *   可加(错):  净变化 = 0.85 + (-0.00065) = +0.84935   ← 穿着羽绒服在零下十度会越来越暖
 *   相乘(对):  净变化 = -0.00065 × (1 − 0.85) = -0.0001 ← 还是会掉, 只是慢得多
 * }</pre>
 * 前者是荒谬的, 而且它会毁掉整个仿真: 只要她一穿衣服, 外面多冷都不再有意义。
 * 后者则给出正确的行为 —— <b>穿衣服让她冷得慢, 但不改变"外面很冷"这件事实</b>。
 * 而用户那句话("多穿衣服……能够持续影响 body 的保暖值")要的正是后者。
 *
 * <h2>它解决的第二个问题: 无遮蔽</h2>
 * {@code ClothingSet.coverageGap()} 给出的"还有多大面积露在外面"在这里被用来
 * <b>放大失温</b>:
 * <pre>{@code
 *   放大系数 = 1 + coverageGap × 0.6
 * }</pre>
 * 露着脖子不会让羽绒服变薄(那是保暖系数的事), 它只是让风从那里进来 ——
 * 所以它作用在同一个乘法链的<b>另一项</b>上。这两件事在本模型里永远是分开的。
 *
 * <h2>它解决的第三个问题: 身体自己会动</h2>
 * 一张只有"外部强制"的表会让仿真变成"她只是一块随环境被动漂移的温度计"。
 * 真人的身体有两个自发过程, 本模型都必须有:
 * <ol>
 *   <li><b>回归(regulation)</b> —— 打哆嗦、血管收缩、出汗。它把她往舒适带的
 *       {@code mid} 拉, 而且<b>有上限</b>({@code MAX_REGULATION_PER_SECOND}):
 *       没有上限的话, 一个增益系数就能让 0℃ 的户外穿短袖也冻不着她 ——
 *       因为"她越冷, 回归力越大"这件事被线性外推了。真人的调节能力是有天花板的,
 *       而那正是"体温过低会死人"的原因;</li>
 *   <li><b>累积(accumulation)</b> —— 饿、渴、困是随时间<b>必然发生</b>的,
 *       不需要任何事件。少了它, 一个没人给她投喂任何事件的 agent 会永远不饿,
 *       而这会让"她中午去吃饭"这件事失去内在动机, 变成纯粹的外部驱动。</li>
 * </ol>
 *
 * <h2>它<b>不</b>拥有的通道</h2>
 * {@code mind.attention-load} 与 {@code mind.mood} 会出现在结算快照里,
 * 但本模型<b>跳过</b>它们(见 {@link #applyForcing} 的开头)。原因见
 * {@link PhysiologicalState#CHANNELS} 的说明: Mind 是兄弟, 不是下游。
 *
 * <h2>时间从哪里来</h2>
 * 全部计算按<b>每秒</b>进行, 而"过了多少秒"由调用方({@link Body})从仿真时钟算出来传进来。
 * 本类<b>绝不</b>读墙上时钟: 同一个仿真跑在加速 60 倍的时间轴上时,
 * 她的生理过程必须跟着仿真时钟走, 而不是跟着机器时间走。
 */
public final class HomeostasisModel {

    // ═══════════════════════ 标定常数 ═══════════════════════

    /**
     * 账本系数 → 每秒变化量的默认换算率。
     *
     * <p>取 {@code 0.0012}: 一条 {@code magnitude = 1.0} 的通道影响, 会让她在一分钟内
     * 变化 0.072 —— 也就是"一分钟能把她从舒适推到不适"。这是个刻意偏慢的值,
     * 因为仿真研究更怕"变化快到看不清"而不是"变化慢到要多跑几个 tick"。
     */
    public static final double DEFAULT_LEDGER_SCALE = 0.0012;

    /**
     * 无遮蔽对失温的<b>放大系数</b>上限。
     *
     * <p>{@code gap = 1.0}(什么都没穿)时失温放大到 1.6 倍 —— 而不是无限大。
     * 因为放大在这里代表的是对流散热, 而对流是有限度的: 没穿衣服的散热当然更快,
     * 但不会比穿了衣服快十倍。
     */
    public static final double UNCOVERED_HEAT_LOSS_PENALTY = 0.6;

    /**
     * 体温调节的<b>比例增益</b>(每秒)。
     *
     * <p>它与 {@link #MAX_REGULATION_PER_SECOND} 一起构成一个带饱和的比例控制器:
     * 偏差越大, 回拉越强 —— 但强不过上限。
     */
    public static final double REGULATION_GAIN_PER_SECOND = 0.004;

    /**
     * 体温调节的<b>上限</b>(每秒) —— 打哆嗦最多能把保暖值每秒拉回这么多。
     *
     * <h3>这个上限就是"冬天穿什么很重要"这件事本身</h3>
     * 真人的调节能力大约能抵消<b>十度左右</b>的额外失温(打哆嗦能让产热翻几倍),
     * 但不是无限的。所以取 {@code 8e-5/s} —— 恰好对应"体感低 10℃"的强制量
     * ({@code 10 × COLD_FORCING_PER_DEGREE})。
     *
     * <p>没有这个上限的后果非常具体: 线性增益会让任何失温都在某个平衡点停住,
     * 越冷回拉越猛, 于是 {@code -20℃} 的户外穿短袖也冻不坏她 ——
     * 而那会让整条"降温 → 觉得冷 → 加衣服"的链在数值上永远不会被触发。
     */
    public static final double MAX_REGULATION_PER_SECOND = 8.0e-5;

    /**
     * 打哆嗦的代价: 每拉回 1 单位保暖值, 消耗多少精力。
     *
     * <p>没有这一项, 体温调节就是免费的午餐 —— 而"她很冷所以很累"是真人最直观的
     * 生理事实之一。取 {@code 0.35}: 持续打哆嗦一小时, 精力掉约
     * {@code 8e-5 × 0.35 × 3600 ≈ 0.10} —— 一个"冻了一下午特别想吃点热的"的量级。
     */
    public static final double SHIVERING_ENERGY_COST = 0.35;

    /** 舒适的环境温度(℃) —— 高于它不产生失温。 */
    public static final double THERMAL_NEUTRAL_CELSIUS = 26.0;

    /**
     * 温度每低于舒适温度 1℃, 每秒增加多少失温系数。
     *
     * <h3>这个数是怎么标定出来的</h3>
     * 它决定"她多久会觉得冷", 所以必须从真人经验反推, 不能随手取一个好看的数。
     * 参照: 一个穿单衣的人站在 0℃ 的户外(有风), 大约 <b>15 分钟</b>开始明显发冷。
     * 于是:
     * <pre>{@code
     *   起点 warmth 0.72 → 下沿 0.45, 需要掉 0.27
     *   0℃ 且有风时体感约 -1℃, 温差约 27℃
     *   扣掉打哆嗦能顶住的那部分(见 MAX_REGULATION_PER_SECOND, 约 10℃ 当量),
     *   净失温速率应约为 0.27 / (15×60) = 3.0e-4 /s
     *   而净速率 = (温差 − 10) × 本系数  →  1.5e-5
     * }</pre>
     * 这个标定同时满足两条必须成立的关系: <b>单衣在 0℃ 会冷</b>(触发那条链),
     * 而<b>羽绒服在 0℃ 不会冷</b>(她是能解决问题的)。任何一个数变了,
     * 这两条关系都可能只剩一条 —— 所以它们被写在这里, 而不是散在代码里。
     */
    public static final double COLD_FORCING_PER_DEGREE = 1.5e-5;

    /**
     * 失温系数的上限(每秒)。
     *
     * <p>温差超过 {@code 53℃}(体感)之后不再增加 —— 相当于 {@code -8e-4/s}。
     * 没有这个上限, {@code -40℃} 的户外会让保暖值在几十秒内归零,
     * 而那是"她还没来得及感觉到冷就已经到底了" —— 一个连刺激都来不及产生的模型。
     */
    public static final double MAX_COLD_FORCING = 8.0e-4;

    /** 风把体感温度往下拉的等效系数 —— 风速 1 m/s 相当于降温 0.6℃。 */
    public static final double WIND_CHILL_PER_METER_PER_SECOND = 0.6;

    /** 每偏离适中值 1 个单位, 核心体温变化多少摄氏度。 */
    public static final double CORE_TEMPERATURE_PER_WARMTH_UNIT = 4.0;

    /** 在舒适带内但离适中值较远时的轻微不适权重 —— 见 {@link #deriveComfort}。 */
    private static final double MILD_DISCOMFORT_WEIGHT = 0.35;

    /**
     * <b>越界的惩罚起点</b> —— 只要真的越界了, 哪怕只越出去一丁点, 惩罚也从这个数起跳。
     *
     * <h3>为什么必须有这个下限</h3>
     * 越界惩罚用的是 {@link ComfortBand#deviation(double)}, 它按"离极值还有多远"归一化 ——
     * 好处是强度随危险程度单调增长, 代价是<b>刚刚越界时它非常小</b>。
     * 保暖值刚掉到 0.44 时 {@code deviation = (0.45 - 0.44) / 0.45 = 0.022},
     * 而同一时刻"她有点累"(疲劳值离适中值远, 但完全在带内)的轻微不适可以有 {@code 0.13}。
     * 于是算出来的综合舒适度会在她<b>越界的同一刻反而上升</b> —— 一个看一眼就假的数。
     *
     * <p>把越界惩罚抬到 {@code [0.5, 1.0]}, 而把带内轻微不适<b>封顶在
     * {@value #MILD_DISCOMFORT_WEIGHT}</b>(见 {@link #penalty}), 就得到一条铁律:
     * <b>任何一条通道真的越界, 都比任何"在带内但不太舒服"更严重</b>。
     * 这条铁律是量纲上的, 不是拍脑袋的常数配比 —— 它保证
     * {@code deviation} 的单调性不会被别的通道的轻微不适盖过去。
     */
    private static final double BREACH_FLOOR = 0.5;

    // ═══════════════════════ 每条通道的动力学 ═══════════════════════

    /**
     * 一条通道怎么随时间演化。
     *
     * @param ledgerScalePerSecond 账本 magnitude → 每秒变化量的换算率
     * @param regulationGain       回归适中值的比例增益(0 = 这条通道不回归)
     * @param accumulationPerSecond 与事件无关的自然累积(饿/渴/困是正的, 精力是负的)
     */
    public record ChannelDynamics(double ledgerScalePerSecond,
                                  double regulationGain,
                                  double accumulationPerSecond) {

        public ChannelDynamics {
            if (ledgerScalePerSecond < 0.0) {
                throw new IllegalArgumentException("换算率不能为负");
            }
            if (regulationGain < 0.0) {
                throw new IllegalArgumentException("回归增益不能为负 —— 负增益意味着身体主动把自己推离适中值");
            }
        }
    }

    /** 一条通道的默认动力学 —— 第三方通道用它, 见 {@link #dynamicsOf}。 */
    public static final ChannelDynamics DEFAULT_DYNAMICS =
            new ChannelDynamics(DEFAULT_LEDGER_SCALE, 0.0, 0.0);

    private static final Map<String, ChannelDynamics> DYNAMICS = buildDynamics();

    private static Map<String, ChannelDynamics> buildDynamics() {
        Map<String, ChannelDynamics> m = new LinkedHashMap<>();
        // 保暖: 强制由"外部降温 + 衣物"专门处理(见 applyThermal), 这里只留回归与换算率
        m.put(CoreEventCatalog.Channels.WARMTH,
                new ChannelDynamics(1.0, REGULATION_GAIN_PER_SECOND, 0.0));
        // 潮湿: 会自己干 —— 回归增益就是"晾干速度"
        m.put(CoreEventCatalog.Channels.WETNESS, new ChannelDynamics(0.0010, 0.0016, 0.0));
        // 精力: 醒着就在掉; 睡觉时由 Life 投正影响补回来
        m.put(CoreEventCatalog.Channels.ENERGY, new ChannelDynamics(0.0015, 0.0, -1.7e-5));
        // 疲劳: 醒着就在涨
        m.put(CoreEventCatalog.Channels.FATIGUE, new ChannelDynamics(0.0015, 0.0006, 2.0e-5));
        // 饿/渴/困: 不回归 —— 吃东西才会不饿, 没有"自己就不饿了"这回事
        m.put(CoreEventCatalog.Channels.HUNGER, new ChannelDynamics(0.0020, 0.0, 4.6e-5));
        m.put(CoreEventCatalog.Channels.THIRST, new ChannelDynamics(0.0025, 0.0, 6.9e-5));
        m.put(CoreEventCatalog.Channels.SLEEP_PRESSURE, new ChannelDynamics(0.0020, 0.0, 1.7e-5));
        // 疼痛: 没有新的致痛源时会自己消退
        m.put(CoreEventCatalog.Channels.PAIN, new ChannelDynamics(0.0040, 0.0030, 0.0));
        // 压力: 会自己平复, 只是比疼痛慢
        m.put(CoreEventCatalog.Channels.STRESS, new ChannelDynamics(0.0018, 0.0012, 0.0));
        // 综合舒适度: 也回归(她倾向于回到还算舒服的底色)
        m.put(CoreEventCatalog.Channels.COMFORT, new ChannelDynamics(0.0015, 0.0010, 0.0));
        return Map.copyOf(m);
    }

    /** 一条通道的动力学。平台没定义过就返回默认(第三方通道不会因为"平台不认识"而停止演化)。 */
    public static ChannelDynamics dynamicsOf(String channel) {
        return DYNAMICS.getOrDefault(channel, DEFAULT_DYNAMICS);
    }

    /** 这条通道是不是 Body 拥有的 —— {@code false} 表示它是 Mind 的, Body 只借过、不改写。 */
    public static boolean owns(String channel) {
        return PhysiologicalState.CHANNELS.contains(channel);
    }

    // ═══════════════════════ 主流程 ═══════════════════════

    /**
     * 推进一个 tick。
     *
     * <h3>顺序不能乱</h3>
     * <pre>
     *   ① 先算热 (warmth 是唯一一条"强制项需要换算"的通道)
     *   ② 再算其余通道 (逐条查表)
     *   ③ 最后派生 (体温/心率/呼吸/舒适度 —— 它们不是独立状态, 是上一步的函数)
     * </pre>
     * 若先算派生量再算状态, 心率反映的会是<b>上一刻</b>的状态, 于是"她害怕时心跳加速"
     * 会晚一个 tick 发生 —— 而在一个分钟级 tick 的仿真里, 这个延迟足以让
     * "先害怕还是先心跳"的因果顺序反掉。
     *
     * @param current       当前状态(不会被改写 —— 它是 {@code record})
     * @param settlement    这一 tick 的账本结算
     * @param deltaSeconds  从上一次推进到现在经过的仿真秒, 必须为正
     * @param clothing      她身上这一身 —— 用来在<b>结算时</b>施加潮湿对保暖的折损,
     *                      见 {@link #effectiveInsulation}
     */
    public PhysiologicalState step(PhysiologicalState current,
                                   ContinuousEffectLedger.Settlement settlement,
                                   double deltaSeconds,
                                   ClothingSet clothing) {
        Objects.requireNonNull(current, "当前生理状态不能为空");
        Objects.requireNonNull(settlement, "账本结算不能为空 —— 没有它就没有任何外部影响");
        Objects.requireNonNull(clothing, "她身上这一身不能为空 —— 空值会让潮湿折损无处可算");
        if (deltaSeconds < 0.0) {
            throw new IllegalArgumentException("时间不能倒流, deltaSeconds = " + deltaSeconds);
        }
        if (deltaSeconds == 0.0) {
            return current;
        }

        PhysiologicalState next = applyThermal(current, settlement, deltaSeconds, clothing);
        next = applyForcing(next, settlement, deltaSeconds);
        next = applyDerived(next);
        return next.clamped();
    }

    // ─────────────────────────── ① 热 ───────────────────────────

    /**
     * 保暖通道: <b>外部强制 × 保暖折损 × 无遮蔽放大, 再加上身体自己的回归</b>。
     *
     * <p>见类注释"保暖为什么是乘在失温上的"。
     */
    private PhysiologicalState applyThermal(PhysiologicalState current,
                                            ContinuousEffectLedger.Settlement settlement,
                                            double deltaSeconds,
                                            ClothingSet clothing) {
        double forcing = externalWarmthForcing(settlement);
        double insulation = effectiveInsulation(settlement, clothing, current.wetness());

        double loss;
        if (forcing >= 0.0) {
            // 外部在<b>加热</b>(暖气、晒太阳、喝热水)——
            // 保暖系数不再起衰减作用, 因为"挡住外面进来的热"不是衣服的职责
            loss = forcing;
        } else {
            double uncoveredFactor = 1.0 + clothing.coverageGap() * UNCOVERED_HEAT_LOSS_PENALTY;
            loss = forcing * (1.0 - insulation) * uncoveredFactor;
        }

        double regulation = regulate(current.warmth(), ComfortBand.warmth());
        double delta = (loss + regulation) * deltaSeconds;
        return current.with(CoreEventCatalog.Channels.WARMTH, current.warmth() + delta);
    }

    /**
     * 账本上属于"外部强制"的那部分保暖影响 —— 也就是<b>除衣物之外</b>的全部。
     *
     * <p>切分依据是 {@code cancellationKey}, 见类注释。
     * {@code Settlement.why(channel)} 提供的正是每条账目的原始事件。
     */
    public static double externalWarmthForcing(ContinuousEffectLedger.Settlement settlement) {
        double sum = 0.0;
        for (StateEffectEvent event : settlement.why(CoreEventCatalog.Channels.WARMTH)) {
            if (!ClothingSet.INSULATION_CANCELLATION_KEY.equals(event.cancellationKey())) {
                sum += event.magnitude();
            }
        }
        return sum;
    }

    /**
     * 有效保暖系数 {@code [0, 1]} —— <b>账本上的系数 × 潮湿折损</b>。
     *
     * <h3>为什么潮湿必须在这里再算一遍, 而不是在穿衣时算好</h3>
     * 因为<b>潮湿会在两次穿衣之间变化</b>。淋雨不产生穿衣事件, 但一件淋湿的羽绒服
     * 的保暖能力会掉一半。若把这个折损固化在 {@code body.clothing-changed.v1} 里,
     * 那么"她淋了雨但没换衣服, 所以系统认为她还是很暖"这件事必然发生 ——
     * 且没有任何一个事件能修正它。
     *
     * <h3>账本上没有衣物账目时, 回退到直接问衣服</h3>
     * 这在两种情况发生: 账本被重建(重放/测试), 或者一个第三方的身体没有投过穿衣事件
     * 却直接给她穿上了衣服。回退到 {@link ClothingSet#insulation(double)} 保证
     * 这两种情况下的行为与正常路径一致, 而不是"她明明穿着羽绒服却算作没穿"。
     */
    public static double effectiveInsulation(ContinuousEffectLedger.Settlement settlement,
                                             ClothingSet clothing,
                                             double wetness) {
        double fromLedger = 0.0;
        boolean present = false;
        for (StateEffectEvent event : settlement.why(CoreEventCatalog.Channels.WARMTH)) {
            if (ClothingSet.INSULATION_CANCELLATION_KEY.equals(event.cancellationKey())) {
                fromLedger += event.magnitude();
                present = true;
            }
        }
        double dry = present
                ? fromLedger
                : clothing.insulation(wetness).asLedgerCoefficient();
        double dryClamped = dry < 0.0 ? 0.0 : Math.min(dry, 1.0);
        // 潮湿折损: 用这一身的综合防水等级, 逐件折损已在 ClothingSet 里做过一次,
        // 这里只负责"账本上那个干燥系数被雨淋掉多少"
        ThermalInsulation degraded =
                ThermalInsulation.of(dryClamped).wetted(wetness, clothing.waterproofLevel());
        return degraded.asLedgerCoefficient();
    }

    /**
     * 体温调节: 把保暖值往舒适带的适中值拉, <b>但有力气上限</b>。
     *
     * @return 每秒的调节量(可正可负)
     */
    private double regulate(double warmth, ComfortBand band) {
        double error = band.mid() - warmth;
        if (error == 0.0) {
            return 0.0;
        }
        double proportional = Math.abs(error) * REGULATION_GAIN_PER_SECOND;
        return Math.signum(error) * Math.min(proportional, MAX_REGULATION_PER_SECOND);
    }

    // ─────────────────────────── ② 其余通道 ───────────────────────────

    /**
     * 逐条通道施加影响。
     *
     * <h3>三条规则</h3>
     * <ol>
     *   <li>不是 {@code body.} 前缀的通道<b>跳过</b> —— {@code mind.*} 属于 Mind(见类注释),
     *       第三方的 {@code environment.*} / {@code device.*} 也不该由她来结算;</li>
     *   <li>{@code body.warmth} 跳过 —— 它已经在 {@link #applyThermal} 里算过了,
     *       再算一次就是重复计入, 而那会让"她冷得比应该的快一倍";</li>
     *   <li>其余通道(包括平台不认识的 {@code body.xxx})一律
     *       {@code Δ = (账本值 × 换算率 + 回归 + 累积) × Δt}。</li>
     * </ol>
     *
     * <h3>一条从没见过的通道, 起点在哪</h3>
     * 取它舒适带的适中值({@code ComfortBand.forChannel} 对未知通道给的是宽松带)。
     * 而不是取 0 —— 取 0 会让一条第三方通道在她身上第一次出现时, 直接表现为
     * "这条通道的值是极端低的", 于是她的行为会在毫无理由的情况下改变一次。
     * 从适中值开始, 是"我们不知道它原本是多少"这件事最保守的表达。
     */
    private PhysiologicalState applyForcing(PhysiologicalState current,
                                            ContinuousEffectLedger.Settlement settlement,
                                            double deltaSeconds) {
        PhysiologicalState next = current;
        for (Map.Entry<String, Double> entry : settlement.totals().entrySet()) {
            String channel = entry.getKey();
            if (channel == null || !channel.startsWith("body.")) {
                continue;
            }
            if (CoreEventCatalog.Channels.WARMTH.equals(channel)) {
                continue;
            }
            double value = next.maybeValue(channel)
                    .orElseGet(() -> ComfortBand.forChannel(channel).mid());
            double dynamicsDelta = forcingDelta(channel, entry.getValue(), value, deltaSeconds);
            next = next.with(channel, value + dynamicsDelta);
        }
        return next;
    }

    /** 单条通道在一个 tick 里的变化量 —— 见 {@link #applyForcing}。 */
    private double forcingDelta(String channel, double ledgerMagnitude, double value,
                                double deltaSeconds) {
        ChannelDynamics d = dynamicsOf(channel);
        double forced = ledgerMagnitude * d.ledgerScalePerSecond();

        double regulation = 0.0;
        if (d.regulationGain() > 0.0) {
            ComfortBand band = ComfortBand.forChannel(channel);
            regulation = (band.mid() - value) * d.regulationGain();
        }

        double accumulation = d.accumulationPerSecond();
        return (forced + regulation + accumulation) * deltaSeconds;
    }

    // ─────────────────────────── ③ 派生量 ───────────────────────────

    /**
     * 派生量与状态的关系是<b>函数</b>, 不是另一份独立状态。
     *
     * <p>把它们写成字段(而不是每次现算)是为了落库与展示方便, 但它们的值必须
     * <b>每一次都从上面那些通道重算</b>。这是"注意力负荷不该有两份真相"的同一条原则
     * (见 {@link PhysiologicalState#CHANNELS}): 一个可以被单独写入的心率,
     * 迟早会与她的实际处境不一致。
     */
    private PhysiologicalState applyDerived(PhysiologicalState s) {
        ComfortBand warmthBand = ComfortBand.warmth();
        double coldStress = Math.max(0.0, warmthBand.mid() - s.warmth())
                / Math.max(warmthBand.mid() - warmthBand.low(), 1e-6);
        double heatStress = Math.max(0.0, s.warmth() - warmthBand.high())
                / Math.max(warmthBand.high() - warmthBand.mid(), 1e-6);

        double coreTemperature = PhysiologicalState.NORMAL_CORE_TEMPERATURE
                - (warmthBand.mid() - s.warmth()) * CORE_TEMPERATURE_PER_WARMTH_UNIT;

        double heartRate = PhysiologicalState.RESTING_HEART_RATE
                + s.stress() * 22.0
                + Math.min(1.0, coldStress) * 28.0     // 血管收缩 + 打哆嗦
                + Math.min(1.0, heatStress) * 25.0     // 散热
                + s.fatigue() * 8.0
                + s.pain() * 12.0;

        double breathingRate = PhysiologicalState.RESTING_BREATHING_RATE
                + s.stress() * 8.0
                + Math.min(1.0, coldStress) * 6.0
                + s.fatigue() * 3.0;

        // 打哆嗦是要烧精力的 —— 见 SHIVERING_ENERGY_COST
        double shiveringCost = Math.min(1.0, coldStress) * MAX_REGULATION_PER_SECOND
                * SHIVERING_ENERGY_COST;
        double energy = s.energy() - shiveringCost;

        return s.with(CoreEventCatalog.Channels.ENERGY, energy)
                .with(CoreEventCatalog.Channels.COMFORT, deriveComfort(s))
                .withCoreTemperature(coreTemperature)
                .withHeartRate(heartRate)
                .withBreathingRate(breathingRate);
    }

    /**
     * 综合舒适度 —— 由<b>其余九条通道</b>算出来, 而不是独立演化。
     *
     * <pre>{@code
     *   越界通道: 惩罚 = deviation(v)                       (0 到 1, 越界越深惩罚越大)
     *   带内通道: 惩罚 = 0.35 × |v − mid| / 半带宽          (离适中值越远越不适, 但很轻)
     *   舒适度   = 1 − ( 0.6 × 最大惩罚 + 0.4 × 平均惩罚 )
     * }</pre>
     * 带内为什么还要有一点惩罚: 因为"刚好在带内"与"正舒服"是两件事。
     * 保暖值 0.46 与 0.65 都在舒适带里, 但只有后者是"不冷不热"。
     * 少了这一项, 综合舒适度会在带内恒为 1.0, 而"她有点不自在"就没法表达。
     *
     * <h3>为什么是"最大与平均的加权", 而不是二选一</h3>
     * <b>只用平均</b>会得到一个被稀释掉的数: 九条通道里有一条彻底越界(惩罚 1.0),
     * 平均下来只有 0.11 —— 于是"她快冻僵了"与"她有点无聊"得到同一个舒适度,
     * 而这是这个指标最不能犯的错。
     * <p><b>只用最大</b>则会丢掉"糟糕的程度"这个维度: "又冷又饿"与"只是冷"完全同分,
     * 而这两者的区别正是她接下来会先做什么。
     * <p>加权两者同时保住: 最大项保证<b>任何一条通道越界都会立刻压住整体</b>,
     * 平均项保证<b>越界条数越多越糟</b>。{@code 0.6/0.4} 的配比让前者的作用更直接 ——
     * 一条通道的灾难不该被八条正常的通道摊薄。
     */
    private double deriveComfort(PhysiologicalState s) {
        double maxPenalty = 0.0;
        double totalPenalty = 0.0;
        int count = 0;
        for (String channel : PhysiologicalState.CHANNELS) {
            if (CoreEventCatalog.Channels.COMFORT.equals(channel)) {
                continue;
            }
            double p = penalty(channel, s.value(channel));
            maxPenalty = Math.max(maxPenalty, p);
            totalPenalty += p;
            count++;
        }
        if (count == 0) {
            return 1.0;
        }
        double blended = 0.6 * maxPenalty + 0.4 * (totalPenalty / count);
        return Math.max(0.0, 1.0 - blended);
    }

    /**
     * 一条通道的不适程度。
     *
     * <pre>{@code
     *   越界: penalty = BREACH_FLOOR + deviation × (1 − BREACH_FLOOR)   ∈ [0.50, 1.00]
     *   带内: penalty = MILD_DISCOMFORT_WEIGHT × |v − mid| / 半带宽     ∈ [0.00, 0.35]
     * }</pre>
     *
     * <p>两个区间<b>不重叠且不连续</b>: 越界那一段整体在带内那一段之上,
     * 中间留出 {@code 0.15} 的空档。这不是疏忽, 而是刻意留的余量 ——
     * 它保证"越界"这件事在任何数值误差下都不会被误判成"带内轻微不适"。
     */
    private double penalty(String channel, double value) {
        ComfortBand band = ComfortBand.forChannel(channel);
        double deviation = band.deviation(value);
        if (deviation > 0.0) {
            return BREACH_FLOOR + deviation * (1.0 - BREACH_FLOOR);
        }
        double halfWidth = Math.max(band.high() - band.mid(), band.mid() - band.low());
        if (halfWidth <= 1e-9) {
            return 0.0;
        }
        return MILD_DISCOMFORT_WEIGHT * Math.abs(value - band.mid()) / halfWidth;
    }

    // ═══════════════════════ 给世界侧用的标定参考 ═══════════════════════

    /**
     * 给定气温与风速, 一个<b>没有穿衣服</b>的人在保暖通道上应当被施加多大的每秒强制。
     *
     * <h3>它为什么在 Body 里, 而不在 World 里</h3>
     * 因为"这个温度对她来说冷得多快"是<b>她的身体属性</b>, 不是天气的属性。
     * 天气只给出气温与风速这两个物理量; 把它们折算成失温速率需要知道
     * {@link #THERMAL_NEUTRAL_CELSIUS} 与 {@link #COLD_FORCING_PER_DEGREE} ——
     * 这两个数换一个物种(比如一只猫)就该不一样。
     *
     * <p>它是 {@code public static} 的, 因为世界侧在投
     * {@code environment.temperature-changed.v1} 时需要这个数(见那条事件的语义:
     * "气温低于舒适带时 magnitude 为负")。世界侧<b>调用</b>这个函数,
     * 而不是自己再写一套 —— 两边各写一套的结果是天气说她冷、身体说她不冷。
     *
     * @param celsius    气温(℃)
     * @param windSpeed  风速(m/s)
     * @return 每秒的保暖变化量, {@code <= 0}
     */
    public static double referenceThermalForcing(double celsius, double windSpeed) {
        double feelsLike = celsius - Math.max(0.0, windSpeed) * WIND_CHILL_PER_METER_PER_SECOND;
        double below = THERMAL_NEUTRAL_CELSIUS - feelsLike;
        if (below <= 0.0) {
            return 0.0;
        }
        return -Math.min(MAX_COLD_FORCING, below * COLD_FORCING_PER_DEGREE);
    }

    /** 供诊断: 当前的标定常数一览 —— 调参时最先要看的东西。 */
    public static Map<String, Double> calibration() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("thermalNeutralCelsius", THERMAL_NEUTRAL_CELSIUS);
        m.put("coldForcingPerDegree", COLD_FORCING_PER_DEGREE);
        m.put("maxColdForcing", MAX_COLD_FORCING);
        m.put("windChillPerMeterPerSecond", WIND_CHILL_PER_METER_PER_SECOND);
        m.put("uncoveredHeatLossPenalty", UNCOVERED_HEAT_LOSS_PENALTY);
        m.put("regulationGainPerSecond", REGULATION_GAIN_PER_SECOND);
        m.put("maxRegulationPerSecond", MAX_REGULATION_PER_SECOND);
        m.put("shiveringEnergyCost", SHIVERING_ENERGY_COST);
        m.put("coreTemperaturePerWarmthUnit", CORE_TEMPERATURE_PER_WARMTH_UNIT);
        return Map.copyOf(m);
    }
}
