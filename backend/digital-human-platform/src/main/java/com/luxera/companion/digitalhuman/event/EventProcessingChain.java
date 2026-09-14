package com.luxera.companion.digitalhuman.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V10 §9.2 Event Processing Chain(Chain of Responsibility):
 * 统一的事件处理管道。
 *
 * 事件流: ExternalEvent → Validation → Deduplication → Route → 各 Runtime Handler。
 * 任何外部刺激都不能绕过这条链进入认知(V10 §1.2 因果链)。
 *
 * 链节点通过 Spring 注入, 顺序即注册顺序; 节点返回 TERMINATE 时短路。
 */
@Slf4j
@Component
public class EventProcessingChain {

    private final List<AgentEventHandler> handlers;

    public EventProcessingChain(List<AgentEventHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /**
     * 处理一个外部事件。返回链的最终处理结果。
     * 若事件被幂等层判定为已处理, 返回 DEDUPLICATED(不会重复触发认知)。
     */
    public ChainOutcome process(ExternalEvent event) {
        if (event == null) {
            return ChainOutcome.rejected("事件为空");
        }
        if (event.personId() == null || event.personId().isBlank()) {
            return ChainOutcome.rejected("事件缺少 personId");
        }
        log.debug("[EventChain] {} type={} person={}", event.eventId(), event.type(), event.personId());
        for (AgentEventHandler handler : handlers) {
            try {
                if (!handler.supports(event)) {
                    continue;
                }
                AgentEventHandler.HandlingResult result = handler.handle(event);
                if (result.terminate()) {
                    if (result.note() != null && result.note().startsWith("REJECT:")) {
                        return ChainOutcome.rejected(result.note().substring("REJECT:".length()).trim());
                    }
                    if (result.note() != null && result.note().startsWith("DEDUP:")) {
                        return ChainOutcome.deduplicated(result.note().substring("DEDUP:".length()).trim());
                    }
                    return ChainOutcome.terminated(result.note());
                }
            } catch (Exception e) {
                log.error("[EventChain] handler={} 处理失败 event={}: {}",
                        handler.getClass().getSimpleName(), event.eventId(), e.getMessage());
                return ChainOutcome.failed(handler.getClass().getSimpleName(), e.getMessage());
            }
        }
        return ChainOutcome.completed();
    }

    /** 链处理结果 */
    public record ChainOutcome(Status status, String note) {

        public enum Status { COMPLETED, TERMINATED, DEDUPLICATED, REJECTED, FAILED }

        public static ChainOutcome completed() {
            return new ChainOutcome(Status.COMPLETED, "ok");
        }

        public static ChainOutcome terminated(String note) {
            return new ChainOutcome(Status.TERMINATED, note);
        }

        public static ChainOutcome rejected(String note) {
            return new ChainOutcome(Status.REJECTED, note);
        }

        public static ChainOutcome failed(String handler, String error) {
            return new ChainOutcome(Status.FAILED, handler + ": " + error);
        }

        public static ChainOutcome deduplicated(String note) {
            return new ChainOutcome(Status.DEDUPLICATED, note);
        }

        public boolean ok() {
            return status == Status.COMPLETED || status == Status.TERMINATED;
        }
    }
}
