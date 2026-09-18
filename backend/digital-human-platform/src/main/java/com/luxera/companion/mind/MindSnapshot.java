package com.luxera.companion.mind;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V11 §23.4 —— <b>她此刻的心智</b>, 一个只读切面。
 *
 * <h2>与 {@code AgentSnapshot} 的分工(两者确实有重叠, 是刻意的)</h2>
 * <pre>
 *   AgentSnapshot(V11 Phase 1)  给运维与恢复: 她活着吗、信箱多深、手机几条未读
 *   MindSnapshot(本类)          给认知与诊断: 她手上挂着什么、正在想什么、还欠谁
 * </pre>
 * 两者都会数 {@code open_loops} / {@code intentions}, 但<b>都调同一个 Service</b> ——
 * 重叠的部分必须同源。谁要是为了"省一次查询"自己再算一遍, 两个切面就会开始各说各话,
 * 而那种分歧不报错, 只让诊断在最需要它的时候给出两个不同的答案。
 *
 * <h2>它不写任何东西</h2>
 * 与 {@code AgentSnapshotService} 同一条纪律: 观察者不该改变被观察者。
 * 缺失的行返回"没有", 而不是顺手建一条默认行。
 *
 * @param agentId        谁的
 * @param focus          她正关注的事({@link FocusState#none()} 表示还没想过任何事)
 * @param threads        手上挂着的线, 按最近来消息排
 * @param openLoops      她还没了结的事
 * @param intentions     她"想着要做"的事
 * @param sessionFocus   {@code cognitive_sessions.current_focus} —— 消息级的话题(见 MindStateService 的说明)
 * @param sessionThought {@code cognitive_sessions.current_thought}
 * @param lastCognitiveAt 她上一次真正把注意力放到某件事上是什么时候(不是"上次收到消息")
 * @param turns          累计回合计数
 * @param takenAt        切面取的时刻
 */
public record MindSnapshot(String agentId,
                           FocusState focus,
                           List<WorkingThread> threads,
                           List<Loop> openLoops,
                           List<Intention> intentions,
                           String sessionFocus,
                           String sessionThought,
                           LocalDateTime lastCognitiveAt,
                           Turns turns,
                           LocalDateTime takenAt) {

    /** 她还没了结的一件事。 */
    public record Loop(String id, String title, double importance, LocalDateTime expectedResolutionAt) {}

    /** 她想着要做的一件事。 */
    public record Intention(String id, String content, double activationProbability) {}

    /**
     * 回合计数。<b>这是判断"连续消息有没有真的被并成一个回合"的唯一证据</b>:
     * {@code messagesPerTurn} 是合并率 —— 它是 1.0 就说明合并根本没发生(窗口太短),
     * 它是 3.0 说明三句话被当成一次说完的。
     */
    public record Turns(long turnsOpened, long turnsSealed, long messagesAggregated,
                        int openTurns, double messagesPerTurn) {}

    public MindSnapshot {
        focus = focus == null ? FocusState.none() : focus;
        threads = threads == null ? List.of() : List.copyOf(threads);
        openLoops = openLoops == null ? List.of() : List.copyOf(openLoops);
        intentions = intentions == null ? List.of() : List.copyOf(intentions);
    }
}
