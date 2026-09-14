package com.luxera.companion.digitalhuman.persona;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * V10 §5.8 Persona Fingerprint: 多 persona 差异化指纹。
 *
 * 为防止不同 Companion 输出趋同, 计算 8 字节指纹注入 userPrompt。
 * 隐式区分: 不直接告诉 LLM 怎么说话, 而是用 fingerprint 作为隐式标识,
 * 让 LLM 自然区分不同 persona。
 */
@Slf4j
@Component
public class PersonaFingerprint {

    /** 计算 8 字节(16 进制 16 字符)指纹 */
    public String compute(PersonaVoiceProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(profile.accent != null ? profile.accent : "");
        for (int i = 0; i < Math.min(3, profile.catchphrases.size()); i++) {
            sb.append("|").append(profile.catchphrases.get(i));
        }
        for (String hint : profile.vocabularyHints) {
            sb.append("|").append(hint);
        }
        sb.append("|").append(profile.sentenceEndingStyle != null ? profile.sentenceEndingStyle : "");
        sb.append("|").append(profile.emojiUsageRate);

        return sha256Truncate(sb.toString(), 8);
    }

    /** 注入 userPrompt 第一行: [PersonaFingerprint fp=...] */
    public String injectFingerprint(String userPrompt, String fingerprint) {
        return "[PersonaFingerprint fp=" + fingerprint + "]\n" + userPrompt;
    }

    private String sha256Truncate(String input, int bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // fallback
            int hash = input.hashCode();
            return String.format("%08x", Math.abs(hash)).substring(0, bytes * 2);
        }
    }
}