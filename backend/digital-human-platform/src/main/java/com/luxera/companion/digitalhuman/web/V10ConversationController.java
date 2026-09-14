package com.luxera.companion.digitalhuman.web;

import com.luxera.companion.digitalhuman.conversation.PromptLayerCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 对话管道诊断端点(V10 §19 Prompt Cache):
 * GET /api/v10/conversation/cache-stats → 稳定层缓存命中率/大小。
 */
@RestController
@RequestMapping("/api/v10")
public class V10ConversationController {

    private final PromptLayerCache promptLayerCache;

    public V10ConversationController(PromptLayerCache promptLayerCache) {
        this.promptLayerCache = promptLayerCache;
    }

    @GetMapping("/conversation/cache-stats")
    public Map<String, Object> cacheStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stableLayerCacheEntries", promptLayerCache.size());
        result.put("hits", promptLayerCache.hits());
        result.put("misses", promptLayerCache.misses());
        result.put("hitRate", Math.round(promptLayerCache.hitRate() * 100) / 100.0);
        result.put("principle", "Stable Prefix 内容和顺序固定, 动态内容全部追加在末尾(V10 §19)");
        return result;
    }
}
