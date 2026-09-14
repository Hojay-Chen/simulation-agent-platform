package com.luxera.companion.digitalhuman.state;

import com.luxera.companion.cognitive.CognitiveSession;
import com.luxera.companion.cognitive.CognitiveSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V10 §21.1 StateVersionGate: 状态版本门 —— LLM 旧结果不能覆盖新状态(MVP 验收 12)。
 *
 * 用法(认知链路):
 *   long v = gate.snapshot(companionId);      // LLM 调用前快照
 *   ... LLM 调用(耗时) ...
 *   if (!gate.tryCommit(companionId, v)) {    // 返回时版本已变 → 结果作废
 *       discard();  // 丢弃或重新评估
 *   }
 *
 * 实现: 基于 cognitive_sessions.state_version 乐观锁(条件 UPDATE,
 * 影响行数 0 = 版本冲突)。版本由所有状态变更方(认知/计划/行为)统一递增。
 */
@Component
public class StateVersionGate {

    private final CognitiveSessionRepository repository;

    public StateVersionGate(CognitiveSessionRepository repository) {
        this.repository = repository;
    }

    /** LLM 调用前: 快照当前状态版本 */
    @Transactional(readOnly = true)
    public long snapshot(String companionId) {
        return repository.findByCompanionId(companionId)
                .map(CognitiveSession::getStateVersion)
                .orElse(0L);
    }

    /**
     * LLM 返回后: 尝试提交 —— 版本未变则递增并返回 true;
     * 版本已变(期间有其他状态变更)返回 false, 调用方必须丢弃/重评。
     */
    @Transactional
    public boolean tryCommit(String companionId, long expectedVersion) {
        CognitiveSession session = repository.findByCompanionId(companionId).orElse(null);
        if (session == null) {
            return false;
        }
        long next = session.getStateVersion() + 1;
        int updated = repository.updateIfVersion(companionId,
                session.getCurrentFocus(), session.getCurrentThought(),
                session.getCurrentIntention(), session.getActivePlans(), session.getEmotionSummary(),
                expectedVersion, next);
        return updated > 0;
    }
}
