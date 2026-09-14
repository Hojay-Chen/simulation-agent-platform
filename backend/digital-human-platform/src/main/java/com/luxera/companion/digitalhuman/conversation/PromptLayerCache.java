package com.luxera.companion.digitalhuman.conversation;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V10 §19 PromptLayerCache: 分层 Prompt 缓存。
 *
 * 规则:
 * 1. Stable Prefix 内容和顺序固定(同一 hash 的稳定层只渲染一次);
 * 2. Personality 不允许每次重新生成(它属于稳定层);
 * 3. 动态内容全部追加在末尾, 绝不进入缓存;
 * 4. Session 内尽量复用 Context Snapshot(稳定层 hash 命中)。
 *
 * 稳定层内容变化(人格版本/关系阶段) → hash 变化 → 自然失效, 无需手动清缓存。
 */
@Component
public class PromptLayerCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** 渲染并缓存稳定层(Stable Prefix + Semi-Stable); 命中时直接返回缓存 */
    public String stableLayers(List<String> stablePrefix, List<String> semiStable) {
        String key = hash(stablePrefix, semiStable);
        String cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        String rendered = render(stablePrefix, semiStable);
        cache.put(key, rendered);
        misses.incrementAndGet();
        return rendered;
    }

    /** 缓存命中率(诊断用) */
    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        return (h + m) == 0 ? 0 : (double) h / (h + m);
    }

    /** 缓存条目数(诊断用) */
    public int size() {
        return cache.size();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    /** 清空缓存(人格版本升级等场景由调用方决定) */
    public void clear() {
        cache.clear();
    }

    private static String render(List<String> stable, List<String> semi) {
        StringBuilder sb = new StringBuilder();
        if (stable != null) {
            for (String line : stable) {
                if (line != null && !line.isBlank()) {
                    sb.append(line).append('\n');
                }
            }
        }
        if (semi != null && !semi.isEmpty()) {
            sb.append("\n—— 当前关系与生活状态 ——\n");
            for (String line : semi) {
                if (line != null && !line.isBlank()) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String hash(List<String> stable, List<String> semi) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            if (stable != null) stable.forEach(sb::append);
            sb.append("|||");
            if (semi != null) semi.forEach(sb::append);
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            return "no-sha-available";
        }
    }
}
