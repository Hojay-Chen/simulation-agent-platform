package com.luxera.companion.runtime;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.runtime.pipeline.PendingMessageService;
import com.luxera.companion.runtime.pipeline.PendingMessageState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 诊断端点(只读): 观察 V11/V12 运行时内部 —— Agent 痕迹 / 待复查消息 / 世界事件 / V11 开关状态。
 * 用于验证与调试, 不影响主流程。
 *
 * <p>这里的 {@code /v5/**} 前缀是 V11 那一代的路径, {@code /agents} 读的是
 * {@link CognitiveAgentRegistry}。V2.2 的读面在 {@code /api/agents} 一族, 由
 * {@code AgentRegistry} 供数(那个装的是数字人, 不是处理器)—— 两代各说各的, 不共用前缀。
 *
 * <p>曾经这里还有一个 {@code /scheduled} 读的是 {@code scheduled_actions}。那张表与它的
 * 服务/轮询/分发器一并删掉了: 全仓零 handler 注册, 于是每一条写进去的记录都必然变成
 * FAILED —— 一个只产出失败的读面不该留着让人以为它在说什么。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/v5")
public class DiagnosticController {

    private final CurrentUser currentUser;
    private final CompanionService companionService;
    private final AgentTraceService traceService;
    private final PendingMessageService pendingMessageService;
    private final com.luxera.companion.wakeup.AgentWakeupRepository wakeupRepository;
    private final WorldEventLogService worldEventLogService;
    private final CognitiveAgentRegistry cognitiveAgents;
    private final com.luxera.companion.llm.LlmCallRepository llmCallRepository;
    private final com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository;
    private final com.luxera.companion.plan.PlanRepository planRepository;
    private final com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch;
    private final com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow;
    private final com.luxera.companion.runtime.v11.V11TurnsSwitch turnsSwitch;
    private final com.luxera.companion.runtime.v11.V11TurnPath turnPath;
    private final com.luxera.companion.mind.MindStateService mindStates;
    private final com.luxera.companion.runtime.v11.V11CognitionSwitch cognitionSwitch;
    private final com.luxera.companion.runtime.v11.CognitionDecisionRecorder cognitionRecorder;

    public DiagnosticController(CurrentUser currentUser, CompanionService companionService,
                                  AgentTraceService traceService,
                                  PendingMessageService pendingMessageService,
                                  com.luxera.companion.wakeup.AgentWakeupRepository wakeupRepository,
                                  WorldEventLogService worldEventLogService,
                                  CognitiveAgentRegistry cognitiveAgents,
                                  com.luxera.companion.llm.LlmCallRepository llmCallRepository,
                                  com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository,
                                  com.luxera.companion.plan.PlanRepository planRepository,
                                  com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch,
                                  com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow,
                                  com.luxera.companion.runtime.v11.V11TurnsSwitch turnsSwitch,
                                  com.luxera.companion.runtime.v11.V11TurnPath turnPath,
                                  com.luxera.companion.mind.MindStateService mindStates,
                                  com.luxera.companion.runtime.v11.V11CognitionSwitch cognitionSwitch,
                                  com.luxera.companion.runtime.v11.CognitionDecisionRecorder cognitionRecorder) {
        this.currentUser = currentUser;
        this.companionService = companionService;
        this.traceService = traceService;
        this.pendingMessageService = pendingMessageService;
        this.wakeupRepository = wakeupRepository;
        this.worldEventLogService = worldEventLogService;
        this.cognitiveAgents = cognitiveAgents;
        this.llmCallRepository = llmCallRepository;
        this.cognitiveSessionRepository = cognitiveSessionRepository;
        this.planRepository = planRepository;
        this.v11Switch = v11Switch;
        this.v11Shadow = v11Shadow;
        this.turnsSwitch = turnsSwitch;
        this.turnPath = turnPath;
        this.mindStates = mindStates;
        this.cognitionSwitch = cognitionSwitch;
        this.cognitionRecorder = cognitionRecorder;
    }

    private void requireOwned(String userId, String companionId) {
        companionService.requireOwned(userId, companionId);
    }

    /** V11 认知链上装了哪几个处理器 —— 顶层运维页那一块读的就是它。 */
    @GetMapping("/agents")
    public Map<String, Object> agents(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return Map.of("registered", cognitiveAgents.all().keySet().stream().sorted().toList());
    }

    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return traceService.recent(companionId, 50).stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("agent", t.getAgentName());
            m.put("event", t.getEventType());
            m.put("wake", t.getWakeReason());
            m.put("status", t.getStatus());
            m.put("input", t.getInputSummary());
            m.put("output", t.getOutput());
            m.put("latency", t.getLatencyMs());
            m.put("at", t.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * §18.1 —— <b>她排下的闹钟</b>: "她下一次什么时候醒, 因为什么"。
     *
     * <p>这是 {@code agent_schedule} 的唯一读出口。它存在的理由与那张表被造出来的理由
     * 是同一条: 排期必须是<b>看得见的</b>。一个看不见的闹钟与一个不存在的闹钟在运维上
     * 无法区分 —— "她再也没提过那件事"到底是没排上, 还是排了没响, 还是响了她没理?
     * 三个问题里只有第一个能靠这个端点回答, 而剩下两个要靠轨迹。
     *
     * <p>这里曾经读的是另一张表({@code scheduled_actions})。那张表全仓零 handler 注册,
     * 于是每条写进去的记录都必然变成 FAILED —— 端点返回得再整齐, 它显示的也只是一串失败。
     * 现在读的是真正在响的那一张。
     *
     * <p><b>只列 PENDING</b>: 响过的({@code FIRED})与取消掉的({@code CANCELLED})是历史,
     * 它们由 {@code AgentWakeupJob.purgeFinished} 按保留天数清掉。把历史也列出来会让
     * "她还等着什么"这件事淹没在"她等过什么"里。
     */
    @GetMapping("/wakeups")
    public List<Map<String, Object>> wakeups(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return wakeupRepository
                .findByAgentIdAndStatusOrderByWakeAtAsc(companionId, com.luxera.companion.wakeup.AgentWakeup.S_PENDING)
                .stream().map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("wakeAt", w.getWakeAt());
                    m.put("eventType", w.getEventType() == null ? null : w.getEventType().name());
                    m.put("source", w.getSourceKey());
                    m.put("reason", w.getReason());
                    return m;
                }).collect(Collectors.toList());
    }

    /**
     * 她决定待会儿再看的那几条 —— <b>唯一一个会吐消息正文的读面</b>(正文只在她自己
     * 已经看过、并决定推后的那一条上, 所以它不是泄漏)。运维面的解释见前端 {@code Runtime.tsx}。
     *
     * <p>{@code reviewCount} / {@code maxReviews} 一起给: 复查次数不是内部计数器, 它回答
     * 的是运维真正会问的那个问题 —— "这条她是在想, 还是已经忘了"。到 {@code maxReviews}
     * 的那一条<b>不会</b>出现在这里(它已经 EXPIRED), 所以两个数字放在一起看才能读出
     * "还剩几次"。
     */
    @GetMapping("/pending-messages")
    public List<Map<String, Object>> pendingMessages(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return pendingMessageService.pendingFor(companionId).stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("messageId", p.getMessageId());
            m.put("content", p.getSenderText());
            m.put("nextReviewAt", p.getNextReviewAt());
            m.put("reason", p.getReason());
            m.put("reviewCount", p.getReviewCount());
            m.put("maxReviews", PendingMessageService.MAX_REVIEWS);
            m.put("frictionType", p.getFrictionType());
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/world-events")
    public List<Map<String, Object>> worldEvents(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return worldEventLogService.recent(companionId, 50).stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("type", e.getEventType());
            m.put("at", e.getOccurredAt());
            m.put("payload", e.getPayload());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * V11 §25 —— 送达主链的 shadow 对比。切流之前, 这个端点就是"该不该切"的全部依据。
     *
     * <p>它存在的理由值得写在这里: V10 的 {@code ShadowDecisionRecorder} 记了几十万条,
     * 而它的 {@code stats()} / {@code recent()} <b>在整仓里没有任何调用者</b> ——
     * 没有端点也没有测试。于是那次 shadow 是纯成本: 一直写, 从没被看, 顺带漏内存。
     * 一个读不到的对比不是对比, 所以 V11 的对比数据在这里有唯一的读出口。
     *
     * <p>返回里 {@code v11WouldSkip} 是关键: 老链处理了、而新门认为她<b>根本不会注意到</b>
     * 的那些送达。它不是 bug, 它就是这次改动的内容 —— 但这个比例决定了切流值不值、
     * 以及阈值要不要先调。
     */
    @GetMapping("/v11")
    public Map<String, Object> v11(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", v11Switch.isEnabled());
        out.put("shadow", v11Switch.isShadow());
        out.put("overall", v11Shadow.stats());
        out.put("thisCompanion", v11Shadow.perAgentStats().get(companionId));
        out.put("recent", v11Shadow.recentFor(companionId, 20));
        if (!v11Switch.isActive()) {
            // 说出来比返回一堆 0 好: 否则读到全 0 的人会以为"没有分歧", 而事实是"没在看"
            out.put("note", "V11 送达主链未启用(app.v11.runtime.enabled/shadow 皆为 false), 上面的数字无意义");
        }
        out.put("turns", turns(companionId));
        out.put("cognition", cognition());
        return out;
    }

    /**
     * V11 §25.2 —— <b>认知决策的观测</b>(Phase 4 的切流判据)。
     *
     * <p>与上面两节并列的第三个问题: "她决定做不做什么, 与老链差多少"。
     * 决定能不能切流的<b>就是这一个数字</b> —— {@code wouldSilence} 的占比:
     * 切流之后, 那些本来会回、而新决策说"不回"的场合会真的安静下来。
     *
     * <p>这里刻意把三件事分开列, 而不是合成一个"一致率":
     * <pre>
     *   wouldSilence  老链回了、新决策不回   ← 切流后她会安静这么多(必须盯着的)
     *   wouldSpeak    新决策回、老链没回     ← 切流后她会多说这些(通常很小)
     *   byReason      理由分布              ← 安静的原因是"忙"还是"没看到", 处置完全不同
     * </pre>
     * 一致率会把这三种混在一起: 99% 的一致率既可能是"几乎没差别", 也可能是
     * "3% 的场合会安静下来但被大分母稀释了" —— 而后者足以让 53 个 agent 集体闭嘴。
     */
    private Map<String, Object> cognition() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", cognitionSwitch.isEnabled());
        c.put("shadow", cognitionSwitch.isShadow());
        // 只看 enabled: 认知决策挂在回复路径上, 不依赖 runtime 主链
        // (对比 turns.effective —— 那个必须同时看 runtime)
        c.put("effective", cognitionSwitch.isEffective());
        c.put("stats", cognitionRecorder.stats());
        if (!cognitionSwitch.isActive()) {
            c.put("note", "认知决策未启用(app.v11.cognition.enabled/shadow 皆为 false), 上面的数字无意义");
        } else if (!cognitionSwitch.isEnabled()) {
            c.put("note", "shadow 期: 决策只记账不生效。wouldSilence 的占比就是切流后她会安静下来的比例 —— "
                    + "这个数字大到某个程度就不是'更真实', 而是'她坏了'。");
        }
        return c;
    }

    /**
     * V11 §8 —— <b>回合合并的观测</b>。Phase 3 的切流判据就在 {@code messagesPerTurn} 上。
     *
     * <p>它和上面那段回答的是两个不同的问题, 所以是两个并列的小节而不是揉进一个:
     * <ul>
     *   <li>{@code shadow} 回答"新门会漏掉多少条消息"(Phase 2 的差异)</li>
     *   <li>{@code turns} 回答"连着来的几句话会被并成几次认知"(Phase 3 的差异)</li>
     * </ul>
     * 两者的开关也是分开的, 于是切流那天可以一次只翻一个、各自归因。
     *
     * <p>刻意同时给出<b>内存里的计数器</b>与<b>落库的累计值</b>: 前者从这次进程启动算起,
     * 后者跨重启。只给一个的话, 一次部署就会让"合并率"看起来突然变成 0 或突然翻倍,
     * 而那个数字正是用来决定要不要切流的。
     */
    private Map<String, Object> turns(String companionId) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("enabled", turnsSwitch.isEnabled());
        t.put("shadow", turnsSwitch.isShadow());
        // isEffective 而不是 enabled: turns 长在 V11 送达主链上, runtime 没接管时它只是一行配置
        t.put("effective", turnsSwitch.isEffective());
        t.put("window", turnPath.aggregator().window());
        t.put("sinceStartup", turnPath.aggregator().stats());
        t.put("openTurns", turnPath.aggregator().openTurnsOf(companionId));
        t.put("persisted", mindStates.snapshot(companionId).turns());
        t.put("mind", mindStates.snapshot(companionId).focus());
        t.put("threads", mindStates.threadsOf(companionId));
        if (turnsSwitch.isEnabled() && !v11Switch.isEnabled()) {
            t.put("note", "turns.enabled=true 但 runtime.enabled=false —— 回合合并长在 V11 送达主链上, "
                    + "今天不会有任何效果。先开 app.v11.runtime.enabled。");
        } else if (!turnsSwitch.isActive()) {
            t.put("note", "回合合并未启用(app.v11.turns.enabled/shadow 皆为 false), 上面的数字无意义");
        }
        return t;
    }
}