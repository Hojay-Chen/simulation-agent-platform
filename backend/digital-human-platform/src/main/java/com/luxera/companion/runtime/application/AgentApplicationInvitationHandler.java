package com.luxera.companion.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LAP v2 R13: <b>有人邀请数字人加入一场会话时, 它自己决定去不去。</b>
 *
 * <h2>为什么这是数字人自己的事</h2>
 * <p>平台的邀请服务只做三件事: 铸一张票、把票寄给被邀请的人、等有人来兑。它<b>不</b>替收件人
 * 决定 —— 一张定向票说明"有人想让你来", 不说明"你必须来"。所以这条链的最后一跳落在数字人这边,
 * 而这一跳的形状与它处理任何一件事的形状相同: 问 LLM, 不猜。
 *
 * <h2>三个决定, 而不是两个</h2>
 * <ul>
 *   <li>{@link Decision#ACCEPT} —— 它想了, 于是去兑票进场, 并记一笔账。</li>
 *   <li>{@link Decision#REJECT} —— 它想过之后说了不。也记一笔账: "他谢绝过谁"是账本该回答的问题。</li>
 *   <li>{@link Decision#IGNORE} —— <b>它没有做出决定</b>: LLM 不可用、是 mock、或者答得不能采信。
 *       什么都不记。这一档的存在理由是把"没决定"与"决定不去"分开 ——
 *       把服务抖动写成"这个数字人拒绝了邀请", 是账本里最难查的一类假话。</li>
 * </ul>
 *
 * <h2>票是怎么进来的, 以及它绝不能去哪儿</h2>
 * <p>明文 token 在事件的 payload 里 —— 收件人是个数字人, 它没有浏览器可以点开
 * {@code /join/{token}}, 所以那封信就是它的链接(见 {@code InvitationService.invitationEvent})。
 * 它拿着票走的是 {@code ApplicationRuntimePort.joinByInvitation}, 与真人点开链接
 * <em>逐字相同</em>的那一扇门 —— 本类没有、也不能有第二条路。
 *
 * <p><b>因此这段 token 在本类里只有一个去处: 那个方法的第一个参数。</b> 不写日志、不写账本、
 * 不进提示词、不进异常消息。一条凭据一旦被记进"记忆"里就不再是凭据了 —— 而账本是永久的。
 * 这个约束不是靠自觉: {@code AgentApplicationInvitationTest} 里有一条断言盯着账本里没有它。
 */
@Slf4j
@Component
public class AgentApplicationInvitationHandler {

    /**
     * 平台事件 {@code ApplicationEvent.type()} 上的那个字面量。
     *
     * <p>它必须在这里有一份副本: {@code ExternalEventType} 只有一个常量 {@code APPLICATION_EVENT},
     * 平台事件与普通应用事件在<em>类型</em>上是同一个东西, 区分它们的只有 payload 里的
     * {@code eventType}。契约模块给了数字人 {@code ApplicationRuntimePort}, 却没有(也不该有)
     * 平台内部的事件词汇表 —— 于是这一行是那道单向门上的一道缝, 缝的宽度正好是一个字符串常量。
     */
    public static final String EVENT_TYPE = "APPLICATION_INVITATION";

    /** 数字人对一封邀请信的态度。见类注释: 第三种不是"不去", 是"没决定"。 */
    public enum Decision {
        /** 想去 —— 兑票进场。 */
        ACCEPT,
        /** 想过之后不去 —— 记一笔, 不进场。 */
        REJECT,
        /** 没有做出决定 —— 什么都不做, 也什么都不记。 */
        IGNORE
    }

    private final ApplicationRuntimePort port;
    private final LlmRouter llmRouter;
    private final RealityLedger realityLedger;
    private final EventRouter eventRouter;
    private final PersonActorRegistry personActorRegistry;
    private final PersonaService personaService;

    public AgentApplicationInvitationHandler(ApplicationRuntimePort port,
                                             LlmRouter llmRouter,
                                             RealityLedger realityLedger,
                                             EventRouter eventRouter,
                                             PersonActorRegistry personActorRegistry,
                                             PersonaService personaService) {
        this.port = port;
        this.llmRouter = llmRouter;
        this.realityLedger = realityLedger;
        this.eventRouter = eventRouter;
        this.personActorRegistry = personActorRegistry;
        this.personaService = personaService;
    }

    /**
     * 用 subscribe 而不是 register —— 与 {@code AgentApplicationFlow} 一样。
     *
     * <p>两个消费者看的是同一批事件, 但看的是里面不同的东西: 那边看"应用里发生了什么",
     * 这边看"有人点名找我"。邀请事件也会流到那边, 由那边在入口处让开(见
     * {@code AgentApplicationFlow.onApplicationEvent})。
     */
    @PostConstruct
    void registerRoutes() {
        eventRouter.subscribe(ExternalEventType.APPLICATION_EVENT, this::onApplicationEvent);
        log.info("[AgentApplicationInvitationHandler] 已订阅 {}", EVENT_TYPE);
    }

    void onApplicationEvent(ExternalEvent event) {
        if (event == null || !ExternalEventType.APPLICATION_EVENT.equals(event.type())) return;
        if (!EVENT_TYPE.equals(event.str("eventType"))) return;

        String companionId = event.personId();
        String sessionId = event.str("sessionId");
        String token = event.str("token");
        if (companionId == null || sessionId == null || token == null) {
            // 只说"缺了什么", 绝不说"缺的那个值是什么" —— token 可能就在旁边
            log.warn("[AgentInvitation] 邀请事件形状不全(companionId/sessionId/token 缺一), 已丢弃");
            return;
        }
        final String applicationId = event.str("applicationId");
        final String role = event.str("role");
        final String invitationId = event.str("invitationId");
        // 跑在这个人的邮箱线程上: 与聊天、与反应路径共享同一把 per-person 串行锁,
        // 于是"决定去"与"已经在别处动了手"不会交错。
        personActorRegistry.tell(companionId, () ->
                handle(companionId, applicationId, sessionId, invitationId, role, token));
    }

    /**
     * 一次邀请的完整处置。包内可见, 便于确定性的单测 —— 那些用例直接调它, 不必造一个邮箱。
     *
     * @param token 明文票。唯一去处是 {@code port.joinByInvitation} —— 见类注释。
     */
    Decision handle(String companionId, String applicationId, String sessionId,
                    String invitationId, String role, String token) {
        Decision decision = decide(companionId, applicationId, role);
        switch (decision) {
            case ACCEPT -> accept(companionId, applicationId, sessionId, invitationId, token);
            case REJECT -> {
                log.info("[AgentInvitation] {} 谢绝了邀请: application={}, session={}",
                        companionId, applicationId, sessionId);
                appendReality(companionId, RealityEventType.APPLICATION_INVITATION_DECLINED,
                        applicationId, sessionId, invitationId);
            }
            case IGNORE -> log.debug("[AgentInvitation] {} 这一次没有决定, 什么都不做", companionId);
        }
        return decision;
    }

    // ─────────────────────────── 决定 ───────────────────────────

    /**
     * 问 LLM 想不想去 —— 以及"答得不能采信"时怎么办。
     *
     * <p>与反应路径同一条守卫(用户明确要求): LLM 不可用或当前是 mock provider 时, 这里得到的是
     * {@link Decision#IGNORE}, 而不是某个默认值。默认值是这里最危险的东西: 默认"去"会让数字人
     * 在服务抖动时到处乱窜, 默认"不去"会让它替自己撒一次谎。"没有决定"才是事实。
     *
     * <p>答得不能采信(没有 {@code accept} 字段)也归 IGNORE。这不是宽容, 是同一个理由:
     * 从一句读不懂的话里挑一个布尔出来, 挑的那个就是启发式。
     */
    Decision decide(String companionId, String applicationId, String role) {
        if (!llmRouter.available() || llmRouter.isMockActive()) {
            log.warn("[AgentInvitation] LLM 不可用, 这一次邀请不作处置(不是谢绝): companion={}", companionId);
            return Decision.IGNORE;
        }
        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderSystem(companionId, applicationId, role))
                    .user("这事你怎么看?")
                    .task("application-invitation-decision")
                    .schemaHint("{\"accept\":true,\"reason\":\"…\"}")
                    .temperature(0.3)
                    .metadata(CapabilityResolver.meta(companionId, "application-invitation"))
                    .build());
            JsonNode json = result.getJson();
            if (json == null || !json.hasNonNull("accept")) {
                log.warn("[AgentInvitation] LLM 的答复里没有 accept, 当它没决定: companion={}", companionId);
                return Decision.IGNORE;
            }
            return json.path("accept").asBoolean(false) ? Decision.ACCEPT : Decision.REJECT;
        } catch (Exception e) {
            log.warn("[AgentInvitation] 邀请决策失败, 当它没决定: {}", e.getMessage());
            return Decision.IGNORE;
        }
    }

    // ─────────────────────────── 进场 ───────────────────────────

    /**
     * 兑票进场, 票不灵了就试试门还开不开。
     *
     * <p>两条路, 顺序不能换:
     * <ol>
     *   <li>{@code joinByInvitation(token, ctx)} —— <b>这一条才是"接受邀请"</b>。它走的是
     *       {@code InvitationService.consume}, 与真人点开分享链接完全同一条。票指向哪一场,
     *       人就进哪一场 —— 事件里那个 {@code sessionId} 只是信封上的地址, 不是凭据。</li>
     *   <li>{@code joinSession(sessionId, ctx)} —— 只在第一条失败之后试。它对应一种真会发生的
     *       情形: 票被撤了或过期了, 但那一场本身是 {@code OPEN} 的, 而我又确实想去。
     *       这时"门开着"本身就是准入, 不需要票。</li>
     * </ol>
     * <p>两条都失败就什么都没发生 —— 不记 {@code ACCEPTED}, 不重试, 也不退回去改口说"谢绝"。
     * 一封没能兑现的邀请信是一个事实, 而它已经在日志里了。
     */
    private void accept(String companionId, String applicationId, String sessionId,
                        String invitationId, String token) {
        // 相关性用 invitationId 而不是 sessionId: 账本要能回答"是哪一封信让他进去的"
        InvocationContext ctx = InvocationContext.agent(companionId, null, invitationId);
        try {
            String joined = port.joinByInvitation(token, ctx);
            log.info("[AgentInvitation] {} 接受了邀请, 进场: session={}", companionId, joined);
            appendReality(companionId, RealityEventType.APPLICATION_INVITATION_ACCEPTED,
                    applicationId, joined, invitationId);
            return;
        } catch (Exception e) {
            log.info("[AgentInvitation] 票没兑成({}), 再看看这一场还开不开着门: session={}",
                    e.getMessage(), sessionId);
        }
        try {
            port.joinSession(sessionId, ctx);
            log.info("[AgentInvitation] {} 从开着的门进去了: session={}", companionId, sessionId);
            appendReality(companionId, RealityEventType.APPLICATION_INVITATION_ACCEPTED,
                    applicationId, sessionId, invitationId);
        } catch (Exception e) {
            log.warn("[AgentInvitation] 两扇门都没开, 这一场没进去: session={}, reason={}",
                    sessionId, e.getMessage());
        }
    }

    // ─────────────────────────── 提示词 ───────────────────────────

    /**
     * 邀请决策的 system。
     *
     * <p>三段: 我是谁 → 这是一件什么事 → 什么算答应。最后一段是这段提示词的全部要害 ——
     * 模型天然倾向于"被邀请了就答应"(那是它训练数据里更有礼貌的答案), 而这里要的是
     * <em>这个数字人</em>想不想去。所以规则里明写"邀请本身不是理由"。
     */
    String renderSystem(String companionId, String applicationId, String role) {
        StringBuilder sb = new StringBuilder();
        String persona = personaLine(companionId);
        if (persona != null) {
            sb.append("你是").append(persona).append("。\n");
        } else {
            sb.append("你是一个数字人。\n");
        }
        sb.append("\n有人邀请你加入一个应用里的会话。\n");
        sb.append("应用: ").append(describeApplication(applicationId)).append('\n');
        sb.append("他给你的位置: ").append(roleLabel(role)).append('\n');
        sb.append("\n规则:\n");
        sb.append("- accept=true 表示你答应。答应意味着你真的会进入那一场, 那里的人会看到你。\n");
        sb.append("- accept=false 表示你谢绝。谢绝会被记下来, 但那只是说明你不想去。\n");
        sb.append("- <b>邀请本身不是理由</b> —— 不要因为「被邀请了」就答应, 要因为你想去才答应。\n");
        sb.append("- 看不出这是什么、或者你现在没有理由去 → accept=false。\n");
        sb.append("\n只输出 JSON: {\"accept\": true, \"reason\": \"<一句话理由>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /**
     * 这个应用叫什么 —— 问平台的能力目录, 而不是在本类里认识任何一个应用。
     *
     * <p>这一次查找比别处贵(能力目录 × 每个能力下的应用), 但它只在收到邀请时发生一次 ——
     * 而一封连"是什么应用"都说不清的邀请信, 只能换来一个没有信息量的决定。
     * 找不到就退回 id: 一个陌生的应用号至少是诚实的。
     */
    private String describeApplication(String applicationId) {
        if (applicationId == null) return "(未知应用)";
        try {
            for (CapabilityView capability : port.capabilities()) {
                for (ApplicationView view : port.applicationsFor(capability.capabilityId())) {
                    if (applicationId.equals(view.applicationId())) {
                        String desc = view.description() == null || view.description().isBlank()
                                ? "" : " —— " + view.description();
                        return view.name() + " v" + view.version() + desc;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AgentInvitation] 读应用名失败: {}", e.getMessage());
        }
        return applicationId;
    }

    /** 人格只取名字与一句性格概述 —— 决定去不去要的是"这个人会不会去", 不是完整人设。 */
    private String personaLine(String companionId) {
        try {
            Persona persona = personaService.getActive(companionId);
            if (persona == null) return null;
            String name = persona.getIdentity() == null ? null : persona.getIdentity().getName();
            String summary = persona.getPersonality() == null ? null : persona.getPersonality().getSummary();
            if (name == null && summary == null) return null;
            return ((name == null ? "" : name + "。") + (summary == null ? "" : summary)).trim();
        } catch (Exception e) {
            log.debug("[AgentInvitation] 读人格失败: {}", e.getMessage());
            return null;
        }
    }

    /** 角色是平台的词, 提示词要的是人话。认不出的角色原样带过去, 不猜。 */
    private static String roleLabel(String role) {
        if (role == null || role.isBlank()) return "参与者";
        return switch (role) {
            case "OWNER" -> "主人";
            case "MEMBER" -> "参与者";
            case "OBSERVER" -> "旁观者";
            default -> role;
        };
    }

    // ─────────────────────────── 记账 ───────────────────────────

    /** 只有"真的发生了的事"进账本 —— 接受与谢绝都算了, 忽略不算。见 {@link Decision}。 */
    private void appendReality(String companionId, RealityEventType type, String applicationId,
                               String sessionId, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", applicationId);
            payload.put("sessionId", sessionId);
            // 刻意不写 token / invitationId 之外的东西: 邀请 id 不是凭据, 它是这张票的名字
            payload.put("invitationId", correlationId);
            realityLedger.append(companionId, type, payload, clip(correlationId), null);
        } catch (Exception e) {
            log.warn("[AgentInvitation] 写现实账本失败: {}", e.getMessage());
        }
    }

    /** {@code reality_event.correlation_id} 是 varchar(64) —— 与 {@code AgentApplicationFlow} 同理。 */
    private static String clip(String correlationId) {
        if (correlationId == null || correlationId.length() <= 64) return correlationId;
        return correlationId.substring(0, 64);
    }
}
