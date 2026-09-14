package com.luxera.companion.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数字人侧提醒适配层 —— <b>它证明的是"数字人这里没有提醒的真相"</b>。
 *
 * <p>R5 之前, 提醒是数字人的一张表: {@code ReminderRepository.save()} 就是全部, 这里没什么
 * 可测的(测的是 JPA)。现在这两个类只做三件事, 用例也就只盯这三件事:
 *
 * <ol>
 *   <li><b>读的是那个 URI。</b> {@code reminder://owner/{userId}} —— 与 manifest 声明的一致,
 *       不是一个数字人自己发明的地址。</li>
 *   <li><b>写的是那些 action。</b> 建/完成/取消各走 {@code reminder.*}, 身份是
 *       {@code HUMAN(userId)}(提醒的主人是人, 应用会核对 URI 里的 ownerId), 且先
 *       {@code ensureSession}(R9 之前是 {@code ensureInstalled})。</li>
 *   <li><b>翻译是双向且只有一处。</b> {@code dueAt ↔ remindAt}、{@code note ↔ content}、
 *       {@code PENDING/DISPATCHED/DONE/CANCELLED ↔ pending/done/cancelled}。前端那一行
 *       {@code r.status === 'done'} 依赖的就是最后这一条。</li>
 * </ol>
 *
 * <p>下面那个 {@link FakeReminderApp} 不是"返回固定 JSON 的桩", 而是一个真能存取的小应用 ——
 * 因为"翻译对不对"这件事只有在对面真的存下了东西之后才看得出来。
 */
class ReminderServiceTest {

    private static final String USER = "user-1";
    private static final String COMPANION = "companion-1";

    private final FakeReminderApp app = new FakeReminderApp();
    private final ReminderService service = new ReminderService(app, new ObjectMapper());

    // ─────────────────────────── 读 ───────────────────────────

    @Test
    void listReadsTheUsersInboxThroughThePort() {
        app.store(USER, item("r1", "交房租", "2026-09-12T15:00", "PENDING", COMPANION));

        List<Reminder> all = service.list(USER, null);

        assertEquals(List.of("reminder://owner/user-1"), app.reads,
                "读的必须是 manifest 声明的那个收件箱 URI");
        assertEquals(1, all.size());
        Reminder r = all.get(0);
        assertEquals("r1", r.getId());
        assertEquals("交房租", r.getTitle());
        assertEquals(LocalDateTime.of(2026, 9, 12, 15, 0), r.getRemindAt(), "dueAt → remindAt");
        assertEquals("pending", r.getStatus());
        assertEquals(COMPANION, r.getCompanionId());
    }

    @Test
    void anEmptyInboxIsAnEmptyList() {
        app.store(USER);   // 空的收件箱

        assertEquals(List.of(), service.list(USER, null));
    }

    /** 资源根本不存在(应用还没为这个人建过任何东西)不是错误, 是"没有提醒"。 */
    @Test
    void anInboxThatDoesNotExistYetIsAlsoEmpty() {
        assertEquals(List.of(), service.list(USER, null));
    }

    @Test
    void listWithACompanionOnlyReturnsThatCompanionsReminders() {
        app.store(USER,
                item("r1", "交房租", "2026-09-12T15:00", "PENDING", COMPANION),
                item("r2", "她的生日", "2026-10-01T08:00", "PENDING", "someone-else"));

        List<Reminder> mine = service.list(USER, COMPANION);

        assertEquals(1, mine.size());
        assertEquals("交房租", mine.get(0).getTitle());
        assertEquals(2, service.list(USER, null).size(), "不给 companionId 时是全部");
    }

    /**
     * 应用的四个状态折算成数字人的三个词。
     *
     * <p>{@code DISPATCHED → done} 是这里唯一一处"不直译": 它说的是"这条提醒已经响过了",
     * 而旧实现里响过之后正是被标成 {@code done} 的。前端因此不需要知道应用多了个状态。
     */
    @Test
    void applicationStatusesAreTranslatedIntoTheVocabularyTheFrontendKnows() {
        assertEquals("pending", ReminderService.dhStatus("PENDING"));
        assertEquals("done", ReminderService.dhStatus("DISPATCHED"), "响过了 —— 旧模型里这就是 done");
        assertEquals("done", ReminderService.dhStatus("DONE"));
        assertEquals("cancelled", ReminderService.dhStatus("CANCELLED"));
        assertEquals("pending", ReminderService.dhStatus(null));
    }

    // ─────────────────────────── 写 ───────────────────────────

    @Test
    void createGoesThroughTheReminderCreateAction() {
        service.create(USER, COMPANION, "user_set", "交房租", "这个月的",
                LocalDateTime.of(2026, 9, 12, 15, 0));

        assertEquals(List.of(ReminderService.APP_ID), app.sessionsOpened,
                "用的前提是这个人在这应用里有一个会话 —— 而开这件事是幂等的, 每次用之前保证一下");
        ActionRequest request = app.requests.get(0);
        assertEquals("reminder.create", request.action());
        assertEquals("reminder://owner/user-1", request.target());
        assertEquals("交房租", request.input().path("title").asText());
        assertEquals("2026-09-12T15:00", request.input().path("dueAt").asText(),
                "时间要与 manifest 承诺的形状一致(秒为 0 时省略秒)");
        assertEquals("这个月的", request.input().path("note").asText(), "content → note");
        assertEquals("user_set", request.input().path("type").asText());
        assertEquals(COMPANION, request.input().path("companionId").asText());
    }

    /**
     * 身份显式写成 {@code HUMAN(userId)} —— 而不是数字人自己的 companionId。
     *
     * <p>应用的归属规则是"URI 里的 ownerId 必须等于调用方的 principalId"。提醒属于<em>人</em>,
     * 数字人是替这个人记事, 所以这里必须是人的身份。写成 AGENT(companionId) 的话, 调用会落进
     * "别人的收件箱"而被拒 —— 那是一个很难从报错里看出来的错误。
     */
    @Test
    void theCallerIsTheHumanWhoOwnsTheInbox() {
        service.create(USER, COMPANION, "user_set", "交房租", null,
                LocalDateTime.of(2026, 9, 12, 15, 0));

        InvocationContext ctx = app.contexts.get(0);
        assertEquals(PrincipalType.HUMAN, ctx.principalType());
        assertEquals(USER, ctx.principalId());
    }

    /**
     * 两次调用带两个不同的 correlationId。
     *
     * <p>进程内调用的幂等键是从 {@code correlationId + target} 派生的, 而提醒的 target 永远是
     * 同一个收件箱。若共用一个 correlationId, 在同一个收件箱里连建两条提醒会撞成同一个键,
     * 第二条被当成第一条的重放 —— 用户说两遍"提醒我喝水", 只会得到一条。
     */
    @Test
    void eachCallCarriesItsOwnCorrelationId() {
        service.create(USER, COMPANION, "user_set", "喝水", null, LocalDateTime.of(2026, 9, 12, 15, 0));
        service.create(USER, COMPANION, "user_set", "喝水", null, LocalDateTime.of(2026, 9, 13, 15, 0));

        assertNotEquals(app.contexts.get(0).correlationId(), app.contexts.get(1).correlationId());
        assertEquals(2, app.requests.size());
    }

    @Test
    void createReturnsTheFullReminderNotJustTheAckFields() {
        Reminder created = service.create(USER, COMPANION, "user_set", "交房租", "这个月的",
                LocalDateTime.of(2026, 9, 12, 15, 0));

        assertEquals("交房租", created.getTitle());
        assertEquals("这个月的", created.getContent(), "note → content");
        assertEquals(COMPANION, created.getCompanionId());
        assertEquals("pending", created.getStatus());
        assertEquals(USER, created.getUserId());
        assertEquals(1, app.stored(USER).size(), "应用那边确实多了一条");
    }

    @Test
    void markDoneCallsReminderComplete() {
        app.store(USER, item("r1", "交房租", "2026-09-12T15:00", "PENDING", COMPANION));

        Reminder done = service.markDone(USER, "r1");

        assertEquals("reminder.complete", app.requests.get(0).action());
        assertEquals("r1", app.requests.get(0).input().path("reminderId").asText());
        assertEquals("done", done.getStatus());
    }

    @Test
    void deleteCallsReminderCancel() {
        app.store(USER, item("r1", "交房租", "2026-09-12T15:00", "PENDING", COMPANION));

        service.delete(USER, "r1");

        assertEquals("reminder.cancel", app.requests.get(0).action());
        assertEquals("cancelled", service.list(USER, null).get(0).getStatus());
    }

    // ─────────────────────────── 失败 ───────────────────────────

    @Test
    void aReminderWithoutATimeIsRejectedBeforeAnythingIsCalled() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER, COMPANION, "user_set", "交房租", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals(List.of(), app.requests, "一个连时间都没有的提醒不该惊动应用");
    }

    /** 应用说不行 → 数字人这边是一条带原错误码的异常, 而不是一个空对象。 */
    @Test
    void anApplicationRefusalBecomesAnExceptionCarryingItsCode() {
        app.refuse(ActionStatus.DENIED, "NOT_RESOURCE_OWNER", "提醒属于 x 的主人, 不是你");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.markDone(USER, "r1"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        assertEquals("NOT_RESOURCE_OWNER", e.getHint(), "应用给的错误码要原样带出来, 否则只能看日志猜");
    }

    @Test
    void aMissingReminderIsANotFound() {
        app.refuse(ActionStatus.NOT_FOUND, "REMINDER_NOT_FOUND", "没有这条提醒");

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.delete(USER, "r1")).getStatus());
    }

    @Test
    void aSessionFailureIsReportedAsUnavailableRatherThanSwallowed() {
        app.sessionFails = true;

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER, COMPANION, "user_set", "交房租", null,
                        LocalDateTime.of(2026, 9, 12, 15, 0)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(List.of(), app.requests);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static ObjectNode item(String id, String title, String dueAt, String status, String companionId) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("id", id);
        node.put("type", "user_set");
        node.put("title", title);
        node.putNull("note");
        node.put("dueAt", dueAt);
        node.put("status", status);
        node.put("companionId", companionId);
        node.put("createdAt", "2026-09-01T10:00");
        return node;
    }

    /**
     * 一个<em>真的会存取</em>的提醒应用替身。
     *
     * <p>它复刻的是应用对外承诺的那几条: 收件箱是 {@code reminder://owner/{ownerId}},
     * 创建往里面加一条 PENDING, 完成/取消按 id 改状态, 归属按 principalId 核对。
     * 它刻意不实现生日去重、风险带这些应用侧规则 —— 那些由 application-platform 自己的用例钉住,
     * 这里要钉的是<em>数字人这一侧的翻译与调用形状</em>。
     */
    private static final class FakeReminderApp implements ApplicationRuntimePort {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        final List<String> reads = new ArrayList<>();
        final List<String> sessionsOpened = new ArrayList<>();
        final List<ActionRequest> requests = new ArrayList<>();
        final List<InvocationContext> contexts = new ArrayList<>();

        private final Map<String, ArrayNode> inboxes = new HashMap<>();
        private ActionResponse refusal;
        private boolean sessionFails;

        void store(String userId, ObjectNode... items) {
            ArrayNode array = MAPPER.createArrayNode();
            for (ObjectNode item : items) {
                array.add(item);
            }
            inboxes.put(inboxOf(userId), array);
        }

        ArrayNode stored(String userId) {
            return inboxes.getOrDefault(inboxOf(userId), MAPPER.createArrayNode());
        }

        void refuse(ActionStatus status, String code, String message) {
            this.refusal = ActionResponse.failure(status, code, message);
        }

        @Override
        public List<CapabilityView> capabilities() {
            return List.of();
        }

        @Override
        public List<ApplicationView> applicationsFor(String capabilityId) {
            return List.of();
        }

        @Override
        public List<ActionSpec> actionsOf(String applicationId) {
            return List.of();
        }

        @Override
        public String ensureSession(String applicationId, InvocationContext ctx) {
            if (sessionFails) {
                throw new IllegalStateException("应用没有已发布版本");
            }
            sessionsOpened.add(applicationId);
            // 幂等: 同一个 (应用, 主体) 永远拿到同一个会话 id —— 这正是真实实现的性质,
            // 而为每条提醒编一个新 id 会让"会话解析第 4 档"在这里测不出任何东西。
            return "session-" + applicationId + "-" + (ctx == null ? "?" : ctx.principalId());
        }

        /**
         * 提醒收件箱<b>不挂会话</b> —— 见 {@code ResourceView.sessionId} 一直为 null, 以及
         * 会话解析的第 4 档为什么必须存在。所以这里如实回一个空列表, 而不是编一场出来:
         * 编出来的话, {@code SessionResolver} 的"参与中"那一条策略在这里就会显得能用,
         * 而它对这个应用其实毫无意义。
         */
        @Override
        public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) {
            return List.of();
        }

        @Override
        public String joinByInvitation(String token, InvocationContext ctx) {
            throw new UnsupportedOperationException("提醒应用不发邀请");
        }

        @Override
        public void joinSession(String sessionId, InvocationContext ctx) {
            throw new UnsupportedOperationException("提醒应用没有会话模型");
        }

        @Override
        public void leaveSession(String sessionId, InvocationContext ctx) {
            throw new UnsupportedOperationException("提醒应用没有会话模型");
        }

        @Override
        public Optional<ResourceView> read(String resourceUri) {
            reads.add(resourceUri);
            ArrayNode items = inboxes.get(resourceUri);
            if (items == null) {
                return Optional.empty();
            }
            return Optional.of(new ResourceView(resourceUri, "reminder.inbox",
                    ReminderService.APP_ID, null, state(resourceUri, items), items.size(),
                    Instant.now(), null));
        }

        @Override
        public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
            return List.of();
        }

        @Override
        public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
            requests.add(request);
            contexts.add(ctx);
            if (refusal != null) {
                return refusal;
            }
            String owner = ownerOf(request.target());
            ArrayNode items = inboxes.computeIfAbsent(request.target(), k -> MAPPER.createArrayNode());
            if (owner == null || !owner.equals(ctx.principalId())) {
                return ActionResponse.failure(ActionStatus.DENIED, "NOT_RESOURCE_OWNER", "不是你");
            }
            switch (request.action()) {
                case "reminder.create" -> {
                    ObjectNode created = create(items, request);
                    return ActionResponse.success(ack(created), view(request.target(), items));
                }
                case "reminder.complete" -> {
                    return close(items, request, "DONE");
                }
                case "reminder.cancel" -> {
                    return close(items, request, "CANCELLED");
                }
                default -> {
                    return ActionResponse.failure(ActionStatus.NOT_FOUND, "UNKNOWN_ACTION", request.action());
                }
            }
        }

        private ActionResponse close(ArrayNode items, ActionRequest request, String status) {
            String id = request.input().path("reminderId").asText();
            for (JsonNode item : items) {
                if (id.equals(item.path("id").asText())) {
                    ((ObjectNode) item).put("status", status);
                    return ActionResponse.success(ack((ObjectNode) item), view(request.target(), items));
                }
            }
            return ActionResponse.failure(ActionStatus.NOT_FOUND, "REMINDER_NOT_FOUND", "没有这条提醒");
        }

        private ObjectNode create(ArrayNode items, ActionRequest request) {
            ObjectNode created = MAPPER.createObjectNode();
            created.put("id", UUID.randomUUID().toString());
            created.put("type", request.input().path("type").asText("user_set"));
            created.put("title", request.input().path("title").asText());
            created.put("note", request.input().hasNonNull("note") ? request.input().path("note").asText() : null);
            created.put("dueAt", request.input().path("dueAt").asText());
            created.put("status", "PENDING");
            created.put("companionId",
                    request.input().hasNonNull("companionId") ? request.input().path("companionId").asText() : null);
            created.put("createdAt", "2026-09-12T06:00");
            items.add(created);
            return created;
        }

        private static ObjectNode ack(ObjectNode item) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", item.path("id").asText());
            node.put("type", item.path("type").asText());
            node.put("title", item.path("title").asText());
            node.put("dueAt", item.path("dueAt").asText());
            node.put("status", item.path("status").asText());
            return node;
        }

        private ResourceView view(String uri, ArrayNode items) {
            return new ResourceView(uri, "reminder.inbox", ReminderService.APP_ID, null,
                    state(uri, items), items.size(), Instant.now(), null);
        }

        private static ObjectNode state(String uri, ArrayNode items) {
            ObjectNode state = MAPPER.createObjectNode();
            state.put("ownerId", ownerOf(uri));
            state.put("total", items.size());
            state.set("items", items);
            return state;
        }

        private static String inboxOf(String userId) {
            return "reminder://owner/" + userId;
        }

        private static String ownerOf(String uri) {
            String prefix = "reminder://owner/";
            return uri != null && uri.startsWith(prefix) ? uri.substring(prefix.length()) : null;
        }
    }
}
