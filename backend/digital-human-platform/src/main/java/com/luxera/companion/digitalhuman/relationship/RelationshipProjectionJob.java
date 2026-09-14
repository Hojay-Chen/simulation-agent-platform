package com.luxera.companion.digitalhuman.relationship;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V10 §17 RelationshipProjectionJob: 每日核对全部伴侣的关系事实与 Reality Ledger。
 * cron 配置化(app.scheduler.relationship-projection-cron, 测试环境禁用)。
 *
 * 保证: 关系状态与真实经历一致 —— 无论增量更新(AgentPostProcessor)是否发生,
 * 账本投影都能把 messageCount/lastInteractionAt 拉回事实(幂等)。
 */
@Slf4j
@Component
public class RelationshipProjectionJob {

    private final CompanionRepository companionRepository;
    private final RelationshipProjectionService projectionService;

    public RelationshipProjectionJob(CompanionRepository companionRepository,
                                     RelationshipProjectionService projectionService) {
        this.companionRepository = companionRepository;
        this.projectionService = projectionService;
    }

    @Scheduled(cron = "${app.scheduler.relationship-projection-cron:0 0 4 * * *}")
    public void reconcileAll() {
        int reconciled = 0;
        for (Companion companion : companionRepository.findAll()) {
            if (companion.getDeletedAt() != null || companion.getUserId() == null) {
                continue;
            }
            try {
                if (projectionService.reconcile(companion.getId(), companion.getUserId())) {
                    reconciled++;
                }
            } catch (Exception e) {
                log.debug("[RelationshipProjection] {} 核对失败: {}", companion.getId(), e.getMessage());
            }
        }
        if (reconciled > 0) {
            log.info("[RelationshipProjection] 修正 {} 个伴侣的关系事实", reconciled);
        }
    }
}
