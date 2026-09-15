package com.luxera.agentopenapi.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API Key 生成与哈希 —— 一个 key 一生只在这里明文存在一次。
 *
 * <p>格式 {@code sap_<64 hex>}(32 字节 SecureRandom)。验证 = sha256(收到的 key)
 * 与库里的 hash 常量时间比对 —— API key 自带 256 位随机熵, 与密码不同不需要
 * 抗暴力破解的慢哈希(BCrypt 一次 ~100ms, 三方高频调用下纯浪费)。
 */
public final class ApiKeyGenerator {

    public static final String KEY_PREFIX = "sap_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public record GeneratedKey(String plaintext, String hash, String prefix) {}

    public static GeneratedKey generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        String plaintext = KEY_PREFIX + hex;
        return new GeneratedKey(plaintext, sha256Hex(plaintext), plaintext.substring(0, 10));
    }

    public static String sha256Hex(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    /** 常量时间比对 —— 与仓 1 RemoteSignature/InternalSignature 同哲学。 */
    public static boolean matches(String expectedHash, String receivedKey) {
        if (expectedHash == null || receivedKey == null) return false;
        String actual = sha256Hex(receivedKey);
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
