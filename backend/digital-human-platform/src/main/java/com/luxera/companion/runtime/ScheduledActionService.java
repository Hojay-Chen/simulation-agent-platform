package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 排程动作服务(§64): 持久化主动行为, 服务重启不丢。
 * 真人不会因为服务器重启而失忆 —— 延迟回复/未回复查/主动分享都从这里恢复。
 */
@Service
public class ScheduledActionService {

    /** 便捷常量(见 {@link ScheduledAction}) */
    public static final String SEND_MESSAGE = ScheduledAction.SEND_MESSAGE;
    public static final String CHECK_MESSAGE = ScheduledAction.CHECK_MESSAGE;
    public static final String RE_EVALUATE_MESSAGE = ScheduledAction.RE_EVALUATE_MESSAGE;
    public static final String ACTIVITY_END = ScheduledAction.ACTIVITY_END;
    public static final String EVENT_SIMULATION = ScheduledAction.EVENT_SIMULATION;
    public static final String EMOTION_RECHECK = ScheduledAction.EMOTION_RECHECK;
    public static final String PROACTIVE_THOUGHT = ScheduledAction.PROACTIVE_THOUGHT;

    private final ScheduledActionRepository repo;
    private final ObjectMapper mapper;

    public ScheduledActionService(ScheduledActionRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional
    public ScheduledAction schedule(String companionId, String actionType, LocalDateTime executeAt, Map<String, Object> payload) {
        ScheduledAction a = new ScheduledAction();
        a.setCompanionId(companionId);
        a.setActionType(actionType);
        a.setExecuteAt(executeAt);
        a.setPayload(toJson(payload));
        return repo.save(a);
    }

    @Transactional
    public ScheduledAction schedule(String companionId, String actionType, LocalDateTime executeAt, String payloadJson) {
        ScheduledAction a = new ScheduledAction();
        a.setCompanionId(companionId);
        a.setActionType(actionType);
        a.setExecuteAt(executeAt);
        a.setPayload(payloadJson);
        return repo.save(a);
    }

    /** 到期的待执行动作(升序) */
    @Transactional(readOnly = true)
    public List<ScheduledAction> dueActions(LocalDateTime now) {
        return repo.findByStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(ScheduledAction.STATUS_PENDING, now);
    }

    @Transactional(readOnly = true)
    public List<ScheduledAction> pending(String companionId) {
        return repo.findByCompanionIdAndStatus(companionId, ScheduledAction.STATUS_PENDING);
    }

    @Transactional(readOnly = true)
    public List<ScheduledAction> pending(String companionId, String actionType) {
        return repo.findByCompanionIdAndStatusAndActionType(companionId, ScheduledAction.STATUS_PENDING, actionType);
    }

    @Transactional
    public void markDone(String id) {
        repo.findById(id).ifPresent(a -> {
            a.setStatus(ScheduledAction.STATUS_DONE);
            repo.save(a);
        });
    }

    @Transactional
    public void markFailed(String id) {
        repo.findById(id).ifPresent(a -> {
            a.setRetryCount(a.getRetryCount() + 1);
            if (a.getRetryCount() >= 3) {
                a.setStatus(ScheduledAction.STATUS_FAILED);
            }
            repo.save(a);
        });
    }

    @Transactional
    public void cancel(String id) {
        repo.findById(id).ifPresent(a -> {
            a.setStatus(ScheduledAction.STATUS_CANCELLED);
            repo.save(a);
        });
    }

    /** 取消某 companion 的同类待执行动作(例如消息回复后取消复查) */
    @Transactional
    public void cancelPending(String companionId, String actionType) {
        for (ScheduledAction a : repo.findByCompanionIdAndStatusAndActionType(companionId,
                ScheduledAction.STATUS_PENDING, actionType)) {
            a.setStatus(ScheduledAction.STATUS_CANCELLED);
            repo.save(a);
        }
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null) return null;
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
