package com.luxera.agentopenapi.config;

import com.luxera.agentopenapi.client.ApiKeyGenerator;
import com.luxera.agentopenapi.client.OpenApiClientRecord;
import com.luxera.agentopenapi.client.OpenApiClientRepository;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * 对外 OpenAPI 的鉴权 —— 两把钥匙, 两个面。
 *
 * <ul>
 *   <li><b>管理面</b> {@code /api/v1/openapi/clients**} 与 {@code /api/v1/openapi/admin**}:
 *       {@code X-Admin-Key} 头, 全局一把({@code OPENAPI_ADMIN_KEY} env)。未配 → 503
 *       死端点(与仓 1 MCP "密钥没配就完全不服务"同哲学)。建客户端是"发钥匙"的动作,
 *       只能由平台管理员做 —— 一把管理钥换任意把客户端钥。{@code admin} 命名空间
 *       装的是**跨客户端**的运维动作(目前只有 agent 开关), 它放在这里而不是客户端面,
 *       理由见下面 {@code ADMIN_PREFIX} 那段。</li>
 *   <li><b>客户端面</b> {@code /api/v1/openapi/agents**}:
 *       {@code Authorization: Bearer sap_...}, 每客户端一把。命中后把
 *       {@code OpenApiClientRecord} 作为 request attribute 交给下游 ——
 *       controller 不再二次查库, 归属校验(userId=clientId)基于此做。</li>
 * </ul>
 *
 * <p>都不匹配 → 401。不区分"没带/格式错/已吊销", 对外不暴露为什么。
 * 只拦 {@code /api/v1/openapi/**}: /api/health、/docs、/v3/api-docs 对外放行
 * (spec 本身不含密钥; 三方接入第一步就是看文档)。
 */
@Component
@Order(1)
public class OpenApiAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_CLIENT = "openapi.client";
    public static final String HEADER_ADMIN_KEY = "X-Admin-Key";

    private static final String OPENAPI_PREFIX = "/api/v1/openapi/";
    private static final String CLIENTS_PREFIX = "/api/v1/openapi/clients";
    private static final String ADMIN_PREFIX = "/api/v1/openapi/admin";

    private final String adminKey;
    private final OpenApiClientRepository clients;

    public OpenApiAuthFilter(Environment env, OpenApiClientRepository clients) {
        this.adminKey = env.getProperty("app.openapi.admin-key", "");
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(OPENAPI_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // ── 管理面: clients 资源 + admin 命名空间 ──
        //
        // `/api/v1/openapi/admin/**` 是平台级运维动作(见 OpenApiAdminLifecycleController)。
        // 它与 clients 一样吃 X-Admin-Key, 理由也一样: 它做的事**跨客户端** ——
        // "把全平台所有 agent 停下来"不是任何一个客户端能对自己做的操作。若把它放回
        // 客户端面, 那要么是"持钥者能停别人的 agent"(越权), 要么是"谁也停不了全部"
        // (做不到), 两条都不对。
        //
        // 用 `equals(ADMIN_PREFIX) || startsWith(ADMIN_PREFIX + "/")` 而不是裸
        // `startsWith(ADMIN_PREFIX)`: 后者会让 `/api/v1/openapi/administrators` 也被
        // 判成管理面 —— 与前端 `faceOf()` 里"裸 startsWith 把 clients-archive 判成管理面"
        // 是同一类错, 只是这里错的方向更危险(把该拦的放进了管理面)。
        if (path.startsWith(CLIENTS_PREFIX)
                || path.equals(ADMIN_PREFIX) || path.startsWith(ADMIN_PREFIX + "/")) {
            if (adminKey.isBlank()) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json; charset=utf-8");
                response.getWriter().write("{\"error\":\"openapi admin face not configured\"}");
                return;
            }
            String given = request.getHeader(HEADER_ADMIN_KEY);
            if (given == null || !MessageDigest_isEqual(adminKey, given)) {
                reject(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // ── 客户端面: agents 及其余资源 ──
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer " + ApiKeyGenerator.KEY_PREFIX)) {
            reject(response);
            return;
        }
        String key = auth.substring("Bearer ".length());
        String hash = ApiKeyGenerator.sha256Hex(key);
        Optional<OpenApiClientRecord> client =
                clients.findByApiKeyHashAndStatus(hash, OpenApiClientRecord.STATUS_ACTIVE);
        if (client.isEmpty()) {
            reject(response);
            return;
        }
        request.setAttribute(ATTR_CLIENT, client.get());
        chain.doFilter(request, response);
    }

    /** 直接写 401 响应体 —— 不用 sendError: 在 Spring Security 链内 sendError 的
     * 状态码会被 ExceptionTranslationFilter/entry point 改写成 403, 三方便会看到
     * "鉴权失败"与"没带钥匙"两件不同的事都回 403。这里绕过改写, 401 就是 401。 */
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"error\":\"invalid or missing api key\"}");
    }

    private static boolean MessageDigest_isEqual(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
