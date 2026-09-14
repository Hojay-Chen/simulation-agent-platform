package com.luxera.companion.digitalhuman.event.handlers;

import com.luxera.companion.digitalhuman.event.AgentEventHandler;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ProcessedEventRepository;
import org.springframework.stereotype.Component;

/**
 * 链节点: Deduplication(事件去重, V10 §21.2)。
 *
 * processed_event 表已存在该 eventId → 直接终止链(幂等短路),
 * 保证重试/重放不会重复触发认知(V10 MVP 验收 13)。
 */
@Component
public class DeduplicationHandler implements AgentEventHandler {

    private final ProcessedEventRepository processedEventRepository;

    public DeduplicationHandler(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Override
    public boolean supports(ExternalEvent event) {
        return true;
    }

    @Override
    public HandlingResult handle(ExternalEvent event) {
        try {
            if (processedEventRepository.existsByEventId(event.eventId())) {
                return HandlingResult.terminate("DEDUP: 事件已处理, 幂等短路: " + event.eventId());
            }
        } catch (Exception e) {
            // 幂等检查失败不阻塞处理(宁可多处理一次, 不可漏处理)
        }
        return HandlingResult.continueChain("未处理过");
    }
}
