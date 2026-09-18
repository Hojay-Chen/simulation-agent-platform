package com.luxera.companion.mind;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.cognitive.CognitiveSession;
import com.luxera.companion.cognitive.CognitiveSessionService;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.runtime.v11.V11TurnsSwitch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * V11 §9.2 —— 心智的读写。
 *
 * <h2>写为什么全部由这里统一上闸</h2>
 * 本类的每一个写方法第一行都是 {@code if (!isPersisting()) return;}。
 * 闸门放在 Service 而不是放在各个调用方, 是因为"心智要不要落库"是一个<b>迁移阶段</b>
 * 的决定, 不是一个业务决定: 今天 {@code app.v11.turns.enabled=false}, 心智落库只会
 * 给 53 个 agent 各写一行没人读的数据 —— 那正是 V10 那个"只写不读"的影子记录器
 * (见 {@code V11DeliveryShadow} 的类注释)。
 *
 * <p>放在调用方会出现什么: 三四个月后有人在某条新路径上顺手写一次, 而那条路径
 * 并不知道此刻是 shadow 还是 enabled —— 于是 switch 变成了"大概关着"。
 * 放在这里, "现在到底写不写"只有一个答案。
 *
 * <h2>读不上闸</h2>
 * {@link #get} / {@link #snapshot} 任何时候都能读。诊断的价值恰恰在于
 * <b>切流之前就能看见</b>她手上有什么; 让读跟着写一起被关掉, 等于把观察能力
 * 也一起关掉了。
 *
 * <h2>观察者不该改变被观察者</h2>
 * 与 {@code AgentSnapshotService} 同一条纪律: {@link #snapshot} 不建行、不修字段。
 * 一个从没醒过的 agent 的心智是"没有", 不是一个默认值。
 */
@Service
@Slf4j
public class MindStateService {

    /**
     * 手上最多留几条线。
     *
     * <p>必须封顶: {@code working_threads} 是一个人的工作台, 而工作台会随着
     * 会话数无限长下去。12 这个数的意思是"超过 12 条就不再记新的" —— 挂到 12 条线
     * 的人, 第 13 条不会改变任何判断, 而 JSON 会一直长。
     */
    private static final int MAX_THREADS = 12;

    /** 快照里各带几条她惦记着的事。诊断不需要全量, 全量会把一个切面变成一次全表扫描。 */
    private static final int SNAPSHOT_LOOP_LIMIT = 5;
    private static final int SNAPSHOT_INTENTION_LIMIT = 5;

    /** 与 {@code AgentSnapshotService} 取同一个阈值 —— 两处"她还惦记着"必须同源。 */
    private static final double INTENTION_THRESHOLD = 0.5;

    private final MindStateRepository repo;
    private final CognitiveSessionService sessions;
    private final OpenLoopService openLoops;
    private final IntentionService intentions;
    private final V11TurnsSwitch turnsSwitch;
    private final ObjectMapper mapper;

    public MindStateService(MindStateRepository repo, CognitiveSessionService sessions,
                            OpenLoopService openLoops, IntentionService intentions,
                            V11TurnsSwitch turnsSwitch, ObjectMapper mapper) {
        this.repo = repo;
        this.sessions = sessions;
        this.openLoops = openLoops;
        this.intentions = intentions;
        this.turnsSwitch = turnsSwitch;
        this.mapper = mapper;
    }

    /** 现在写的每一笔会不会真的落库。 */
    public boolean isPersisting() {
        return turnsSwitch.isEnabled();
    }

    // ─────────────────────────── 读 ───────────────────────────

    /** 只读, 不建行。没有就是没有。 */
    @Transactional(readOnly = true)
    public MindState get(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        return repo.findByCompanionId(agentId).orElse(null);
    }

    /**
     * 她此刻的心智切面。<b>任何一项读不到都降级成"没有", 不整体抛出</b> ——
     * 一个切面的价值在于整体, 因为情绪表读不到就说她整个人读不到, 会让诊断
     * 在最需要它的时候失效。
     */
    @Transactional(readOnly = true)
    public MindSnapshot snapshot(String agentId) {
        MindState m = safe(() -> get(agentId), null, "agent_mind_states", agentId);
        CognitiveSession session = safe(() -> sessions.get(agentId), null, "cognitive_sessions", agentId);

        FocusState focus = m == null || m.getFocusWhat() == null
                ? FocusState.none()
                : new FocusState(m.getFocusWhat(), m.getFocusSource(), m.getFocusIntensity(), m.getFocusSince());

        List<MindSnapshot.Loop> loops = safe(() -> openLoops.activeLoops(agentId).stream()
                .limit(SNAPSHOT_LOOP_LIMIT)
                .map(l -> new MindSnapshot.Loop(l.getId(), l.getTitle(), l.getImportance(),
                        l.getExpectedResolutionAt()))
                .toList(), List.of(), "open_loops", agentId);

        List<MindSnapshot.Intention> intent = safe(() -> intentions
                .activatable(agentId, INTENTION_THRESHOLD).stream()
                .limit(SNAPSHOT_INTENTION_LIMIT)
                .map(i -> new MindSnapshot.Intention(i.getId(), i.getContent(), i.getActivationProbability()))
                .toList(), List.of(), "intentions", agentId);

        return new MindSnapshot(agentId, focus, threadsOf(m),
                loops, intent,
                session == null ? null : session.getCurrentFocus(),
                session == null ? null : session.getCurrentThought(),
                m == null ? null : m.getLastCognitiveAt(),
                new MindSnapshot.Turns(
                        m == null ? 0 : m.getTurnsOpened(),
                        m == null ? 0 : m.getTurnsSealed(),
                        m == null ? 0 : m.getMessagesAggregated(),
                        0,
                        m == null ? 0 : m.messagesPerTurn()),
                LocalDateTime.now());
    }

    /** 手上的线, 最近来过消息的排在前面。 */
    @Transactional(readOnly = true)
    public List<WorkingThread> threadsOf(String agentId) {
        return threadsOf(get(agentId));
    }

    /** 供回合逻辑用: 这次会话上有没有挂着一条线。 */
    public Optional<WorkingThread> threadOf(String agentId, String conversationId) {
        return threadsOf(get(agentId)).stream()
                .filter(t -> t.conversationId().equals(conversationId))
                .findFirst();
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 一条线上又有消息进来了(或者刚挂上)。
     *
     * @param pendingCount 这条线当前欠着几条没处理
     * @param openedNewTurn true = 这是新回合的第一条消息(而不是往已有回合里加)
     */
    @Transactional
    public void noteThreadHolding(String agentId, String conversationId, String userId,
                                  int pendingCount, boolean openedNewTurn, LocalDateTime now) {
        if (!isPersisting()) return;
        if (blank(agentId) || blank(conversationId)) return;
        try {
            MindState m = getOrCreate(agentId);
            List<WorkingThread> threads = new ArrayList<>(threadsOf(m));
            Optional<WorkingThread> existing = threads.stream()
                    .filter(t -> t.conversationId().equals(conversationId)).findFirst();
            WorkingThread updated = existing
                    .map(t -> t.extended(pendingCount, now))
                    .orElseGet(() -> new WorkingThread(conversationId, userId,
                            WorkingThread.STATE_OPEN, pendingCount, now, now));
            threads.removeIf(t -> t.conversationId().equals(conversationId));
            threads.add(0, updated);
            if (threads.size() > MAX_THREADS) {
                threads = new ArrayList<>(threads.subList(0, MAX_THREADS));
            }
            String json = writeThreads(threads, agentId);
            if (json == null) return;   // 序列化失败: 保留库里原来的工作台, 不写半个
            if (openedNewTurn) {
                m.setTurnsOpened(m.getTurnsOpened() + 1);
            }
            m.setWorkingThreads(json);
            repo.save(m);
        } catch (Exception e) {
            // 记不上只是"这一刻没留下痕迹", 不是"这件事没发生" —— 更不能因此把一次送达炸掉
            log.warn("[Mind] {} 记线程失败: {}", agentId, e.getMessage());
        }
    }

    /**
     * 写出她此刻的关注。
     *
     * @param intensity 认知链已经在算的那个重要度, <b>不是</b>为了填字段新发明的指标
     *                  (一个为了填字段而生的数字, 迟早会有人拿它做决策)
     */
    @Transactional
    public void noteFocus(String agentId, String conversationId, String what,
                          double intensity, LocalDateTime now) {
        if (!isPersisting()) return;
        if (blank(agentId) || blank(what)) return;
        try {
            MindState m = getOrCreate(agentId);
            m.setFocusWhat(trim(what, 200));
            m.setFocusSource(conversationId);
            m.setFocusIntensity(intensity);
            m.setFocusSince(now);
            repo.save(m);
        } catch (Exception e) {
            log.warn("[Mind] {} 记关注点失败: {}", agentId, e.getMessage());
        }
    }

    /**
     * 一个回合结束了(封口并处理完)。
     *
     * @param cognized 这个回合的消息<b>真的进过认知</b>吗。
     *                 被暂停丢掉、或者一条正文都没读到的回合传 false ——
     *                 它们不该把 {@code last_cognitive_at} 往前推, 否则
     *                 "她上一次真正想过事是什么时候"就成了一句假话
     */
    @Transactional
    public void noteTurnSealed(String agentId, String conversationId, String turnId,
                               int messageCount, boolean cognized, LocalDateTime now) {
        if (!isPersisting()) return;
        if (blank(agentId)) return;
        try {
            MindState m = getOrCreate(agentId);
            List<WorkingThread> threads = new ArrayList<>(threadsOf(m));
            threads.removeIf(t -> conversationId != null && t.conversationId().equals(conversationId));
            String json = writeThreads(threads, agentId);
            if (json == null) return;
            m.setWorkingThreads(json);
            m.setTurnsSealed(m.getTurnsSealed() + 1);
            m.setMessagesAggregated(m.getMessagesAggregated() + Math.max(0, messageCount));
            m.setLastConversationId(conversationId);
            m.setLastTurnId(trim(turnId, 160));
            if (cognized) {
                m.setLastCognitiveAt(now);
            }
            repo.save(m);
        } catch (Exception e) {
            log.warn("[Mind] {} 记回合封口失败: {}", agentId, e.getMessage());
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 拿到这一行, 没有就建一个空白的。{@code private} 是刻意的 —— 它是"建行"这件事
     * 在本工程里的唯一入口, 而建行必须发生在闸门之后。开成 public, 就等于在闸门旁边
     * 留了一扇不需要钥匙的门: 三个写方法各自记得先判断, 而第四个调用者不会。
     */
    private MindState getOrCreate(String agentId) {
        return repo.findByCompanionId(agentId).orElseGet(() -> {
            MindState m = new MindState();
            m.setCompanionId(agentId);
            m.setWorkingThreads("[]");
            return repo.save(m);
        });
    }

    @SuppressWarnings("unchecked")
    private List<WorkingThread> threadsOf(MindState m) {
        if (m == null || m.getWorkingThreads() == null || m.getWorkingThreads().isBlank()) {
            return List.of();
        }
        try {
            List<WorkingThread> list = mapper.readValue(m.getWorkingThreads(),
                    new TypeReference<ArrayList<WorkingThread>>() { });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            // 解不开的 JSON 当作"没有线"。反向选择(抛出去)会让一条写坏的记录
            // 从此让这个 agent 的每一次心智读写都失败 —— 那比丢掉一个线程糟得多。
            log.warn("[Mind] {} 的 working_threads 解不开, 按空处理: {}", m.getCompanionId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 序列化工作台; 失败返回 {@code null}, 调用方<b>放弃这次写入</b>。
     *
     * <p>不抛异常、也不返回空数组: 前者会把一次读不到的序列化变成一次送达失败,
     * 后者会用"她手上什么都没有"覆盖掉真实的工作台 —— 那是把一个技术故障
     * 写成了她的记忆。
     */
    private String writeThreads(List<WorkingThread> threads, String agentId) {
        List<WorkingThread> ordered = threads.stream()
                .sorted(Comparator.comparing(WorkingThread::lastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        try {
            return mapper.writeValueAsString(ordered);
        } catch (Exception e) {
            log.warn("[Mind] {} 的 working_threads 序列化失败, 本次不写: {}", agentId, e.getMessage());
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static <T> T safe(java.util.function.Supplier<T> query, T fallback, String what, String agentId) {
        try {
            T v = query.get();
            return v == null ? fallback : v;
        } catch (Exception e) {
            log.warn("[Mind] 读取 {} 失败(agent {}), 该维度用默认值", what, agentId, e);
            return fallback;
        }
    }
}
