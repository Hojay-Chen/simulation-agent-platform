package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * 仓 2 三个 HTTP 客户端适配器共用的小基座 —— 对 chat-platform 的 HMAC 签名调用。
 *
 * <p>签名模式与仓 1 R14 {@code RemoteSignature} 完全一致
 * （{@code sha256=HMAC(key, timestamp.body)}，头 {@code X-Lap-Timestamp} /
 * {@code X-Lap-Signature}），加 {@code X-Lap-Service: agent} 自报身份。
 *
 * <p>用 JDK {@code HttpURLConnection} 而非 RestTemplate/WebClient —— 与仓 1
 * {@code RemoteApplicationInvoker} 同一先例，不引新依赖。
 */
public abstract class HttpClientSupport {

    protected final String baseUrl;
    protected final String serviceKey;
    protected final ObjectMapper objectMapper;
    private final int timeoutMillis;

    protected HttpClientSupport(String baseUrl, String serviceKey, ObjectMapper objectMapper,
                                int timeoutMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceKey = serviceKey;
        this.objectMapper = objectMapper;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 读路径：GET。chat 平台缺席 → {@code Optional.empty()}（"世界暂时是空的"，
     * 与 G2 占位语义一致；fire-and-forget 下这不是失败）。
     */
    protected <T> Optional<T> get(String path, Class<T> type) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET", null);
            int code = conn.getResponseCode();
            if (code == 404 || code >= 500) return Optional.empty();
            if (code >= 300) return Optional.empty();
            try (InputStream in = conn.getInputStream()) {
                return Optional.ofNullable(objectMapper.readValue(in, type));
            }
        } catch (Exception e) {
            log().warn("[http] GET {} 失败: {}", path, e.toString());
            return Optional.empty();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 读路径：GET 列表（元素类型擦除，按 {@code objectMapper} 的类型工厂处理）。 */
    protected <T> java.util.List<T> getList(String path,
                                            com.fasterxml.jackson.databind.JavaType listType) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET", null);
            int code = conn.getResponseCode();
            if (code != 200) return java.util.List.of();
            try (InputStream in = conn.getInputStream()) {
                return objectMapper.readValue(in, listType);
            }
        } catch (Exception e) {
            log().warn("[http] GET list {} 失败: {}", path, e.toString());
            return java.util.List.of();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 写路径：POST。chat 平台缺席 → 抛 {@link IllegalStateException}
     * （沉默地假装写成功比诚实地说做不到糟糕得多 —— G2 占位哲学）。
     *
     * <p>读路径(empty/Optional)与写路径(抛错)的差异是有意的: 读可以等价于"世界是空的",
     * 写没有等价物。
     */
    protected <T> T post(String path, Object body, Class<T> responseType) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST", body == null ? "" : objectMapper.writeValueAsString(body));
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code >= 400) {
                throw new IllegalStateException("chat 平台 " + code + ": " + readError(conn));
            }
            if (responseType == null) return null;
            try (InputStream in = conn.getInputStream()) {
                return objectMapper.readValue(in, responseType);
            }
        } catch (IOException e) {
            throw new IllegalStateException("chat 平台调用失败(" + path + "): "
                    + e.getClass().getSimpleName(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 写路径的 PATCH（updatePerception 用）。 */
    protected <T> T patch(String path, Object body, Class<T> responseType) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST", body == null ? "" : objectMapper.writeValueAsString(body));
            // HttpURLConnection 不支持 PATCH; 服务端按 POST 接。
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            int code = conn.getResponseCode();
            if (code >= 400) throw new IllegalStateException("chat 平台 " + code + ": " + readError(conn));
            if (responseType == null) return null;
            try (InputStream in = conn.getInputStream()) {
                return objectMapper.readValue(in, responseType);
            }
        } catch (IOException e) {
            throw new IllegalStateException("chat 平台调用失败(" + path + "): "
                    + e.getClass().getSimpleName(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** fire-and-forget 写：任何失败都吞掉只 log（onUserMessage/markRead/deliveryStatus 等）。
     * DH 平台缺席时聊天平台照常运转, outbox 兜底链路仍在。 */
    protected void postFireAndForget(String path, Object body) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST", body == null ? "" : objectMapper.writeValueAsString(body));
            conn.getResponseCode();  // 触发连接, 不关心结果
        } catch (Exception e) {
            log().debug("[http] fire-and-forget {} 失败(吞掉): {}", path, e.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String payload = body == null ? "" : body;
        conn.setRequestProperty(com.luxera.agentserver.internal.InternalSignature.HEADER_TIMESTAMP, timestamp);
        conn.setRequestProperty(com.luxera.agentserver.internal.InternalSignature.HEADER_SIGNATURE,
                com.luxera.agentserver.internal.InternalSignature.sign(serviceKey, timestamp, payload));
        conn.setRequestProperty(com.luxera.agentserver.internal.InternalSignature.HEADER_SERVICE, "agent");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private String readError(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return "(no body)";
            return new String(err.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unreadable)";
        }
    }

    protected abstract org.slf4j.Logger log();
}
