package com.luxera.companion.cognitive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V9 §4.3 Cognitive Session Service: 连续心智的读写。
 *
 * - getOrCreate: 每个 Agent 一条(companion_id 唯一)
 * - update: 以 state_version 乐观锁提交, 冲突时重读最新再合并(不覆盖用户刚产生的状态)
 * - activePlanBriefs: 返回进行中的计划摘要(供上下文注入)
 */
@Slf4j
@Service
public class CognitiveSessionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitiveSessionRepository repo;

    public CognitiveSessionService(CognitiveSessionRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public CognitiveSession getOrCreate(String companionId) {
        return repo.findByCompanionId(companionId).orElseGet(() -> {
            CognitiveSession s = new CognitiveSession();
            s.setCompanionId(companionId);
            s.setActivePlans("[]");
            return repo.save(s);
        });
    }

    /** 更新认知状态(乐观锁: 版本冲突时重读最新并重试一次, 保证不覆盖并发写入) */
    @Transactional
    public CognitiveSession update(String companionId, java.util.function.Consumer<CognitiveSession> mutator) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CognitiveSession s = getOrCreate(companionId);
            long expected = s.getStateVersion();
            mutator.accept(s);
            int updated = repo.updateIfVersion(companionId, s.getCurrentFocus(), s.getCurrentThought(),
                    s.getCurrentIntention(), s.getActivePlans(), s.getEmotionSummary(),
                    expected, expected + 1);
            if (updated > 0) {
                s.setStateVersion(expected + 1);
                return s;
            }
            // 冲突: 重读最新, 下次循环重试
        }
        log.debug("[CognitiveSession] {} 乐观锁冲突超过重试上限", companionId);
        return getOrCreate(companionId);
    }

    /** 处理用户消息后更新认知焦点(他刚才在说什么/我想到什么) */
    @Transactional
    public void touchOnMessage(String companionId, String focus, String thought, String emotionSummary) {
        update(companionId, s -> {
            if (focus != null && !focus.isBlank()) s.setCurrentFocus(focus);
            if (thought != null && !thought.isBlank()) s.setCurrentThought(thought);
            if (emotionSummary != null && !emotionSummary.isBlank()) s.setEmotionSummary(emotionSummary);
        });
    }

    /** 记录当前想法(主动事件/后台触发) */
    @Transactional
    public void setThought(String companionId, String thought) {
        update(companionId, s -> {
            if (thought != null && !thought.isBlank()) s.setCurrentThought(thought);
        });
    }

    /** 进行中计划摘要写入 */
    @Transactional
    public void setActivePlans(String companionId, List<Map<String, Object>> planBriefs) {
        update(companionId, s -> {
            try {
                s.setActivePlans(MAPPER.writeValueAsString(planBriefs == null ? List.of() : planBriefs));
            } catch (Exception e) {
                log.debug("[CognitiveSession] 计划序列化失败: {}", e.getMessage());
            }
        });
    }

    /** 进行中的计划摘要(供 ContextCompiler 注入) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activePlanBriefs(String companionId) {
        CognitiveSession s = repo.findByCompanionId(companionId).orElse(null);
        if (s == null || s.getActivePlans() == null || s.getActivePlans().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = MAPPER.readValue(s.getActivePlans(), new TypeReference<>() { });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 认知摘要(供 L2 动态层注入) */
    @Transactional(readOnly = true)
    public String describe(String companionId) {
        CognitiveSession s = repo.findByCompanionId(companionId).orElse(null);
        if (s == null) return null;
        List<String> parts = new ArrayList<>();
        if (s.getCurrentFocus() != null && !s.getCurrentFocus().isBlank()) {
            parts.add("正关注:" + s.getCurrentFocus());
        }
        if (s.getCurrentThought() != null && !s.getCurrentThought().isBlank()) {
            parts.add("心里想:" + s.getCurrentThought());
        }
        if (s.getCurrentIntention() != null && !s.getCurrentIntention().isBlank()) {
            parts.add("想:" + s.getCurrentIntention());
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    /** 供测试/诊断: 读取原始 */
    @Transactional(readOnly = true)
    public CognitiveSession get(String companionId) {
        return repo.findByCompanionId(companionId).orElse(null);
    }

    public static Map<String, Object> planBrief(String title, String status, String expectedTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("status", status);
        m.put("expectedTime", expectedTime);
        return m;
    }
}
