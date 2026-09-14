package com.luxera.companion.digitalhuman.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §19 PromptLayerCache 测试: 稳定层内容固定 → 命中缓存不重复渲染。
 */
class PromptLayerCacheTest {

    private final PromptLayerCache cache = new PromptLayerCache();

    @Test
    void sameStableLayersHitCache() {
        List<String> stable = List.of("你是小满,一个真实的人。", "人格: 温柔体贴。");
        List<String> semi = List.of("关系: 熟悉。", "生活: 周末休闲。");

        String first = cache.stableLayers(stable, semi);
        String second = cache.stableLayers(stable, semi);

        assertEquals(first, second, "相同稳定层渲染结果一致");
        assertEquals(1, cache.size(), "稳定层只缓存一次");
        assertTrue(cache.hits() >= 1, "第二次应命中缓存");
        assertTrue(cache.misses() >= 1, "第一次未命中");
        assertEquals(0.5, cache.hitRate(), 0.001);
    }

    @Test
    void changedStableContentMissesCache() {
        List<String> stableA = List.of("你是小满,一个真实的人。");
        List<String> stableB = List.of("你是小满,一个成熟的人。");

        cache.stableLayers(stableA, List.of());
        String second = cache.stableLayers(stableB, List.of());

        assertNotEquals(cache.stableLayers(stableA, List.of()), second, "人格变化 → 缓存自然失效");
        assertEquals(2, cache.size(), "不同稳定层分别缓存");
    }

    @Test
    void dynamicContentNeverCached() {
        List<String> stable = List.of("稳定层");
        cache.stableLayers(stable, List.of());
        // 动态内容不在缓存键内: 同一稳定层 + 不同动态 → 命中同一缓存
        String cached = cache.stableLayers(stable, List.of());
        assertEquals(cache.stableLayers(stable, List.of()), cached);
    }

    @Test
    void clearResetsStats() {
        cache.stableLayers(List.of("a"), List.of());
        cache.clear();
        assertEquals(0, cache.size());
    }
}
