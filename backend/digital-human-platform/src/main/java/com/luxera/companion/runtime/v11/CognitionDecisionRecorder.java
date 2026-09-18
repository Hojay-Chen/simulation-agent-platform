package com.luxera.companion.runtime.v11;

import com.luxera.companion.cognition.CognitiveDecision;
import com.luxera.companion.cognition.DecisionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V11 §25.2 —— <b>shadow 期唯一要回答的问题: 按新决策执行, 她的行为会变成什么样</b>。
 *
 * <h2>为什么不是"记录每次决策"那么简单</h2>
 * 把每次决策打一条日志是无用的: 那只会产出一堆 {@code decision=REPLY}, 而人关心的是
 * <b>差异</b>。真正决定能不能切流的两个数字是:
 * <pre>
 *   wouldSilence   新决策说"不回", 老链回了   → 切流后这些回复会消失
 *   wouldSpeak     新决策说"回",   老链没回   → 切流后她会多说这些话
 * </pre>
 * 第二个通常很小(老链不回的原因大多是"没写出来"或"冲突", 那些在新链里仍然会发生)。
 * 第一个是<b>必须盯着的那一个</b>: 它直接等于"切流后 53 个 agent 会安静多少"。
 * 这个数字大到某个程度就不是"更真实", 而是"她坏了"。
 *
 * <h2>为什么计数在内存里, 而 Phase 3 的回合计数落了库</h2>
 * 因为两者要回答的问题的时间尺度不一样。回合的合并率是<b>部署之后还要能问</b>的
 * (见 {@code MindState} 的类注释), 而 shadow 的差异率是<b>切流判据</b> ——
 * 它只需要活到切流那一刻, 重启清零反而更好: 一批新配置从零开始积累, 不会把
 * 改配置之前的旧样本混进来。混进来的后果很具体: 你会对着一个被旧配置污染的
 * 平均数判断"可以切了"。
 *
 * <h2>样本为什么只留最近若干条</h2>
 * 因为差异样本是给人读的。<b>读不完的证据等于没有证据</b> —— 一个存了十万条的列表
 * 与一个存了二十条的列表, 在"明天要不要切流"这个问题上提供的信息一样多,
 * 而前者会先把内存吃掉。
 */
@Component
@Slf4j
public class CognitionDecisionRecorder {

    /** 最多留这么多条差异样本(供诊断端点读) */
    static final int MAX_SAMPLES = 20;

    private final AtomicLong decisions = new AtomicLong();
    private final AtomicLong agree = new AtomicLong();
    private final AtomicLong wouldSilence = new AtomicLong();
    private final AtomicLong wouldSpeak = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    private final Map<DecisionType, AtomicLong> byType = new EnumMap<>(DecisionType.class);
    /** 理由分布 —— "她为什么安静"必须能一眼看出是"忙"还是"没看到" */
    private final Map<String, AtomicLong> byReason = new java.util.concurrent.ConcurrentHashMap<>();

    private final Deque<Divergence> samples = new ArrayDeque<>();

    /**
     * 一次"新决策 vs 老链实际走向"的对照。
     *
     * @param planned          V11 决策
     * @param oldChainReplied  老链这一次到底有没有发出一条消息
     * @param detail           老链的实际走向(Outcome 名 / 不发的原因)
     */
    public record Divergence(LocalDateTime at, DecisionType planned, String reason,
                             boolean oldChainReplied, String detail) {}

    /** 一次对照的结论。 */
    public enum Agreement {
        /** 两边一致 */
        AGREE,
        /** 新决策更安静 —— 切流后这条回复会消失 */
        WOULD_SILENCE,
        /** 新决策更爱说 —— 切流后这里会多一条消息 */
        WOULD_SPEAK
    }

    public void record(CognitiveDecision decision, boolean oldChainReplied, String detail) {
        if (decision == null) {
            return;
        }
        decisions.incrementAndGet();
        byType.computeIfAbsent(decision.type(), k -> new AtomicLong()).incrementAndGet();
        byReason.computeIfAbsent(decision.reason(), k -> new AtomicLong()).incrementAndGet();

        Agreement a = classify(decision.type(), oldChainReplied);
        switch (a) {
            case AGREE -> agree.incrementAndGet();
            case WOULD_SILENCE -> wouldSilence.incrementAndGet();
            case WOULD_SPEAK -> wouldSpeak.incrementAndGet();
        }
        if (a != Agreement.AGREE) {
            remember(new Divergence(LocalDateTime.now(), decision.type(), decision.reason(),
                    oldChainReplied, detail));
        }
    }

    /** 判定两侧是否一致。<b>比较的是"会不会写一条消息出去", 不是决策名字</b>。 */
    public static Agreement classify(DecisionType planned, boolean oldChainReplied) {
        boolean plannedSpeaks = planned.producesOutboundMessage();
        if (plannedSpeaks == oldChainReplied) {
            return Agreement.AGREE;
        }
        return plannedSpeaks ? Agreement.WOULD_SPEAK : Agreement.WOULD_SILENCE;
    }

    /** 记录器自己出问题(算决策时抛异常) —— 单独计数, 因为"shadow 崩了"必须与"差异很多"区分开。 */
    public void recordError() {
        errors.incrementAndGet();
    }

    private synchronized void remember(Divergence d) {
        if (samples.size() >= MAX_SAMPLES) {
            samples.removeFirst();
        }
        samples.addLast(d);
    }

    /** 诊断端点用。 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        long total = decisions.get();
        out.put("decisions", total);
        out.put("agree", agree.get());
        out.put("wouldSilence", wouldSilence.get());
        out.put("wouldSpeak", wouldSpeak.get());
        out.put("errors", errors.get());
        // 差异率是<b>决策级的比例</b>, 不是"消息级的比例" —— 一个回合可能含三条消息,
        // 拿消息总数当分母会得到一个偏小的数, 而切流判据问的是"多少次认知会改变结果"
        out.put("divergenceRate", total == 0 ? 0.0
                : round((double) (wouldSilence.get() + wouldSpeak.get()) / total));
        out.put("silenceRate", total == 0 ? 0.0 : round((double) wouldSilence.get() / total));
        Map<String, Long> types = new java.util.LinkedHashMap<>();
        byType.forEach((k, v) -> types.put(k.name(), v.get()));
        out.put("byType", types);
        Map<String, Long> reasons = new java.util.LinkedHashMap<>();
        byReason.forEach((k, v) -> reasons.put(k, v.get()));
        out.put("byReason", reasons);
        synchronized (this) {
            out.put("samples", List.copyOf(samples));
        }
        return out;
    }

    public long decisions() {
        return decisions.get();
    }

    public long wouldSilence() {
        return wouldSilence.get();
    }

    /** 测试与运维用: 把一批样本清掉(改了配置之后重新积累)。 */
    public synchronized void reset() {
        decisions.set(0);
        agree.set(0);
        wouldSilence.set(0);
        wouldSpeak.set(0);
        errors.set(0);
        byType.clear();
        byReason.clear();
        samples.clear();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
