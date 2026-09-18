package com.luxera.companion.runtime.v11;

import com.luxera.companion.behavior.BehaviorAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V11 §25.2 —— <b>主动行为的切流账本</b>(Phase 5)。
 *
 * <h2>它记得跟 {@link CognitionDecisionRecorder} 不一样, 这是有原因的</h2>
 * Phase 4 那本账能做到<b>逐条配对</b>: 同一次送达里, "新决策说 DEFER"与"老链回了"
 * 是同一个调用栈上的两个值, 所以 {@code agree} / {@code wouldSilence} 是精确的。
 *
 * <p>主动行为做不到这件事, 而且这个差别不是实现细节: 触发器在<b>调度线程</b>上跑
 * (那里知道老链这一轮干了什么), 消费者在<b>她自己的 actor 线程</b>上跑
 * (那里知道新链算出了什么), 两者之间隔着一个信箱。硬要把它们配对, 就得在信封里塞一个
 * 关联 id 并在两侧各存半张表 —— 那是为了账面好看而给运行时加一个真相源。
 *
 * <p>所以这本账是<b>聚合对照</b>: 同一段时间里, 新链"会主动说话"多少次 vs
 * 老链"实际主动说话"多少次。切流要回答的问题恰好就是这个量级上的问题
 * ("切了之后她会变吵还是变安静"), 而不是"第 13842 次唤醒她本来要干什么"。
 *
 * <h2>那个会误导人的比率</h2>
 * {@code wouldAct} 的分母是<b>拆开的信</b>, 不是"老链跑了几个 agent" —— 两者在
 * dual run 期间本来就不相等(有信的 agent 才进消费者)。任何把这个比率当成
 * "她会多主动 X%" 来读的人都会读错, 所以 {@link #stats()} 里两个计数并列给出,
 * 不做成一个比率。见 {@code speakRatio} 的注释。
 */
@Component
@Slf4j
public class ProactiveActionRecorder {

    /** 样本留多少条 —— 与认知账本同一个理由: 读不完的证据等于没有证据。 */
    static final int MAX_SAMPLES = 20;

    private final AtomicLong events = new AtomicLong();
    private final AtomicLong wouldAct = new AtomicLong();
    private final AtomicLong oldActed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Map<String, AtomicLong> byEventType = new ConcurrentHashMap<>();
    private final Map<BehaviorAction, AtomicLong> byPlannedAction =
            new ConcurrentHashMap<>();
    private final Deque<WouldAct> samples = new ArrayDeque<>();

    /**
     * 一条"她本来会主动说话"的证据。
     *
     * @param reason 候选的触发理由({@code BehaviorCandidate.trigger()}) —— 人类可读,
     *               没有它的话这条样本只能说"她会说话", 说不出"她想说什么"
     */
    public record WouldAct(LocalDateTime at, String eventType, String action, String reason,
                           double score) {
    }

    /**
     * 唯一算"她开口了"的动作。
     *
     * <p>抽成静态方法而不是各处写 {@code == SEND_PROACTIVE_MESSAGE}: 以后多一个
     * 会往外发消息的动作时, 只改这一处, 而不是在三处 {@code if} 里找一遍。
     * {@code CHECK_PHONE} / {@code CONTACT_OTHER_PERSON} 都<b>不算</b> ——
     * 它们改变她的内部状态与她对世界的印象, 但不产生一条用户看得见的消息。
     */
    public static boolean speaks(BehaviorAction action) {
        return action == BehaviorAction.SEND_PROACTIVE_MESSAGE;
    }

    /** 新链这一侧: 消费者拆开一封信, 算出她本来会做什么。 */
    public void record(String eventType, BehaviorAction planned, String reason, double score) {
        events.incrementAndGet();
        byEventType.computeIfAbsent(eventType == null ? "UNKNOWN" : eventType,
                k -> new AtomicLong()).incrementAndGet();
        if (planned == null) {
            return;
        }
        byPlannedAction.computeIfAbsent(planned, k -> new AtomicLong()).incrementAndGet();
        if (speaks(planned)) {
            wouldAct.incrementAndGet();
            remember(new WouldAct(LocalDateTime.now(), eventType, planned.name(),
                    truncate(reason, 60), score));
        }
    }

    /** 老链这一侧: dual run 期间 {@code BehaviorEngine.evaluateAll} 的产出。 */
    public void recordOld(BehaviorAction oldAction) {
        if (oldAction != null && speaks(oldAction)) {
            oldActed.incrementAndGet();
        }
    }

    /**
     * 一次坏掉的 shadow 会表现为"她本来一次都不会主动说话"。
     *
     * <p>与 {@code wouldAct} 分开计数是必须的: 合成一个的话, 一个抛异常的消费者与
     * 一个真的算出"不主动"的消费者在账面上完全一样。
     */
    public void recordError() {
        errors.incrementAndGet();
    }

    private synchronized void remember(WouldAct s) {
        if (samples.size() >= MAX_SAMPLES) {
            samples.removeFirst();
        }
        samples.addLast(s);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events.get());
        out.put("wouldAct", wouldAct.get());
        out.put("oldActed", oldActed.get());
        out.put("errors", errors.get());
        // 刻意不给"她会多主动百分之多少": 分子是新链算出来的次数, 分母是老链实际发出去的
        // 条数, 两者的取样口径不同(一个按拆开的信, 一个按跑过的 agent)。并列给出来,
        // 让读的人自己判断, 好过给一个看起来精确、实际上会让人做错决定的比率。
        Map<String, Long> types = new LinkedHashMap<>();
        byEventType.forEach((k, v) -> types.put(k, v.get()));
        out.put("byEventType", types);
        Map<String, Long> actions = new LinkedHashMap<>();
        byPlannedAction.forEach((k, v) -> actions.put(k.name(), v.get()));
        out.put("byPlannedAction", actions);
        synchronized (this) {
            out.put("samples", List.copyOf(samples));
        }
        return out;
    }

    public long events() {
        return events.get();
    }

    public long wouldAct() {
        return wouldAct.get();
    }

    public long errors() {
        return errors.get();
    }

    public synchronized void reset() {
        events.set(0);
        wouldAct.set(0);
        oldActed.set(0);
        errors.set(0);
        byEventType.clear();
        byPlannedAction.clear();
        samples.clear();
    }

    static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
