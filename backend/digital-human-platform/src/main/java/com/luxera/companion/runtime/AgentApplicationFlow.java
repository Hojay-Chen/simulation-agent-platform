package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.runtime.application.ActionSelector;
import com.luxera.companion.runtime.application.AgentApplicationInvitationHandler;
import com.luxera.companion.runtime.application.ApplicationResolver;
import com.luxera.companion.runtime.application.CapabilityResolver;
import com.luxera.companion.runtime.application.SessionResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LAP — 数字人与应用平台之间的<b>唯一一条认知链</b>。两个方向:
 *
 * <h2>反应(reactive): 应用里发生了什么 → 我该做什么</h2>
 * <p>它取代了原先写在 {@code AgentRuntime} 里的 {@code onApplicationEvent} —— 那段代码把某个
 * 具体应用的应用常量 import 进认知链、自己解析局面 JSON、自己拼幂等键、自己判断轮到谁。
 * 于是"再加一个应用"必须改认知链。现在不是了。
 *
 * <h2>八段流水线 (R13)</h2>
 * <p>R13 之前这四步写在一个方法里。拆成八段不是为了好看, 是因为 v2 让"哪一场"变成了一个真正的
 * 问题: 同一个人可以在同一个应用里开好几场, 还可能被人拉进别人开的场子。于是"读资源"与
 * "我该在哪一场里动手"必须各占一段, 而不是藏在某一行里。
 *
 * <pre>
 *   READ      读资源              → 资源不存在就停
 *   LOCATE    定位会话            → 定位不到不停(网关自己还有三档兜底), 但要把结论记下来
 *   CONTEXT   组装身份            → 把定位到的会话钉进 InvocationContext
 *   PENDING   问"现在能做什么"     → 空则停(轮到别人 / 已终局 / 无事可做)
 *   ELIGIBLE  我现在够格动吗       → LLM 不可用或 mock ⇒ 停 ★用户明确要求的守卫★
 *   DECIDE    问 LLM 做哪一件      → 空则停(它说不行动, 或者答得不能采信)
 *   EXECUTE   执行                → 被拒就停, 且不记账
 *   RECORD    记账                → 只有真的做成了才写现实账本
 * </pre>
 *
 * <p>四段"停"的分工就是 {@link PipelineReport#stoppedAt()} 的全部意义: 事后问"数字人为什么没动"
 * 时, 答案不是一句"它没动", 而是"它停在了 ELIGIBLE, 因为 LLM 不可用"。{@link Stage} 是公开的,
 * {@link PipelineReport} 也是 —— 因为它回答的那个问题是运维问题, 不是实现细节。
 *
 * <h2>四个协作者</h2>
 * <p>每一段各自需要的那点判断被搬到了 {@code runtime/application/} 下:
 * {@link CapabilityResolver}(意图→能力)、{@link ApplicationResolver}(能力→应用)、
 * {@link ActionSelector}(能做什么→做哪件)、{@link SessionResolver}(在哪一场)。本类剩下的
 * 只有<em>顺序</em>与<em>上下文</em> —— 也就是说, 认识"某个具体应用"的地方一个都没有。
 *
 * <h2>主动(initiative): 用户说了一句话 → 这该动用哪个应用</h2>
 * <p>见 {@link #route(String, String)}: 意图 → 能力 → 应用, 逐级收窄。数字人认识的是
 * {@link CapabilityView} 这份<b>能力目录</b>, 不是任何具体应用 —— 这就是"50000 个 action
 * 不塞给 LLM"的落点: 先在一张几十行的目录里选一行, 再谈别的。
 *
 * <h2>LLM 优先, 绝不降级到启发式</h2>
 * <p>(用户明确要求) LLM 不可用或当前是 mock provider 时直接不行动 —— 没有启发式、没有随机、
 * 没有"随便挑第一个空格"。反应路径上这条守卫是 {@link Stage#ELIGIBLE} 那一段; 主动路径上它
 * 体现为 mock 网关对未知 task 回空对象 ⇒ {@link #route} 得到"没有任何能力适用"。
 * {@code AgentApplicationFlowTest} 的"零次 execute"断言是这条性质的保险丝。
 *
 * <p>反应部分整体跑在 {@code personActorRegistry.tell(personId, ...)} 的 mailbox 里:
 * 原先它跑在用户 HTTP 请求线程上, 与聊天路径没有共享同一把 per-person 串行锁。
 */
@Slf4j
@Service
public class AgentApplicationFlow {

    private final ApplicationRuntimePort applicationRuntimePort;
    private final LlmRouter llmRouter;
    private final RealityLedger realityLedger;
    private final EventRouter eventRouter;
    private final PersonActorRegistry personActorRegistry;

    private final CapabilityResolver capabilityResolver;
    private final ApplicationResolver applicationResolver;
    private final ActionSelector actionSelector;
    private final SessionResolver sessionResolver;

    /**
     * 能力选择的置信度门槛: 低于它 = "这句话不需要动用任何应用"。
     *
     * <p>这个数字就是设计文档那句「大多数日常聊天不需要任何应用」的可测试版本 ——
     * 调低会让闲聊被当成应用请求, 调高会让明确的请求被漏掉, 所以它是一个配置项而不是常量。
     */
    private final double capabilityThreshold;

    public AgentApplicationFlow(ApplicationRuntimePort applicationRuntimePort,
                                LlmRouter llmRouter,
                                RealityLedger realityLedger,
                                EventRouter eventRouter,
                                PersonActorRegistry personActorRegistry,
                                PersonaService personaService,
                                @Value("${app.lap.capability-threshold:0.6}") double capabilityThreshold) {
        this.applicationRuntimePort = applicationRuntimePort;
        this.llmRouter = llmRouter;
        this.realityLedger = realityLedger;
        this.eventRouter = eventRouter;
        this.personActorRegistry = personActorRegistry;
        this.capabilityThreshold = capabilityThreshold;
        // 协作者是普通对象而不是 Bean: 它们的构造参数全是本类已有的依赖, 提成 Bean 只会让
        // 这个模块多出四个"只有这里有"的单例名字(见 BeanNameCollisionArchitectureTest 的告诫)。
        this.capabilityResolver = new CapabilityResolver(applicationRuntimePort, llmRouter, capabilityThreshold);
        this.applicationResolver = new ApplicationResolver(applicationRuntimePort, llmRouter);
        this.actionSelector = new ActionSelector(applicationRuntimePort, llmRouter, personaService);
        this.sessionResolver = new SessionResolver(applicationRuntimePort);
    }

    /**
     * 用 subscribe 而非 register —— {@code register} 是覆盖语义, 会把别的消费者挤掉,
     * 而 {@code EventProcessingChainTest} / {@code OutboxRelayTest} 依赖那套语义。
     */
    @PostConstruct
    void registerRoutes() {
        eventRouter.subscribe(ExternalEventType.APPLICATION_EVENT, this::onApplicationEvent);
        log.info("[AgentApplicationFlow] 已订阅 APPLICATION_EVENT");
    }

    void onApplicationEvent(ExternalEvent event) {
        if (event == null || !ExternalEventType.APPLICATION_EVENT.equals(event.type())) return;
        String companionId = event.personId();
        String resourceUri = event.str("resourceUri");
        if (companionId == null || resourceUri == null) return;

        // 邀请不是"应用里发生了什么事", 是"有人点名找我" —— 它有自己的处理者。
        // 这道判断之所以必须在这里, 是因为 ExternalEventType 只有一个常量: 邀请事件与应用事件
        // 在类型上是同一个东西, 区分它们的只有 payload 里的 eventType。
        if (AgentApplicationInvitationHandler.EVENT_TYPE.equals(event.str("eventType"))) return;

        // agentTrigger 由应用算出(它认得自己的事件类型与规则), 数字人只认这个布尔
        if (!Boolean.TRUE.equals(event.get("agentTrigger"))) return;

        final String userId = event.str("userId");
        final String correlationId = event.eventId();
        // 应用可以在事件里点名"这件事发生在哪一场" —— 它比任何推断都准, 所以它是第一策略。
        final String sessionId = event.str("sessionId");
        personActorRegistry.tell(companionId, () -> run(
                new Reaction(companionId, userId, resourceUri, correlationId).explicit(sessionId)));
    }

    // ═══════════════════════════ 八段流水线 ═══════════════════════════

    /** 流水线的八段。停止在哪一段, 就是"数字人为什么没动"的答案。 */
    public enum Stage {
        /** 读资源。 */
        READ,
        /** 定位会话。 */
        LOCATE,
        /** 组装身份。 */
        CONTEXT,
        /** 问"现在能做什么"。 */
        PENDING,
        /** 我现在够格动吗(LLM 优先, 绝不降级)。 */
        ELIGIBLE,
        /** 问 LLM 做哪一件。 */
        DECIDE,
        /** 执行。 */
        EXECUTE,
        /** 记账。 */
        RECORD
    }

    /**
     * 这一趟走到了哪里。
     *
     * @param stoppedAt  停下的那一段; 走完全程是 {@link Stage#RECORD}
     * @param reason     稳定的短码, 不是给人读的句子(日志里那句才是)
     * @param sessionId  定位到的会话, 可能是 null("定位不到"不是失败, 见 {@link Stage#LOCATE})
     * @param strategy   凭什么定位到的
     * @param actionId   真的执行了的动作, 没执行则为 null
     */
    public record PipelineReport(Stage stoppedAt, String reason, String sessionId,
                                 SessionResolver.Strategy strategy, String actionId) {}

    /**
     * 第 2–8 段。跑在该 Person 的 mailbox 线程上(包内可见, 便于确定性单测)。
     *
     * <p>不接收"哪一场": 事件入口那条路走 {@link #onApplicationEvent}, 它手上才有 payload。
     * 这个重载是给"我手上只有一个资源 URI"的调用方(以及单测)用的 —— 它会退回策略 2、3。
     */
    PipelineReport react(String companionId, String userId, String resourceUri, String correlationId) {
        return run(new Reaction(companionId, userId, resourceUri, correlationId));
    }

    private PipelineReport run(Reaction r) {
        try {
            if (!readStage(r)) return r.report();
            locateStage(r);
            contextStage(r);
            if (!pendingStage(r)) return r.report();
            if (!eligibleStage(r)) return r.report();
            if (!decideStage(r)) return r.report();
            if (!executeStage(r)) return r.report();
            recordStage(r);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 处理应用事件失败 resource={}: {}", r.resourceUri, e.getMessage());
            r.stop(Stage.EXECUTE, "EXCEPTION");
        }
        return r.report();
    }

    private boolean readStage(Reaction r) {
        ResourceView resource = applicationRuntimePort.read(r.resourceUri).orElse(null);
        if (resource == null) {
            return r.stop(Stage.READ, "RESOURCE_NOT_FOUND");
        }
        r.resource = resource;
        return true;
    }

    /**
     * 定位"我该在哪一场里动手"。
     *
     * <p><b>定位不到不是失败</b> —— 这是这一段与其它七段唯一的分别, 也是它没有返回值的原因。
     * 资源行上没有会话的应用多的是(提醒收件箱就是), 网关那边还有三档兜底
     * ({@code URI 模板 → 最近的 ACTIVE 会话 → ensureSession})。在这里把它变成硬闸门,
     * 只会让这些应用的事件从此一个人也唤不醒。
     */
    private void locateStage(Reaction r) {
        SessionResolver.Query query = SessionResolver.Query
                .of(r.companionId, r.resource.applicationId())
                .withExplicit(r.explicitSessionId)
                .withResource(r.resource.sessionId());
        Optional<SessionResolver.Resolution> found =
                sessionResolver.locate(query, InvocationContext.agent(r.companionId, r.userId, r.correlationId));
        if (found.isPresent()) {
            r.sessionId = found.get().sessionId();
            r.strategy = found.get().by();
            return;
        }
        log.debug("[AgentApplicationFlow] 定位不到会话, 交给网关自己解析: resource={}", r.resourceUri);
    }

    private void contextStage(Reaction r) {
        InvocationContext ctx = InvocationContext.agent(r.companionId, r.userId, r.correlationId);
        // 钉进去, 让网关不必再猜 —— 它那边的第一档就是 context.sessionId
        r.ctx = r.sessionId == null ? ctx : ctx.withSession(r.sessionId);
    }

    private boolean pendingStage(Reaction r) {
        List<ActionSpec> pending = applicationRuntimePort.pendingActions(r.resourceUri, r.ctx);
        if (pending == null || pending.isEmpty()) {
            return r.stop(Stage.PENDING, "NOTHING_PENDING");
        }
        r.pending = pending;
        return true;
    }

    /** ── LLM 优先, 绝不降级到启发式 ── */
    private boolean eligibleStage(Reaction r) {
        if (!llmRouter.available() || llmRouter.isMockActive()) {
            log.warn("[AgentApplicationFlow] LLM 不可用, 数字人不行动: resource={}", r.resourceUri);
            return r.stop(Stage.ELIGIBLE, "LLM_UNAVAILABLE");
        }
        return true;
    }

    private boolean decideStage(Reaction r) {
        Optional<ActionSelector.Decision> decision =
                actionSelector.select(r.pending, r.resource, r.companionId);
        if (decision.isEmpty()) {
            return r.stop(Stage.DECIDE, "NO_DECISION");
        }
        r.decision = decision.get();
        return true;
    }

    private boolean executeStage(Reaction r) {
        ActionSelector.Decision decision = r.decision;
        ActionResponse response = applicationRuntimePort.execute(
                new ActionRequest(decision.action().actionId(), r.resourceUri,
                        decision.input(), r.resource.version()), r.ctx);
        if (response == null) {
            return r.stop(Stage.EXECUTE, "NO_RESPONSE");
        }
        if (!response.isSuccess()) {
            log.warn("[AgentApplicationFlow] 动作被拒: action={}, resource={}, status={}, err={}",
                    decision.action().actionId(), r.resourceUri, response.status(),
                    response.error() == null ? null : response.error().message());
            return r.stop(Stage.EXECUTE, "REFUSED:" + response.status());
        }
        r.actionId = decision.action().actionId();
        log.info("[AgentApplicationFlow] 数字人执行动作: action={}, resource={}, session={}",
                r.actionId, r.resourceUri, r.sessionId);
        return true;
    }

    private void recordStage(Reaction r) {
        appendReality(r.companionId, r.resource, r.decision.action(), r.decision.input(), r.correlationId);
        r.stop(Stage.RECORD, "RECORDED");
    }

    // ═══════════════════════════ 主动: 用户说了一句话 → 用哪个应用 ═══════════════════════════

    /** 意图路由的结果: 该用哪个能力下的哪个应用。 */
    public record Intent(String capabilityId, String applicationId, double confidence, String reason) {}

    /**
     * 用户的一句话 → (能力, 应用)。没有任何应用该被牵扯进来时返回空。
     *
     * <p>这是"意图 → 能力 → 应用"这条收窄路径的入口, 也是数字人唯一一次<b>主动</b>决定要用
     * 一个应用。它取代了原先那种"每个功能各自认识自己的那个应用"的写法。
     */
    public Optional<Intent> route(String companionId, String userText) {
        Optional<CapabilityResolver.Choice> capability = capabilityResolver.select(companionId, userText);
        if (capability.isEmpty()) return Optional.empty();
        Optional<String> application = applicationResolver.select(
                companionId, capability.get().capabilityId(), userText);
        if (application.isEmpty()) return Optional.empty();
        CapabilityResolver.Choice c = capability.get();
        return Optional.of(new Intent(c.capabilityId(), application.get(), c.confidence(), c.reason()));
    }

    /** 第一级: 意图 → 能力。见 {@link CapabilityResolver}(给 LLM 看的是能力目录, 不是 action 列表)。 */
    public Optional<CapabilityResolver.Choice> selectCapability(String companionId, String userText) {
        return capabilityResolver.select(companionId, userText);
    }

    /** 第二级: 能力 → 具体应用。见 {@link ApplicationResolver}(只有一个候选时不问 LLM)。 */
    public Optional<String> selectApplication(String companionId, String capabilityId, String userText) {
        return applicationResolver.select(companionId, capabilityId, userText);
    }

    /**
     * 数字人要用某个应用时, 先确保自己在那儿有个位置 —— 能进现成的就进, 进不去就开一场。
     *
     * <p>它是 {@link SessionResolver#enter} 在这条链上的出口: 主动路径得到的是一个
     * {@link Intent}(能力+应用), 而不是一场会话; 会话要另外问。这两件事分开, 是因为
     * "该用哪个应用"是一个可以在没有会话的情况下回答的问题(用户还没答应玩呢)。
     */
    public SessionResolver.Resolution enterSession(String companionId, String applicationId,
                                                  String userId, String correlationId) {
        return sessionResolver.enter(SessionResolver.Query.of(companionId, applicationId),
                InvocationContext.agent(companionId, userId, correlationId));
    }

    /**
     * 能力目录的指纹。目录变了指纹就变 —— 事后翻 {@code llm_calls} 能知道模型当时看的是什么。
     *
     * <p>先按 id 排序再拼: 平台给出的顺序不必是稳定的, 而"目录没变、指纹却变了"会让这个字段
     * 失去全部意义。留在本类上是因为它是这条链对外的样子; 实现只有一份, 在
     * {@link CapabilityResolver#fingerprint}。
     */
    static String hash(List<CapabilityView> catalogue) {
        return CapabilityResolver.fingerprint(catalogue);
    }

    // ═══════════════════════════ 记账 ═══════════════════════════

    /** 账本语义只在数字人侧: 应用不知道自己被记了什么。 */
    private void appendReality(String companionId, ResourceView resource, ActionSpec action,
                               JsonNode input, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", resource.applicationId());
            payload.put("resource", resource.uri());
            payload.put("action", action.actionId());
            payload.put("input", input);
            realityLedger.append(companionId, RealityEventType.APPLICATION_ACTION_EXECUTED,
                    payload, clip(correlationId), null);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 写现实账本失败: {}", e.getMessage());
        }
    }

    /**
     * reality_event.correlation_id 是 varchar(64), 而事件 id 里含完整 resource id 时会超长 ——
     * 超长会让整条账本写入失败(只剩一条 WARN), 于是"数字人做过什么"就丢了。
     * 相关性 id 本就是不透明串, 截断比丢账本划算。
     */
    private static String clip(String correlationId) {
        if (correlationId == null || correlationId.length() <= 64) return correlationId;
        return correlationId.substring(0, 64);
    }

    // ═══════════════════════════ 流水线的状态 ═══════════════════════════

    /**
     * 一趟流水线的全部中间状态。
     *
     * <p>做成一个可变对象而不是八个返回值, 是因为这八段是<b>顺序</b>关系而不是组合关系 ——
     * 每一段都要看上一段留下了什么。把结论放进一个对象里, 最直接的好处是: 任何一段停在哪儿,
     * 前面几段查到了什么, 都能原样被 {@link #report()} 说出去。
     */
    private static final class Reaction {
        final String companionId;
        final String userId;
        final String resourceUri;
        final String correlationId;

        String explicitSessionId;
        ResourceView resource;
        String sessionId;
        SessionResolver.Strategy strategy = SessionResolver.Strategy.NONE;
        InvocationContext ctx;
        List<ActionSpec> pending = List.of();
        ActionSelector.Decision decision;
        String actionId;

        Stage stoppedAt = Stage.RECORD;
        String reason = "RECORDED";

        Reaction(String companionId, String userId, String resourceUri, String correlationId) {
            this.companionId = companionId;
            this.userId = userId;
            this.resourceUri = resourceUri;
            this.correlationId = correlationId;
        }

        Reaction explicit(String sessionId) {
            this.explicitSessionId = sessionId;
            return this;
        }

        boolean stop(Stage stage, String reason) {
            this.stoppedAt = stage;
            this.reason = reason;
            return false;
        }

        PipelineReport report() {
            return new PipelineReport(stoppedAt, reason, sessionId, strategy, actionId);
        }
    }
}
