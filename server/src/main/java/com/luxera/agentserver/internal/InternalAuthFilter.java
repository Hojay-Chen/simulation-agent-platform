package com.luxera.agentserver.internal;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * {@code /internal/**} 的服务间鉴权 —— 只认 HMAC 签名, 不认 JWT。
 *
 * <p>为什么不用 JWT: JWT 表达的是"哪个用户/哪个 principal", 而服务间调用要表达的是
 * "这个请求确实来自 chat-platform 进程"。一个被偷的 JWT 没法冒充服务（它不知道该
 * 算哪个签名）, 一个被偷的 internal-service-key 才能 —— 而这把密钥从来不出仓、不进
 * manifest、不落到客户端。
 *
 * <p>只拦 {@code /internal/**}: 登录、SSE、WS、openapi 的对外面走原有 JWT/密钥体系,
 * 本过滤器对它们透明。
 *
 * <p>校验失败一律 401 —— 不区分"没签名"/"签名错"/"时间窗过期", 对外不暴露为什么
 * (三种情况在调用方看来都该重新签字重发)。
 *
 * <p><b>顺序: 先验签, 再放行。</b>整个请求体先读完、签字过了才进 controller ——
 * 任何写路径都不会在签名不合法时执行。代价是每个 /internal 请求多一次缓冲,
 * 对内网控制面可接受。
 */
@Component
@Order(1)   // 在所有业务 controller 之前
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/internal/";

    private final String serviceKey;

    public InternalAuthFilter(org.springframework.core.env.Environment env) {
        this.serviceKey = env.getProperty("app.agent-platform.internal-service-key", "");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (serviceKey.isBlank()) {
            // 与仓 1 McpPrincipalResolver 同哲学: 密钥没配, 内网端点完全不服务。
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "internal-service-key 未配置");
            return;
        }

        String body = readFully(request);
        String timestamp = request.getHeader(InternalSignature.HEADER_TIMESTAMP);
        String signature = request.getHeader(InternalSignature.HEADER_SIGNATURE);
        if (!InternalSignature.verify(serviceKey, timestamp, body, signature)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid internal signature");
            return;
        }

        chain.doFilter(new ReReadableRequest(request, body), response);
    }

    private static String readFully(HttpServletRequest request) throws IOException {
        try (var in = request.getInputStream()) {
            byte[] buf = in.readAllBytes();
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    /** 把已经读掉的请求体重新放回去, 让下游 controller 的 @RequestBody 还能读。 */
    private static final class ReReadableRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReReadableRequest(HttpServletRequest request, String body) {
            super(request);
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public javax.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new javax.servlet.ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(javax.servlet.ReadListener listener) { }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
