package com.luxera.companion.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 关联记忆(设计文档 28/98 节): 记忆之间按内容重叠建立关联,
 * 检索时沿关联扩展,形成记忆网络。此服务不依赖向量库,用字符二元组重叠近似语义相似。
 */
@Slf4j
@Service
public class MemoryAssociationService {

    private final MemoryLinkRepository linkRepo;
    private final MemoryRepository memoryRepo;

    public MemoryAssociationService(MemoryLinkRepository linkRepo, MemoryRepository memoryRepo) {
        this.linkRepo = linkRepo;
        this.memoryRepo = memoryRepo;
    }

    /** 新记忆入库后调用: 与近期记忆建关联 */
    @Transactional
    public void linkNewMemory(String userId, String companionId, Memory newMemory, int maxCandidates) {
        if (newMemory.getId() == null || newMemory.getContent() == null) return;
        List<Memory> candidates = memoryRepo.search(userId, companionId, null);
        if (candidates.size() > maxCandidates) {
            candidates = candidates.subList(0, maxCandidates);
        }
        Set<String> newBigrams = bigrams(newMemory.getContent());
        if (newBigrams.isEmpty()) return;

        int linked = 0;
        for (Memory other : candidates) {
            if (other.getId().equals(newMemory.getId())) continue;
            if (other.getStatus() != null && !"active".equals(other.getStatus())) continue;
            double overlap = overlapRatio(newBigrams, bigrams(other.getContent()));
            if (overlap >= 0.3 && !hasLink(newMemory.getId(), other.getId())) {
                createLink(newMemory.getId(), other.getId(), "same_topic", overlap);
                linked++;
            }
        }
        if (linked > 0) {
            log.debug("记忆关联: 新记忆与 {} 条旧记忆建链", linked);
        }
    }

    /** 同一批抽取出的新记忆之间互相建链(如同一次对话抽出的多个"咖啡"记忆) */
    @Transactional
    public void linkBatch(List<Memory> newMemories) {
        for (int i = 0; i < newMemories.size(); i++) {
            Memory a = newMemories.get(i);
            if (a.getId() == null || a.getContent() == null) continue;
            for (int j = i + 1; j < newMemories.size(); j++) {
                Memory b = newMemories.get(j);
                if (b.getId() == null || b.getContent() == null) continue;
                if (hasLink(a.getId(), b.getId())) continue;
                double overlap = overlapRatio(bigrams(a.getContent()), bigrams(b.getContent()));
                if (overlap >= 0.3) {
                    createLink(a.getId(), b.getId(), "same_topic", overlap);
                }
            }
        }
    }

    /** 检索扩展: 沿关联取回邻居记忆 */
    @Transactional(readOnly = true)
    public List<Memory> expand(List<Memory> base) {
        if (base.isEmpty()) return base;
        List<String> baseIds = base.stream().map(Memory::getId).toList();
        Set<String> seen = new HashSet<>(baseIds);
        Map<String, Memory> byId = new LinkedHashMap<>();
        for (Memory m : base) byId.put(m.getId(), m);

        for (MemoryLink link : linkRepo.findByFromMemoryIdIn(baseIds)) {
            addNeighbor(byId, seen, link.getToMemoryId());
        }
        for (MemoryLink link : linkRepo.findByToMemoryIdIn(baseIds)) {
            addNeighbor(byId, seen, link.getFromMemoryId());
        }
        return new ArrayList<>(byId.values());
    }

    /** 记忆图谱(前端展示) */
    @Transactional(readOnly = true)
    public Map<String, Object> graph(String userId, String companionId, int limit) {
        List<Memory> nodes = memoryRepo.search(userId, companionId, null);
        if (nodes.size() > limit) nodes = nodes.subList(0, limit);
        List<String> ids = nodes.stream().map(Memory::getId).toList();
        List<MemoryLink> links = new ArrayList<>();
        links.addAll(linkRepo.findByFromMemoryIdIn(ids));
        links.addAll(linkRepo.findByToMemoryIdIn(ids));
        // 去重边
        Set<String> seen = new HashSet<>();
        List<MemoryLink> uniqueLinks = new ArrayList<>();
        for (MemoryLink l : links) {
            String a = l.getFromMemoryId();
            String b = l.getToMemoryId();
            String k = (a.compareTo(b) <= 0 ? a + "-" + b : b + "-" + a);
            if (seen.add(k)) uniqueLinks.add(l);
        }
        return Map.of("nodes", nodes, "links", uniqueLinks);
    }

    private void addNeighbor(Map<String, Memory> byId, Set<String> seen, String neighborId) {
        if (seen.contains(neighborId)) return;
        memoryRepo.findById(neighborId).filter(m -> "active".equals(m.getStatus())).ifPresent(m -> {
            byId.put(m.getId(), m);
            seen.add(m.getId());
        });
    }

    private boolean hasLink(String a, String b) {
        return linkRepo.findByFromMemoryId(a).stream().anyMatch(l -> l.getToMemoryId().equals(b))
                || linkRepo.findByToMemoryId(a).stream().anyMatch(l -> l.getFromMemoryId().equals(b));
    }

    private void createLink(String a, String b, String relation, double strength) {
        MemoryLink link = new MemoryLink();
        link.setId(UUID.randomUUID().toString());
        link.setFromMemoryId(a);
        link.setToMemoryId(b);
        link.setRelation(relation);
        link.setStrength(strength);
        linkRepo.save(link);
    }

    static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        String clean = s.replaceAll("[\\s\\p{Punct}，。！？、；：【】《》]", "");
        for (int i = 0; i + 2 <= clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        return set;
    }

    static double overlapRatio(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / Math.min(a.size(), b.size());
    }
}
