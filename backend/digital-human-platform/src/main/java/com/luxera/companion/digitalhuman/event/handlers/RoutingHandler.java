package com.luxera.companion.digitalhuman.event.handlers;

import com.luxera.companion.digitalhuman.event.AgentEventHandler;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ProcessedEventRecord;
import com.luxera.companion.digitalhuman.event.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 链尾节点: Router(事件路由)。
 *
 * 通过 {@link EventRouter} 把事件转交到对应 Runtime; 处理成功后
 * 写入 processed_event(幂等记录), 使重试可被 DeduplicationHandler 短路。
 */
@Slf4j
@Component
public class RoutingHandler implements AgentEventHandler {

    private final EventRouter eventRouter;
    private final ProcessedEventRepository processedEventRepository;

    public RoutingHandler(EventRouter eventRouter, ProcessedEventRepository processedEventRepository) {
        this.eventRouter = eventRouter;
        this.processedEventRepository = processedEventRepository;
    }

    @Override
    public boolean supports(ExternalEvent event) {
        return true;
    }

    @Override
    public HandlingResult handle(ExternalEvent event) {
        boolean routed = eventRouter.route(event);
        if (!routed) {
            return HandlingResult.terminate("无该类型路由: " + event.type());
        }
        try {
            processedEventRepository.save(ProcessedEventRecord.of(event, "routed"));
        } catch (Exception e) {
            log.warn("[EventChain] 幂等记录写入失败 event={}: {}", event.eventId(), e.getMessage());
        }
        return HandlingResult.continueChain("已路由到 " + event.type());
    }
}
