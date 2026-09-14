package com.luxera.companion.digitalhuman.event.handlers;

import com.luxera.companion.digitalhuman.event.AgentEventHandler;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 链首节点: Validation(事件完整性校验)。
 * 校验失败直接终止链 —— 残缺事件不进入认知(因果链入口守卫)。
 * note 以 REJECT: 开头, 由 EventProcessingChain 映射为 REJECTED 状态。
 */
@Component
public class ValidationHandler implements AgentEventHandler {

    @Override
    public boolean supports(ExternalEvent event) {
        return true;
    }

    @Override
    public HandlingResult handle(ExternalEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            return HandlingResult.terminate("REJECT: 事件缺少 eventId");
        }
        if (event.occurredAt() == null) {
            return HandlingResult.terminate("REJECT: 事件缺少 occurredAt");
        }
        Object source = event.payload() == null ? null : event.payload().get("source");
        if (event.type() == ExternalEventType.CHAT_MESSAGE_DELIVERED
                && (source == null || source.toString().isBlank())) {
            return HandlingResult.terminate("REJECT: 聊天消息事件缺少 source");
        }
        return HandlingResult.continueChain("校验通过");
    }
}
