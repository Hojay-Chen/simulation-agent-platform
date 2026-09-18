package com.luxera.companion.persona;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 的**总开关**: 让运维能把一个 agent 停下来, 而不销毁它。
 *
 * <h2>它要解决的问题</h2>
 *
 * 这个平台的 agent 是持续运转的: 18 个定时任务在推进它的生活/念头/反省/行为,
 * 一条消息进来就走完整条认知链。**几乎每一步都会调 LLM**, 而 LLM 是按量计费的。
 *
 * <p>其中真正需要点名的是三条**在完全没有人说话时也会跑**的路:
 *
 * <ol>
 *   <li>{@code EventSimulationJob}(每 30 分钟)→ {@code EventSimulationAgent} —— 每个
 *       醒着的 agent、每次**无条件**调一次结构化 LLM(<i>没有任何概率门</i>, 采样发生在
 *       调用<b>之后</b>)。约 24–48 次/agent/天, 是空闲部署上的头号开销。</li>
 *   <li>{@code WeeklyReflectionJob} → {@code PersonaEvolutionService.runAll} ——
 *       **没有"这周聊过天吗"这个前提**, 只要有 persona 就调。1–2 次/agent/周。</li>
 *   <li>{@code BehaviorTickJob}(每 5 分钟)→ 随机选中"主动发消息"时走完整聊天链,
 *       1–3 次 {@code llm.chat}。</li>
 * </ol>
 *
 * <p>在本类之前, 唯一能停下一个 agent 的办法是删掉它。于是运维被夹在
 * "全速烧"和"销毁历史"之间, 没有中间的档位 —— 而"先停一停"恰恰是最常用的那档。
 *
 * <h2>闸门有三层, 但**只有一层是保证**</h2>
 *
 * <ol>
 *   <li><b>入口闸</b> —— {@code AgentRuntime.submitWithPhase} 的 mailbox 任务体内。
 *       聊天消息驱动的认知在这里被拦住。放在<b>任务体</b>而不是提交处是刻意的: 暂停
 *       之前已经排进邮箱的消息也一并拦下, 而提交处做不到这一点。</li>
 *   <li><b>批处理闸</b> —— 遍历全部 agent 的那些循环改用
 *       {@code CompanionRepository.findRunnable()}, 直接从查询里排除暂停的 agent。
 *       这一层挡住那三条"无人也跑"的路, 并且顺带省掉了对它们的全部无用计算。</li>
 *   <li><b>LLM 硬闸</b> —— {@code LlmRouter} 的三个出口。**这是唯一的真保证**:
 *       全平台 28 处 LLM 调用点无一例外都穿过它。</li>
 * </ol>
 *
 * <p>三层的分工要说清楚, 否则容易误以为任意一层就够: 第 1、2 层是"别做无用功"
 * (省的不只是 token, 还有数据库写入和线程时间), 第 3 层是"无论如何别花钱"。
 * 第 3 层能兜住前两层漏掉的任何一条路径, 包括将来新加的、忘记接闸门的任务。
 *
 * <h2>它在两个服务里都存在</h2>
 *
 * 本类住在 {@code persona} 包, 而 8091(认知链)与 8092(openAPI)的组件扫描白名单
 * **都包含它**。于是两个服务各自持有一个实例, 各自读写同一张 {@code companions} 表。
 * 这是刻意的, 不是巧合: 它让"暂停一个 agent"这个操作在 8092 上不需要跨进程调用 8091
 * —— 而跨进程调用会引入一个"8091 没起来就暂停不了"的新故障模式, 恰恰在最需要
 * 按下这个开关的时候。
 *
 * <h2>为什么没有缓存</h2>
 *
 * 状态读的是 {@code companions} 主键, 而暂停/恢复是**运维动作**(人按的, 不是每毫秒
 * 发生的)。缓存能省下的是一次主键查询, 代价却是一个**跨 JVM 的陈旧窗口** ——
 * 在 8092 上暂停、8091 上还在跑, 而"我明明关了它怎么还在烧 token"正是这个功能最
 * 不该出现的症状。查询本身在主键索引上是微秒级, 相对一次动辄数秒的 LLM 调用可以忽略。
 */
@Slf4j
@Service
public class AgentSwitchService {

    private final CompanionRepository companions;

    /**
     * 部署级的**总闸**: {@code false} 时全平台所有 agent 一律不跑。
     *
     * <p>它是 {@code app.agent.runtime-enabled}, 默认 {@code true} —— 也就是说
     * **默认行为与加这个开关之前完全一致**。这条默认值很重要: 一个运维开关如果在
     * 部署时"默认关着", 那它就不是开关, 而是一次静默停机。
     *
     * <p>它与逐 agent 的暂停是**两件事**, 各回答一个问题:
     * <ul>
     *   <li>本字段: "这台机器上的认知链整体要不要跑" —— 改配置 + 重启, 用于维护窗口。</li>
     *   <li>{@code companions.status}: "这一个 agent 要不要跑" —— 立即生效, 不改配置,
     *       在控制台上可见可点。</li>
     * </ul>
     * 两者的关系是**或**: 任意一个说停就停。
     */
    private final boolean runtimeEnabled;

    public AgentSwitchService(CompanionRepository companions,
                              @Value("${app.agent.runtime-enabled:true}") boolean runtimeEnabled) {
        this.companions = companions;
        this.runtimeEnabled = runtimeEnabled;
    }

    public boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    // ── 读: 闸门用的那几个问题 ───────────────────────────────

    /**
     * 这个 agent 现在**该不该**消耗算力 —— LLM 硬闸用的判据。
     *
     * <p>三种输入, 三种答复, 每一种都是刻意的:
     *
     * <ul>
     *   <li>{@code companionId} 为空 → <b>放行</b>。这不是 agent 自己的开销: 平台里有一批
     *       调用是**用户当场发起**的(编译人格、应用链路的三个 resolver), 它们的
     *       metadata 里本来就没有 companionId —— {@code LlmCallService} 同样因此不记账。
     *       拦住它们等于"人按了按钮却没反应", 而这与省钱无关。</li>
     *   <li>查得到、且状态是 {@code paused} → <b>拦住</b>。这是本类存在的全部理由。</li>
     *   <li><b>查不到这一行</b> → <b>放行</b>。这是本类唯一一处"往不省钱的方向兜底",
     *       所以理由要写下来: 一个查不到的 companionId 意味着**调用方或者测试脚手架有 bug**,
     *       而"用停止来应对 bug"会把一个显式的错误变成一片静默 —— 症状是"她不回我了",
     *       没有报错、没有日志。开关的职责是执行**明确的**暂停, 不是替身份校验做判断。</li>
     * </ul>
     */
    public boolean isRunnable(String companionId) {
        if (!runtimeEnabled) return false;
        if (companionId == null || companionId.isBlank()) return true;
        return companions.findById(companionId)
                .map(c -> !AgentLifecycle.of(c.getStatus()).isPaused())
                .orElse(true);
    }

    /**
     * 一个已经拿在手里的 {@link Companion} 要不要跑 —— 给遍历循环用的**零查询**版本。
     *
     * <p>比 {@link #isRunnable(String)} 多管一件事: 它还排除已软删的 agent。
     * 那些循环本来各自都写着 {@code if (c.getDeletedAt() != null) continue}, 这个方法是
     * 把那行重复了十几遍的判断收成一处, 顺带加上暂停这一维。
     *
     * <p>用实体而不是 id, 是因为状态就在 {@code companions} 行上 —— 循环里的 agent
     * 是查出来的, 状态是**免费**的, 不该为了问一句话再查一次库。
     */
    public boolean isRunning(Companion c) {
        if (!runtimeEnabled) return false;
        if (c == null || c.getDeletedAt() != null) return false;
        return !AgentLifecycle.of(c.getStatus()).isPaused();
    }

    public AgentLifecycle lifecycleOf(String companionId) {
        return companions.findById(companionId)
                .map(c -> AgentLifecycle.of(c.getStatus()))
                .orElse(AgentLifecycle.ACTIVE);
    }

    // ── 写: 暂停与恢复 ──────────────────────────────────────

    /**
     * 暂停一个 agent。**幂等** —— 已经暂停的再暂停一次不算错误, 也不会改
     * {@code updated_at}(一个"我按了两次"不该在数据上留下两道痕迹)。
     *
     * @return 状态是否真的变了
     */
    @Transactional
    public boolean pause(String companionId) {
        return setLifecycle(companionId, AgentLifecycle.PAUSED);
    }

    /** 恢复一个 agent。幂等, 同 {@link #pause}。 */
    @Transactional
    public boolean resume(String companionId) {
        return setLifecycle(companionId, AgentLifecycle.ACTIVE);
    }

    private boolean setLifecycle(String companionId, AgentLifecycle target) {
        Companion c = companions.findById(companionId).orElse(null);
        if (c == null) return false;
        if (AgentLifecycle.of(c.getStatus()) == target) return false;
        c.setStatus(target.wire());
        companions.save(c);
        log.info("[AgentSwitch] {} → {}", companionId, target.wire());
        return true;
    }

    /**
     * 暂停**全部**活着的 agent —— 运维口径的"全部关掉"。
     *
     * <p>不碰已软删的行: 它们已经停了, 改它们的 status 只会让 {@code RETIRED} 与
     * {@code PAUSED} 两个概念在数据上互相污染。
     *
     * <p>返回真正发生变化的行数而不是"影响了多少行" —— 这两个数在控制台上读起来
     * 一样, 但前者能回答"我按了两下, 第二下有没有做事", 后者不能。
     */
    @Transactional
    public int pauseAll() {
        List<Companion> changed = new ArrayList<>();
        for (Companion c : companions.findByDeletedAtIsNullOrderByCreatedAtAsc()) {
            if (AgentLifecycle.of(c.getStatus()).isPaused()) continue;
            c.setStatus(AgentLifecycle.PAUSED.wire());
            changed.add(c);
        }
        if (!changed.isEmpty()) {
            companions.saveAll(changed);
            log.warn("[AgentSwitch] 已暂停全部 agent: {} 个", changed.size());
        }
        return changed.size();
    }

    /** 恢复全部被暂停的 agent。同 {@link #pauseAll} 的幂等口径。 */
    @Transactional
    public int resumeAll() {
        List<Companion> changed = new ArrayList<>();
        for (Companion c : companions.findByDeletedAtIsNullOrderByCreatedAtAsc()) {
            if (!AgentLifecycle.of(c.getStatus()).isPaused()) continue;
            c.setStatus(AgentLifecycle.ACTIVE.wire());
            changed.add(c);
        }
        if (!changed.isEmpty()) {
            companions.saveAll(changed);
            log.warn("[AgentSwitch] 已恢复全部 agent: {} 个", changed.size());
        }
        return changed.size();
    }

    // ── 被拦下的调用计数 ────────────────────────────────────
    //
    // 这是这个开关的**证据**。没有它, 运维按下"暂停"之后能看到的只有"它好像安静了"
    // —— 而安静是没法证伪的: 一个 agent 本来就可能几小时不说话。有了计数, 问题变成
    // 可回答的: "拦了多少次、拦在哪一类调用上", 于是"开关到底有没有生效"从感觉变成了数字。
    //
    // 只放内存, 不落库: 这是观测数据而非业务数据, 重启归零是可接受的; 而落库的话,
    // 一个被暂停的 agent 每小时会写下几十行"我什么都没做", 把 llm_calls 那张真正
    // 记账的表淹掉。

    private final java.util.concurrent.atomic.AtomicLong blockedTotal =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentMap<String, Long> blockedByTask =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** LLM 硬闸拦下一次调用时调这里。见 {@code LlmRouter#blocked}。 */
    public void noteBlocked(String task) {
        blockedTotal.incrementAndGet();
        blockedByTask.merge(task == null ? "unknown" : task, 1L, Long::sum);
    }

    /**
     * 控制台要的那几个数 —— 一次遍历, 不是三次 {@code count(*)}。
     *
     * <p>{@code blockedByTask} 拷贝一份再交出去: 交出去的是 {@code ConcurrentHashMap}
     * 的实时视图的话, JSON 序列化会在遍历它的同时被别的线程修改, 而那是一个
     * {@code ConcurrentModificationException} —— 出现在一个只读的统计端点上。
     */
    public Stats stats() {
        int active = 0, paused = 0;
        for (Companion c : companions.findByDeletedAtIsNullOrderByCreatedAtAsc()) {
            if (AgentLifecycle.of(c.getStatus()).isPaused()) paused++;
            else active++;
        }
        return new Stats(active, paused, runtimeEnabled,
                blockedTotal.get(), new java.util.TreeMap<>(blockedByTask));
    }

    /**
     * @param active          正在跑的 agent 数
     * @param paused          被暂停的 agent 数
     * @param runtimeEnabled  部署级总闸是否打开(false 时上面两个数只是"若打开会怎样")
     * @param blockedCalls    本进程启动以来被硬闸拦下的 LLM 调用次数
     * @param blockedByTask   那些调用按 task 的分布 —— 它回答"拦在了哪条路上"
     */
    public record Stats(int active, int paused, boolean runtimeEnabled,
                        long blockedCalls, java.util.Map<String, Long> blockedByTask) {}
}
