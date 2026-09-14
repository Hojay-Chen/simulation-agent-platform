package com.luxera.companion.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * V9 §7 CompiledContext: Cache-aware 分层上下文产物。
 *
 * L0 Stable Prefix   — 身份/人格/行为准则(版本化, 变化才失效 → 命中 provider prefix cache)
 * L1 Session Prefix  — 关系/用户模型/会话状态(session_revision 增量)
 * L2 Dynamic Context — 当前活动/情绪/想法/计划/记忆(短结构, 尽量只发变化字段)
 * L3 Current Turn    — 本回合行为意图/表达提示/用户输入(每轮变化, 不缓存)
 *
 * hashes: stableHash(L0)/sessionHash(L1)/dynamicHash(L2) 用于 LLM Call 观测
 * 与 cache 命中推断(相同 agent + 相同 stableHash 的连续调用 → prefix cache 命中)。
 */
public record CompiledContext(
        String l0,
        String l1,
        String l2,
        String l3,
        String stableHash,
        String sessionHash,
        String dynamicHash) {

    /** 完整 system prompt(稳定在前, 动态在后) */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        append(sb, l0);
        append(sb, l1);
        append(sb, l2);
        append(sb, l3);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            sb.append(part);
            if (!part.endsWith("\n")) sb.append("\n");
        }
    }

    public static String hash(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
