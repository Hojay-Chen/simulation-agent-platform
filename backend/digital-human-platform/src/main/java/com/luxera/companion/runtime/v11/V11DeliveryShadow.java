package com.luxera.companion.runtime.v11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * V11 §25 —— shadow 对比记录器: <b>新门与老链在真实流量下差多少</b>。
 *
 * <h2>为什么不用 {@code ShadowDecisionRecorder} 那种写法</h2>
 * V10 的 shadow 记录器用 {@code ConcurrentHashMap} + {@code System.nanoTime()} 做键,
 * <b>没有淘汰</b>:
 *
 * <pre>
 *   buffer.put(companionId + "-" + System.nanoTime(), s);   // 只增不减
 * </pre>
 *
 * 它的 {@code stats()} 甚至把 {@code buffer.size()} 当作一个指标报出来 ——
 * 也就是说这个泄漏是<b>被观测着的</b>, 只是没人把它当回事。53 个 agent 持续收消息时,
 * 它是一条只涨不跌的内存曲线。
 *
 * <p>本类刻意不这样写: <b>聚合用计数器(定长), 样本用定长环(有界)</b>。
 * 计数器回答"差多少", 环回答"差在哪" —— 而"差在哪"只需要最后几十个例子,
 * 不是全部。要全量证据就不该放在进程内存里, 该落库。
 *
 * <h2>读到的数字怎么看</h2>
 * <ul>
 *   <li>{@code agreeNoticed} —— 两边都认为她注意到了。这部分切流是安全的。</li>
 *   <li>{@code v11WouldSkip} —— 老链处理了, 而新门认为她<b>根本不会注意到</b>。
 *       这是切流后行为会变的那部分。它不是 bug, 它<b>就是本次改动的内容</b>;
 *       但它的比例决定了切流值不值得、以及要不要先调阈值。</li>
 *   <li>{@code v11NoText} —— 新门认为她注意到了, 但设备/平台都读不到正文。
 *       这一项若不为 0, 说明切流后会有人"看见了却不回", 必须先修读取。
 *       <b>shadow 期间它恒为 0</b>, 因为 shadow 不读正文(readCount 传 -1 = 未尝试)。
 *       它要等 enabled=true 之后才是个有意义的数字 —— 这是刻意的:
 *       一个"猜出来"的数字比没有数字更糟。</li>
 * </ul>
 */
@Component
@Slf4j
public class V11DeliveryShadow {

    /** 样本环的容量。够看出"差在哪一类", 又不至于变成一个内存里的日志库。 */
    private static final int SAMPLE_CAPACITY = 64;

    /** 每个 agent 的汇总。agent 数量本身有界(当前 53), 不会无界增长。 */
    private final Map<String, Counters> perAgent = new LinkedHashMap<>();
    private final Deque<Sample> samples = new ArrayDeque<>();

    private final LongAdder total = new LongAdder();
    private final LongAdder agreeNoticed = new LongAdder();
    private final LongAdder v11WouldSkip = new LongAdder();
    private final LongAdder v11NoText = new LongAdder();

    /**
     * 记录一次对比。{@code legacyNoticed} 今天恒为 true(老链只要拿到正文就一定处理),
     * 但仍然显式传进来 —— 等老链也有了自己的门, 这个参数就是它真正的位置。
     *
     * @param readCount 这次真的读到了几条正文; <b>传负数表示"没尝试读"</b>
     *                  (shadow 模式), 与"读了但一条都没读到"(0) 是两件事
     */
    public synchronized void record(String agentId, int burstSize,
                                    double salience, double noticeProbability,
                                    boolean v11Noticed, boolean legacyNoticed,
                                    int readCount, LocalDateTime at) {
        total.increment();
        if (v11Noticed == legacyNoticed) {
            agreeNoticed.increment();
        } else if (legacyNoticed && !v11Noticed) {
            v11WouldSkip.increment();
        }
        if (v11Noticed && readCount == 0) {
            v11NoText.increment();
        }

        perAgent.computeIfAbsent(agentId, k -> new Counters()).add(burstSize, salience, noticeProbability);
        samples.addLast(new Sample(agentId, burstSize, salience, noticeProbability, v11Noticed, at));
        while (samples.size() > SAMPLE_CAPACITY) {
            samples.removeFirst();
        }

        if (log.isDebugEnabled()) {
            log.debug("[V11-shadow] {} burst={} salience={} noticeProb={} v11Noticed={} legacyNoticed={}",
                    agentId, burstSize, round(salience), round(noticeProbability), v11Noticed, legacyNoticed);
        }
    }

    /** 汇总。给诊断端点/测试用 —— 全是定长数据, 调多少次都不会长。 */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total.sum());
        out.put("agreeNoticed", agreeNoticed.sum());
        out.put("v11WouldSkip", v11WouldSkip.sum());
        out.put("v11NoText", v11NoText.sum());
        out.put("skipRate", total.sum() == 0 ? 0.0
                : round((double) v11WouldSkip.sum() / total.sum()));
        return out;
    }

    /** 每个 agent 的均值 —— 用来找"哪个 agent 的判定最反常"。 */
    public synchronized Map<String, Object> perAgentStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        perAgent.forEach((agent, c) -> out.put(agent, c.summary()));
        return out;
    }

    /** 最近的样本(有界), 给人看的字符串形式。 */
    public synchronized List<String> recentSamples() {
        List<String> out = new ArrayList<>(samples.size());
        for (Sample s : samples) {
            out.add(describe(s));
        }
        return out;
    }

    /**
     * 某个 agent 的最近样本, 结构化的形式 —— <b>这是让 shadow 数据真的能被读到的那条路</b>。
     *
     * <p>存在的理由值得写下来: V10 的 {@code ShadowDecisionRecorder} 有
     * {@code recent()} / {@code stats()} / {@code perAgentStats()} 三个查询方法,
     * 而它们<b>在整仓里一个调用者都没有</b> —— 没有端点, 没有测试。
     * 于是 V10 的 shadow 模式是纯成本: 一直记录, 从没被看过, 顺带漏着内存。
     * 一个没人能读的对比等于没有对比, 所以 {@link #stats()} 与 {@link #recentFor}
     * 由 {@code DiagnosticController} 的 V11 端点读取, 并且在测试里被断言。
     *
     * @param agentId 只看这一个 agent 的样本
     */
    public synchronized List<Map<String, Object>> recentFor(String agentId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        var it = samples.descendingIterator();
        while (it.hasNext() && out.size() < Math.max(0, limit)) {
            Sample s = it.next();
            if (!s.agentId().equals(agentId)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("burstSize", s.burstSize());
            m.put("salience", round(s.salience()));
            m.put("noticeProbability", round(s.noticeProbability()));
            m.put("v11Noticed", s.noticed());
            m.put("at", s.at() == null ? null : s.at().toString());
            out.add(m);
        }
        return out;
    }

    private static String describe(Sample s) {
        return String.format("%s burst=%d sal=%.3f noticeP=%.3f noticed=%s",
                s.agentId(), s.burstSize(), s.salience(), s.noticeProbability(), s.noticed());
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record Sample(String agentId, int burstSize, double salience,
                          double noticeProbability, boolean noticed, LocalDateTime at) {}

    private static final class Counters {
        private long n;
        private long burstSum;
        private double salienceSum;
        private double noticeProbSum;
        private double noticeProbMax;

        void add(int burst, double salience, double noticeProb) {
            n++;
            burstSum += burst;
            salienceSum += salience;
            noticeProbSum += noticeProb;
            noticeProbMax = Math.max(noticeProbMax, noticeProb);
        }

        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", n);
            m.put("avgBurst", n == 0 ? 0.0 : round((double) burstSum / n));
            m.put("avgSalience", n == 0 ? 0.0 : round(salienceSum / n));
            m.put("avgNoticeProb", n == 0 ? 0.0 : round(noticeProbSum / n));
            m.put("maxNoticeProb", round(noticeProbMax));
            return m;
        }
    }
}
