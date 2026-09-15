package com.luxera.agentserver.internal;

import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 服务间 HMAC-SHA256 签名 —— chat-platform(8081) 与 agent-server(8091) 互相说
 * "这条请求确实是我发的, 且没被改过"。
 *
 * <p>模式与仓 1 {@code application/.../remote/RemoteSignature.java}(R14 远端应用签名)
 * 完全一致: 签 {@code <timestamp>.<body>} 而不是只签 body —— 只签 body 时, 拿到历史
 * 请求的人可以原样重放到明天。把时间戳纳入签名, 接收方就能用时间窗把重放挡掉。
 *
 * <p>与仓 1 各持一份(contract 是纯类型模块、不依赖 Spring, 这类工具不便下放),
 * 两份必须语义逐字节一致 —— 任何一边改了签名材料都要同步。
 */
public final class InternalSignature {

    public static final String HEADER_SIGNATURE = "X-Lap-Signature";
    public static final String HEADER_TIMESTAMP = "X-Lap-Timestamp";
    /** 服务自报身份: {@code chat} / {@code agent}。仅用于审计, 不参与鉴权。 */
    public static final String HEADER_SERVICE = "X-Lap-Service";
    public static final String SCHEME = "sha256=";

    /** 时钟偏差容忍窗口(秒): 签名时间戳超过这个窗就视为重放/时钟漂移, 拒绝。 */
    public static final long CLOCK_SKEW_SECONDS = 300;

    private InternalSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return SCHEME + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 校验签名 + 时间戳在时钟偏差窗内。常量时间比较。
     *
     * @param body 原始请求体（未经解析的字节 —— 序列化/转义差异会让签名失配）
     */
    public static boolean verify(String secret, String timestamp, String body, String header) {
        if (secret == null || header == null || timestamp == null || body == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            long now = java.time.Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > CLOCK_SKEW_SECONDS) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }
}
