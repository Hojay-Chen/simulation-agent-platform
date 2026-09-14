package com.luxera.companion.memory;

import com.luxera.companion.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private final MemoryRepository repo;
    private final EmbeddingProvider embeddingProvider;
    private final MemoryAssociationService associationService;
    private final com.luxera.companion.openloop.OpenLoopService openLoopService;
    private final AppProperties props;

    public MemoryService(MemoryRepository repo, EmbeddingProvider embeddingProvider,
                         MemoryAssociationService associationService,
                         com.luxera.companion.openloop.OpenLoopService openLoopService,
                         AppProperties props) {
        this.repo = repo;
        this.embeddingProvider = embeddingProvider;
        this.associationService = associationService;
        this.openLoopService = openLoopService;
        this.props = props;
    }

    /** 保存记忆,内容重复时强化而非重复插入 */
    @Transactional
    public Memory save(Memory m) {
        if (m.getId() == null) m.setId(UUID.randomUUID().toString());
        if (m.getOccurredAt() == null) m.setOccurredAt(m.getCreatedAt() != null ? m.getCreatedAt() : LocalDateTime.now());
        Optional<Memory> existing = repo.findTopByUserIdAndCompanionIdAndContentAndStatusOrderByCreatedAtDesc(
                m.getUserId(), m.getCompanionId(), m.getContent(), "active");
        if (existing.isPresent()) {
            Memory e = existing.get();
            e.setImportance(Math.max(e.getImportance(), m.getImportance()));
            e.setConfidence(Math.max(e.getConfidence(), m.getConfidence()));
            e.setEmotionalWeight(Math.max(e.getEmotionalWeight(), m.getEmotionalWeight()));
            e.setRetrievalCount(e.getRetrievalCount() + 1);   // 反复提及 → 记忆强化
            if (m.getSummary() != null) e.setSummary(m.getSummary());
            return repo.save(e);
        }
        return repo.save(m);
    }

    @Transactional
    public void saveBatch(String userId, String companionId, String sourceType, String sourceId,
                          List<Memory> memories) {
        for (Memory m : memories) {
            m.setUserId(userId);
            m.setCompanionId(companionId);
            m.setSourceType(sourceType);
            m.setSourceId(sourceId);
            if (!StringUtils.hasText(m.getContent())) continue;
            save(m);
        }
    }

    /**
     * 检索记忆(设计文档 §12.2): 结构化候选 + 可插拔向量检索 →
     * 评分 = 检索强度 × narrative_relevance × open_loop_relevance → 强化。
     */
    @Transactional
    public List<Memory> retrieve(String userId, String companionId, String query, int limit) {
        List<Memory> candidates = new ArrayList<>();
        if (StringUtils.hasText(query)) {
            candidates.addAll(repo.searchByKeyword(userId, companionId, query));
            // §19 回退: 整段消息很少能精确命中记忆内容(LIKE 全文匹配)。
            // 关键词检索为空时, 回退到最近的记忆 —— 她仍会"想起"一些近事,
            // 让 Memory Agent 有候选可评估(而不是直接无记忆)。
            if (candidates.isEmpty()) {
                candidates.addAll(repo.search(userId, companionId, null));
            }
        } else {
            candidates.addAll(repo.search(userId, companionId, null));
        }
        // 向量检索补充(未配 embedding key 时为空)
        for (String id : embeddingProvider.searchSimilar(userId, companionId, query, 20)) {
            repo.findById(id).ifPresent(m -> {
                if (!candidates.contains(m)) candidates.add(m);
            });
        }

        // 未完成事项关键词(open_loop_relevance 因子)
        List<String> loopKeywords = openLoopService.activeLoops(companionId).stream()
                .map(l -> l.getTitle() == null ? "" : l.getTitle())
                .toList();

        LocalDateTime now = LocalDateTime.now();
        var ranked = candidates.stream()
                .map(m -> {
                    LocalDateTime base = m.getOccurredAt() != null ? m.getOccurredAt() : m.getCreatedAt();
                    int days = (int) ChronoUnit.DAYS.between(base, now);
                    double strength = m.retrievalStrength(days);
                    // narrative_relevance: 有叙事角色的记忆(如里程碑/梗)略加权
                    double narrativeFactor = m.getNarrativeRole() != null ? 1.2 : 1.0;
                    // open_loop_relevance: 内容与未了结事项相关的记忆加权(她会想起待办)
                    double loopFactor = 1.0;
                    if (!loopKeywords.isEmpty()) {
                        for (String kw : loopKeywords) {
                            if (!kw.isBlank() && m.getContent() != null && m.getContent().contains(kw)) {
                                loopFactor = 1.4;
                                break;
                            }
                        }
                    }
                    return new AbstractMap.SimpleEntry<>(m, strength * narrativeFactor * loopFactor);
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();

        double minStrength = props.getAgent().getMemoryMinStrength();
        List<Memory> top = ranked.stream()
                .filter(e -> e.getValue() >= minStrength)
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 记忆强化: 被检索到即视为"再次回忆"
        for (Memory m : top) {
            reinforce(m.getId());
        }

        // 关联记忆扩展: 沿记忆网络取回邻居(设计文档 28 节)
        if (!top.isEmpty()) {
            List<Memory> expanded = associationService.expand(top);
            if (expanded.size() > limit * 2) {
                return expanded.subList(0, limit * 2);
            }
            return expanded;
        }
        return top;
    }

    @Transactional
    public void reinforce(String memoryId) {
        repo.findById(memoryId).ifPresent(m -> {
            m.setRetrievalCount(m.getRetrievalCount() + 1);
            m.setLastRetrievedAt(LocalDateTime.now());
            repo.save(m);
        });
    }

    @Transactional(readOnly = true)
    public List<Memory> list(String userId, String companionId, String type) {
        return repo.search(userId, companionId, StringUtils.hasText(type) ? type : null);
    }

    @Transactional(readOnly = true)
    public List<Memory> search(String userId, String companionId, String q) {
        return repo.searchByKeyword(userId, companionId, q);
    }

    @Transactional
    public void forget(String userId, String companionId, String memoryId) {
        Memory m = repo.findById(memoryId)
                .filter(x -> x.getUserId().equals(userId) && x.getCompanionId().equals(companionId))
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("记忆不存在"));
        m.setStatus("forgotten");
        repo.save(m);
    }

    @Transactional
    public void clearAll(String userId, String companionId) {
        for (Memory m : repo.findByUserIdAndCompanionIdAndStatus(userId, companionId, "active")) {
            m.setStatus("forgotten");
            repo.save(m);
        }
    }

    @Transactional(readOnly = true)
    public long count(String userId, String companionId) {
        return repo.countByUserIdAndCompanionIdAndStatus(userId, companionId, "active");
    }
}
