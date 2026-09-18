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
    private final com.luxera.companion.persona.AgentSwitchService agentSwitch;

    public EventProcessingChain(List<AgentEventHandler> handlers,
                                com.luxera.companion.persona.AgentSwitchService agentSwitch) {
        this.handlers = List.copyOf(handlers);
        this.agentSwitch = agentSwitch;
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
        // Agent 开关: 事件是进入认知的**两条**路之一(另一条是 AgentRuntime.process 的
        // 同步直调), 所以闸门必须在这条链的最前面 —— 一次拦住全部事件类型:
        // 消息送达、时间事件、生活事件、设备通知、应用事件。
        //
        // 放在链**内部**而不是各调用方: 这条链的调用方有四个(AgentRuntime 的 mailbox、
        // OutboxRelayJob 的兜底补投、WakeupCatchUpService 的醒来补处理、
        // AgentApplicationFlow 的应用事件), 在调用方各拦一次就是四处漏水点。
        //
        // 与 AgentRuntime mailbox 里那道闸门**刻意重复**: 那道是"入口"(连事件都不必构造),
        // 这道是"总线"(所有事件类型都经过)。多花一次主键查询, 换来的是"新加一种事件类型
        // 时不必记得再拦一次" —— 而忘了的那次是要花钱的。
        if (!agentSwitch.isRunnable(event.personId())) {
            log.debug("[EventChain] agent {} 已暂停, 丢弃事件 type={}", event.personId(), event.type());
            return ChainOutcome.rejected("agent 已暂停");
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
