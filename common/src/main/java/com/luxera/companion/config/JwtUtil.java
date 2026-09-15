package com.luxera.companion.config;

import com.luxera.companion.contracts.application.PrincipalType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p><b>LAP v1 的 {@code ptype} claim(本次重构唯一一处触及 platform-kernel 的改动)。</b>
 * 令牌里多带一个 {@code ptype}, 让真人客户端签发的令牌与 Agent 的令牌在<em>认证层</em>就能
 * 区分, 而不是让每个服务各自猜。缺 {@code ptype} 的历史令牌回落 {@link PrincipalType#HUMAN}
 * —— 这是<b>向后兼容</b>, 不是通用默认值: Agent 侧的 {@code InvocationContext} 必须显式声明
 * {@code AGENT}, 由 {@code InternalPrincipalResolver} 强制(未声明直接抛异常)。
 *
 * <p>改动刻意只有这一个类: 不动 {@code JwtAuthenticationFilter}, 也就不会改变任何既有令牌
 * 的 {@code ROLE_USER} 语义 —— 认证栈的行为对现有 294 个测试逐字节保持。
 */
@Component
public class JwtUtil {

    /** LAP v1: principal 类型的 claim 名。 */
    public static final String CLAIM_PRINCIPAL_TYPE = "ptype";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    private SecretKey key;

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String username) {
        return generateToken(userId, username, PrincipalType.HUMAN);
    }

    /** LAP v1: 带 principal 类型的令牌。 */
    public String generateToken(String userId, String username, PrincipalType principalType) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(userId)
                .claim("username", username)
                .claim(CLAIM_PRINCIPAL_TYPE, (principalType == null ? PrincipalType.HUMAN : principalType).name())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 令牌声明的 principal 类型。缺 claim / 值无法识别时回落 {@link PrincipalType#HUMAN} ——
     * 前者是历史令牌, 后者是别人手改过的令牌, 两者都不该让请求变成 500。
     */
    public PrincipalType getPrincipalType(String token) {
        String raw = parseClaims(token).get(CLAIM_PRINCIPAL_TYPE, String.class);
        if (raw == null || raw.isBlank()) {
            return PrincipalType.HUMAN;
        }
        try {
            return PrincipalType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PrincipalType.HUMAN;
        }
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token).getBody();
    }
}
