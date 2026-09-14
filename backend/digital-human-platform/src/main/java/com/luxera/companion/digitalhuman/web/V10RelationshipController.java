package com.luxera.companion.digitalhuman.web;

import com.luxera.companion.digitalhuman.relationship.InteractionSummary;
import com.luxera.companion.digitalhuman.relationship.RelationshipProjectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 关系投影诊断端点(验收可视化, V10 §17):
 * GET /api/v10/relationship/projection?companionId=xxx&userId=yyy
 * → 从 Reality Ledger 投影的互动摘要(事实层只信账本)。
 */
@RestController
@RequestMapping("/api/v10")
public class V10RelationshipController {

    private final RelationshipProjectionService projectionService;

    public V10RelationshipController(RelationshipProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @GetMapping("/relationship/projection")
    public Map<String, Object> projection(@RequestParam String companionId,
                                          @RequestParam String userId) {
        InteractionSummary summary = projectionService.projection(companionId);
        boolean reconciled = projectionService.reconcile(companionId, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("companionId", companionId);
        result.put("summary", Map.of(
                "totalEvents", summary.totalEvents(),
                "messagesSentByPerson", summary.messagesSentByPerson(),
                "messagesRead", summary.messagesRead(),
                "messagesDeferred", summary.messagesDeferred(),
                "messagesIgnored", summary.messagesIgnored(),
                "activitiesEnded", summary.activitiesEnded(),
                "replyRate", Math.round(summary.replyRate() * 100) / 100.0,
                "lastInteractionAt", summary.lastInteractionAt() == null ? null : summary.lastInteractionAt().toString()));
        result.put("reconciled", reconciled);
        result.put("principle", "关系事实层从 Reality Ledger 投影, 不手工维护(Memory 不能覆盖 Reality)");
        return result;
    }
}
