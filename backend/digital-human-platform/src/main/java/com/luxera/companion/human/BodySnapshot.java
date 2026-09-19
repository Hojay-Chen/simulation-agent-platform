package com.luxera.companion.human;

import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.body.PhysiologicalState;
import com.luxera.companion.human.body.senses.SensoryChannel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §3.1.4 —— <b>她身体怎么样</b>。一张某一刻的读数表, 不是 {@code Body} 本人。
 *
 * <h2>为什么给快照, 不给 Body</h2>
 * 两个理由见 {@link HumanContext}: <b>并发</b>（actor 线程在写、HTTP 线程在读,
 * 而 {@code Body.advance()} 是多步写入）与<b>方向</b>（活对象带出去, 迟早有人加
 * 一个绕过账本的 setter, 于是"她为什么冷"再也答不出来）。这里只补一条
 * 只属于 Body 的: <b>{@code Body} 上有一堆"会改变她"的方法</b>
 * （{@code feel} / {@code apply} / {@code wear} / {@code markMeal} / {@code advance}）——
 * 把 Body 交出去等于把"谁让她冷的"这个问题同时交给了所有人。
 *
 * <h2>它装什么: 够画一页控制台, 且每一格都能追溯到源头</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>控制台上是什么</th></tr>
 *   <tr><td>{@link #state()}</td><td>"她现在的体温/保暖/精力/饿" —— 十条通道的数字</td></tr>
 *   <tr><td>{@link #lastDelta()}</td><td>"这一秒她朝哪个方向在变" —— 趋势箭头</td></tr>
 *   <tr><td>{@link #lastSettlement()}</td><td>"她为什么冷" —— 账本结算, 每个通道是谁造成的</td></tr>
 *   <tr><td>{@link #senses()}</td><td>"她耳朵灵不灵、还有多少刺激没被处理"</td></tr>
 *   <tr><td>{@link #thresholds()}</td><td>"她身上的报警灯亮着哪些"</td></tr>
 *   <tr><td>{@link #clothing()}</td><td>"她穿着什么、保暖/防水/透气是多少"</td></tr>
 *   <tr><td>{@link #recentStates()}</td><td>最近几条状态 —— 那根曲线的尾巴</td></tr>
 *   <tr><td>{@link #lastTickAt()} / {@link #lastMealAt()}</td>
 *       <td>"她的身体最后一次被推进是什么时候"、"上一顿是什么时候"</td></tr>
 * </table>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不包着 Body</b>。类里没有 <code>Body source</code> 这样的字段 —— 有它
 *       就等于有了一个从快照回到活对象的跃迁, 而"只读"这条性质会在第一个
 *       "我就要那一个字段, 直接从 source 上读一下"的补丁里失效;</li>
 *   <li><b>不判断</b>。这里没有"健康分"、"舒服不舒服的评分"。唯一的布尔
 *       {@link #comfortable()} 是 <b>{@code Body} 自己的判断</b>（阈值住在身体里,
 *       见 §3.2.1: "冷"是生理事实, 不是认知判断）, 快照只是把它搬过来。
 *       快照自己一旦开始算分, 那个分就会被人拿去做分支, 而那种分支没法解释 ——
 *       同 {@code PlanPriority} 的"标签只给人看, 不许用来判断";</li>
 *   <li><b>不抄两遍</b>。通道的当前值只在 {@link #state()} 里, 这里不再复制一份
 *       {@code Map<String, Double>} —— 两份拷贝就有机会不一致, 而不一致的那个瞬间
 *       没法排查（两个数都"看起来对"）。{@code SensoryChannel} 那边的"水位/灵敏度"
 *       与状态不是同一件事, 所以 {@link #senses()} 不是重复;</li>
 *   <li><b>没有 setter</b>, 也没有"with"方法。它是 record, 全部字段 final,
 *       两个 Map 在构造时 {@code Map.copyOf}。要改的那一刻只能新建一张。</li>
 * </ul>
 *
 * <h2>它是"现场构造"的, 不是缓存</h2>
 * {@link #of(Body)} 每次都重新读一遍。这个代价是明知的（§3.1.4）:
 * 控制台读的是一个人的当前状态, 不是一万行报表。
 *
 * @param humanId         这是谁 —— 刻意<b>不</b>用 {@code String}, 见 {@link HumanId}
 * @param state           她此刻的生理状态（十条通道 + 扩展通道）
 * @param lastDelta       上一次推进算出来的增量; {@code null} = 她的身体还没有被推进过
 * @param lastSettlement  上一次账本结算; {@code null} = 还没有结算过（"为什么冷"暂时无解）
 * @param comfortable     身体自己的判断: 她此刻舒不舒服
 * @param clothing        她身上那套衣服的一句话描述（含保暖/无遮蔽/防水/透气）
 * @param senses          每条感官通道的一句话描述（水位、丢弃、灵敏度）
 * @param thresholds      阈值报警灯: 通道 → "已报警(偏低/偏高)", 以及趋势提醒
 * @param recentStates    最近几条生理状态 —— 曲线的尾巴, 最新的在最后
 * @param historySize     身体一共留了多少条状态（有上限, 见 {@code Body.SNAPSHOT_HISTORY_LIMIT}）
 * @param pendingStimuli  还压在她的通道里、没被 drain 的刺激数。
 *                        <b>大于 0 是正常的</b>（她还没轮到处理它们）——
 *                        它不是"丢了", 见 §8.5.4: 被暂停时它会一直涨
 * @param lastTickAt      她的身体最后一次被推进的时刻; {@code null} = 从没有过
 * @param lastMealAt      上一顿的时刻; {@code null} = 平台还不知道（不是"从没吃过"）
 */
public record BodySnapshot(
        HumanId humanId,
        PhysiologicalState state,
        PhysiologicalState.Delta lastDelta,
        ContinuousEffectLedger.Settlement lastSettlement,
        boolean comfortable,
        String clothing,
        Map<String, String> senses,
        Map<String, String> thresholds,
        List<PhysiologicalState> recentStates,
        int historySize,
        int pendingStimuli,
        Instant lastTickAt,
        Instant lastMealAt) {

    /** 曲线尾巴取几条。128 条画不下一页, 而快照会被复制、会被缓存进日志。 */
    public static final int TREND_TAIL = 12;

    public BodySnapshot {
        Objects.requireNonNull(humanId, "快照必须知道这是谁的身体");
        Objects.requireNonNull(state, "快照必须带生理状态 —— 一张什么都没有的身体快照"
                + "无法回答'她怎么样了', 只会让人以为她一切正常");
        clothing = clothing == null ? "" : clothing.trim();
        senses = senses == null ? Map.of() : Map.copyOf(senses);
        thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        recentStates = recentStates == null ? List.of() : List.copyOf(recentStates);
        if (historySize < 0 || pendingStimuli < 0) {
            throw new IllegalArgumentException("快照里的计数不能为负 —— 负数的计数会让趋势图无法解释。"
                    + "收到: historySize=" + historySize + ", pendingStimuli=" + pendingStimuli);
        }
    }

    /**
     * <b>读一遍她的身体, 造一张快照。</b>
     *
     * <h2>它是纯读的 —— 这一条是硬约束, 不是风格</h2>
     * 本方法<b>不</b>调用 {@code Body} 上任何一个"会改变她"的东西:
     * 不 {@code drainSensoryStimuli()}（那会把刺激从通道里拿走, 于是真的 actor
     * 下一次心跳再也看不到它们 —— 症状是"控制台一刷新她就像聋了一样"）、
     * 不 {@code advance()}、不 {@code feel()}。压着的刺激只被<b>数</b>出来
     * （{@code channel.pendingCount()}）。
     *
     * <p>这条约束之所以要写成一段话而不是一句"只读", 是因为最自然的那个写法
     * （"把刺激 drain 出来看看有几条")恰好是会毁掉仿真的那一个。
     */
    public static BodySnapshot of(Body source) {
        Objects.requireNonNull(source, "不能给一个空的 Body 造快照");

        List<SensoryChannel<?>> channels = source.channels();
        Map<String, String> senseDescriptions = new LinkedHashMap<>();
        int pending = 0;
        for (SensoryChannel<?> channel : channels) {
            senseDescriptions.put(channel.modality(), channel.describe());
            pending += channel.pendingCount();
        }

        List<PhysiologicalState> history = source.history();
        List<PhysiologicalState> tail = tailOf(history, TREND_TAIL);

        return new BodySnapshot(
                HumanId.of(source.id()),
                source.state(),
                source.lastDelta().orElse(null),
                source.lastSettlement().orElse(null),
                source.state().comfortable(),
                source.clothing().describe(),
                senseDescriptions,
                source.detector().snapshot(),
                tail,
                history.size(),
                pending,
                source.lastTickAt().orElse(null),
                source.lastMealAt());
    }

    private static List<PhysiologicalState> tailOf(List<PhysiologicalState> history, int limit) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - limit);
        return List.copyOf(new ArrayList<>(history.subList(from, history.size())));
    }

    /** 她的身体被推进过吗 —— 没推进过的身体读不到 {@code currentSimulationTime()}。 */
    public boolean hasTicked() {
        return lastTickAt != null;
    }

    /** 账本结算过吗 —— 没结算过时"她为什么冷"没有答案（而不是"没有原因"）。 */
    public boolean settled() {
        return lastSettlement != null;
    }

    /** 还压着没处理的刺激 —— 与 §8.5.4 的"被暂停时队列会涨"是同一个数。 */
    public boolean hasPendingStimuli() {
        return pendingStimuli > 0;
    }

    /**
     * 一行摘要。给日志与诊断面板的第一行用。
     *
     * <p>它<b>不</b>把每个字段都列出来 —— 那是 {@code toString()} 的活。
     * 它答的是"有没有异常需要我看一眼": 灯亮着没有、有没有积压、账本结算过没有。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("BodySnapshot[").append(humanId).append("] ").append(state.describe());
        if (lastDelta != null) {
            sb.append(" | 刚过去这一下: ").append(lastDelta.describe());
        }
        sb.append(" | ").append(clothing);
        sb.append(" | 待处理刺激 ").append(pendingStimuli);
        if (!thresholds.isEmpty()) {
            sb.append(" | 报警: ").append(thresholds);
        }
        if (lastSettlement == null) {
            sb.append(" | 尚未结算");
        } else {
            sb.append(" | 账本 ").append(settlementLine(lastSettlement));
        }
        sb.append(" | 上餐 ").append(lastMealAt == null ? "(不知道)" : lastMealAt.toString());
        return sb.toString();
    }

    /**
     * 账本结算的一行字。
     *
     * <p>为什么不直接用 {@code ContinuousEffectLedger.describe(Instant)}: 它的时刻参数
     * 会让它把"离现在还有多久过期"一起算进去, 而快照里没有"现在"（它只有一个
     * {@code settledAt}）。在这里凑一个"现在"就是 {@code EventFabric.describe(Instant)}
     * 的注释里记着的那个真实 bug 的翻版 —— 所以只印结算自己带的东西。
     */
    private static String settlementLine(ContinuousEffectLedger.Settlement settlement) {
        return "结算于 " + settlement.settledAt() + " " + settlement.totals()
                + (settlement.expiredCount() > 0 ? " (其中 " + settlement.expiredCount() + " 条已过期)" : "");
    }

    /**
     * 完整的字段清单 —— 只在"我要贴进 issue"时用。
     *
     * <p>刻意与 {@link #describe()} 分成两个方法: 摘要与全量混在一起的那个方法,
     * 最终会变成谁都不敢改的长字符串（因为有人在解析它）。
     */
    public String diagnostic() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("humanId", humanId.value());
        lines.put("state", state.describe());
        lines.put("delta", lastDelta == null ? "(无)" : lastDelta.describe());
        lines.put("settlement", lastSettlement == null ? "(尚未结算)" : settlementLine(lastSettlement));
        lines.put("comfortable", String.valueOf(comfortable));
        lines.put("clothing", clothing);
        lines.put("senses", senses.toString());
        lines.put("thresholds", thresholds.toString());
        lines.put("historySize", historySize + " (尾部 " + recentStates.size() + " 条)");
        lines.put("pendingStimuli", String.valueOf(pendingStimuli));
        lines.put("lastTickAt", String.valueOf(lastTickAt));
        lines.put("lastMealAt", String.valueOf(lastMealAt));
        return lines.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
