package com.luxera.companion.runtime;

import com.luxera.companion.cognitive.CognitiveSession;
import com.luxera.companion.cognitive.CognitiveSessionService;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.AgentLifecycle;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.phone.PhoneNotificationRepository;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * V11 §4.1 —— 把一个 agent 的散落状态收成一个切面。
 *
 * <h2>为什么只读</h2>
 * 本类<b>一个字节都不写</b>。看起来像是一个不必要的自我约束, 但它有很具体的理由:
 * 恢复流程({@code AgentRecoveryService})与运维诊断都会调用它, 而这两条路径都不该
 * 因为"顺手 getOrCreate 一下"而给一个从未启动过的 agent 造出 {@code agent_states} /
 * {@code cognitive_sessions} 行。今天 {@code getOrCreate} 在认知链里到处被调,
 * 这是对的(那条路径上确实需要一个状态对象); 但在观察路径上, <b>观察者不该改变被观察者</b>。
 *
 * <p>因此缺失的状态返回默认值而不是创建默认行 —— 一个还没醒过的 agent 的能量是
 * 多少? 这个问题没有答案, 而"0.72"是一个诚实的"不知道, 用基准值"。
 *
 * <h2>它会不会拖慢主链</h2>
 * 不会跑到主链上。快照只在恢复、诊断、测试里被调用; 认知链的每一次回复都不经过这里。
 * 这是刻意的 —— 一个每次回消息都要查五张表的切面, 迟早会被某个性能补丁改成缓存,
 * 而缓存过的"她此刻怎样"就不再是事实了。
 */
@Service
@Slf4j
public class AgentSnapshotService {

    /** 快照里"她还惦记着"的意图阈值。与 {@code IntentionService} 的调用方保持一致。 */
    private static final double INTENTION_THRESHOLD = 0.5;

    /** 没有 {@code agent_states} 行时的基准值 —— 与 {@code AgentState} 的列默认值同源。 */
    private static final String BASE_MOOD = "calm";
    private static final double BASE_ENERGY = 0.72;
    private static final double BASE_STRESS = 0.18;
    private static final double BASE_FOCUS = 0.6;

    private final AgentStateService agentStates;
    private final CognitiveSessionService sessions;
    private final PhoneNotificationRepository notifications;
    private final OpenLoopService openLoops;
    private final IntentionService intentions;
    private final AgentSwitchService agentSwitch;
    private final AgentMailbox mailbox;

    public AgentSnapshotService(AgentStateService agentStates,
                                CognitiveSessionService sessions,
                                PhoneNotificationRepository notifications,
                                OpenLoopService openLoops,
                                IntentionService intentions,
                                AgentSwitchService agentSwitch,
                                AgentMailbox mailbox) {
        this.agentStates = agentStates;
        this.sessions = sessions;
        this.notifications = notifications;
        this.openLoops = openLoops;
        this.intentions = intentions;
        this.agentSwitch = agentSwitch;
        this.mailbox = mailbox;
    }

    /**
     * 取一个切面。任何单项查询失败都<b>降级成默认值</b>而不是整体抛出 ——
     * 一个快照的价值在于"她整体上怎么样了", 因为情绪表读不到就说她整个人读不到,
     * 会让诊断在最需要它的时候失效。
     */
    @Transactional(readOnly = true)
    public AgentSnapshot snapshot(String agentId) {
        AgentState state = safe(() -> agentStates.get(agentId), null, "agent_states", agentId);
        CognitiveSession session = safe(() -> sessions.get(agentId), null, "cognitive_sessions", agentId);

        AgentSnapshot.State stateView = new AgentSnapshot.State(
                state == null ? BASE_MOOD : state.getMood(),
                state == null ? BASE_ENERGY : state.getEnergy(),
                state == null ? BASE_STRESS : state.getStress(),
                state == null ? BASE_FOCUS : state.getFocus(),
                session == null ? null : session.getCurrentFocus(),
                session == null ? null : session.getCurrentThought(),
                session == null ? null : session.getCurrentIntention(),
                session == null ? 0L : session.getStateVersion());

        // count 返回 long, 而快照里的计数是 int —— 未读通知不可能到 21 亿, 这里窄化是安全的
        int unread = (int) (long) safe(() -> notifications.countByCompanionIdAndReadFalse(agentId), 0L,
                "phone_notifications", agentId);
        int loops = safe(() -> openLoops.activeLoops(agentId).size(), 0, "open_loops", agentId);
        int intent = safe(() -> intentions.activatable(agentId, INTENTION_THRESHOLD).size(), 0, "intentions", agentId);
        long inbox = safe(() -> mailbox.depthOf(agentId), 0L, "agent_inbox", agentId);

        AgentLifecycle lifecycle = safe(() -> agentSwitch.lifecycleOf(agentId), AgentLifecycle.ACTIVE,
                "companions.status", agentId);

        return new AgentSnapshot(
                agentId,
                lifecycle.wire(),
                LocalDateTime.now(),
                stateView,
                new AgentSnapshot.Attention(unread, inbox, loops, intent));
    }

    private static <T> T safe(java.util.function.Supplier<T> query, T fallback, String what, String agentId) {
        try {
            T v = query.get();
            return v == null ? fallback : v;
        } catch (Exception e) {
            log.warn("[Snapshot] 读取 {} 失败(agent {}), 该维度用默认值", what, agentId, e);
            return fallback;
        }
    }
}
