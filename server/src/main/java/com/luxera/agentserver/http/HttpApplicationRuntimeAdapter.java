package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ApplicationRuntimePort} 的 HTTP 适配器 —— 仓 2 数字人调用应用平台（游戏、提醒等）
 * 的唯一通道。
 *
 * <p>语义同 {@link HttpChatWorldAdapter}: 读缺席 → 空（"没有应用可玩"等价于世界暂时空）；
 * 写/变更（execute/ensureSession/join/leave）缺席 → 抛 {@link IllegalStateException}。
 * chat 侧应用平台自己的业务失败经 {@code ActionResponse} 原样带回来 —— 平台级错误
 * （连不上/超时/5xx）与业务级错误（DENIED/STATE_CONFLICT）在仓 2 侧必须能区分：
 * 前者意味着"应用平台缺席"，后者意味着"应用平台在, 但这一步它不让你这么走"。
 *
 * <p>{@link InvocationContext} 整个序列化成请求体的一部分 —— 它有六个字段
 * （principalType/principalId/companionId/userId/sessionId/correlationId）, 数字人
 * 调用时必须显式声明 {@code AGENT}, 挑两个字段传会让代理静默降级成人类。
 */
@Slf4j
public class HttpApplicationRuntimeAdapter extends HttpClientSupport
        implements ApplicationRuntimePort {

    public HttpApplicationRuntimeAdapter(String baseUrl, String serviceKey,
                                         ObjectMapper objectMapper, int timeoutMillis) {
        super(baseUrl, serviceKey, objectMapper, timeoutMillis);
    }

    @Override protected org.slf4j.Logger log() { return log; }

    // ── 读 ─────────────────────────────────────────────────────────────────

    @Override
    public List<CapabilityView> capabilities() {
        return getList("/internal/runtime/capabilities", listOf(CapabilityView.class));
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        return getList("/internal/runtime/capabilities/" + capabilityId + "/applications",
                listOf(ApplicationView.class));
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return getList("/internal/runtime/applications/" + applicationId + "/actions",
                listOf(ActionSpec.class));
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        return get("/internal/runtime/resources?uri=" + enc(resourceUri), ResourceView.class);
    }

    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        return getList("/internal/runtime/pending-actions?uri=" + enc(resourceUri),
                listOf(ActionSpec.class));
    }

    @Override
    public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) {
        return getList("/internal/runtime/applications/" + applicationId + "/sessions",
                listOf(SessionRef.class));
    }

    // ── 写/变更 ────────────────────────────────────────────────────────────

    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        return post("/internal/runtime/actions",
                Map.of("request", request, "context", ctx), ActionResponse.class);
    }

    @Override
    public String ensureSession(String applicationId, InvocationContext ctx) {
        Map<?, ?> resp = post("/internal/runtime/sessions/ensure",
                Map.of("applicationId", applicationId, "context", ctx), Map.class);
        return resp == null ? null : String.valueOf(resp.get("sessionId"));
    }

    @Override
    public String joinByInvitation(String token, InvocationContext ctx) {
        Map<?, ?> resp = post("/internal/runtime/invitations/join",
                Map.of("token", token, "context", ctx), Map.class);
        return resp == null ? null : String.valueOf(resp.get("sessionId"));
    }

    @Override
    public void joinSession(String sessionId, InvocationContext ctx) {
        post("/internal/runtime/sessions/" + sessionId + "/join",
                Map.of("context", ctx), Void.class);
    }

    @Override
    public void leaveSession(String sessionId, InvocationContext ctx) {
        post("/internal/runtime/sessions/" + sessionId + "/leave",
                Map.of("context", ctx), Void.class);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private com.fasterxml.jackson.databind.JavaType listOf(Class<?> elementType) {
        return TypeFactory.defaultInstance().constructCollectionType(List.class, elementType);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
