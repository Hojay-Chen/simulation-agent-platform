package com.luxera.companion.digitalhuman.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LAP v1 §12: 应用事件进入数字人世界的唯一入口。
 *
 * <p>它把平台的 {@link ApplicationEvent} 翻译成数字人的 {@link ExternalEvent} 并交给
 * {@link EventProcessingChain}(Validation → Dedup → Route)。翻译里有三件事值得说明:
 *
 * <ol>
 *   <li><b>personId 从 {@code data.companionId} 取。</b> {@code ApplicationEvent} 的信封被
 *       设计成六个字段, 没有"谁的"这一栏 —— 这个键由<em>平台</em>在投递前盖上
 *       ({@code LapEventPublisher} + {@code AgentRouteResolver}: 应用只说"这盘棋里还有谁",
 *       由平台查安装表认出其中的数字人)。应用自己不会也不需要知道 companionId。
 *       缺了就丢弃并告警, 绝不猜 —— 这里留着这道检查是因为本类是个独立消费者, 不该假定
 *       上游永远是对的。</li>
 *   <li><b>幂等由 {@code event.id()} 承担。</b> 平台已按 manifest 的 {@code idTemplate} 保证
 *       它确定性 —— 同一个 {@code ApplicationEvent} 重放会得到同一个 {@code eventId},
 *       于是被 DeduplicationHandler 短路, 数字人不会对同一步行动两次。</li>
 *   <li><b>{@code agentTrigger} 取自 {@code data.agentTrigger}, 缺省 false。</b> 这是两级闸门里
 *       的<em>第二级</em>: manifest 的 {@code events[].triggersAgent} 说"这类事件<em>可以</em>
 *       唤起数字人", 应用在 {@code data} 里说的则是"<em>这一次</em>该唤起"。两者取与 ——
 *       平台侧多一道过滤, 应用侧少一次误唤醒。缺了这个布尔就一律 false: 宁可数字人晚知道,
 *       也不要它无缘无故读一次别人的资源。</li>
 * </ol>
 *
 * <p><b>本类永久留在 digital-human-platform</b>: 它是"应用平台"与"数字人平台"之间那道
 * 单向门, 两边都不该知道对方的内部事件词汇。
 */
@Slf4j
@Component
public class DhApplicationEventSink implements ApplicationEventSink {

    private final EventProcessingChain chain;
    private final ObjectMapper objectMapper;

    public DhApplicationEventSink(EventProcessingChain chain, ObjectMapper objectMapper) {
        this.chain = chain;
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(ApplicationEvent event) {
        if (event == null) return;
        String companionId = companionIdOf(event);
        if (companionId == null || companionId.isBlank()) {
            log.warn("[AppEventSink] 事件 {} 缺少 data.companionId, 无法投递 —— 已丢弃", event.id());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", event.source());
        payload.put("eventType", event.type());
        payload.put("resourceUri", event.target());
        payload.put("agentTrigger", agentTriggerOf(event));
        payload.put("source", "application-platform");
        payload.putAll(flatten(event.data()));

        ExternalEvent external = new ExternalEvent(
                event.id(),
                companionId,
                ExternalEventType.APPLICATION_EVENT,
                event.occurredAt() == null ? Instant.now() : event.occurredAt(),
                payload,
                null);

        EventProcessingChain.ChainOutcome outcome = chain.process(external);
        log.debug("[AppEventSink] {} -> {} ({})", event.id(), event.type(), outcome.status());
    }

    private static String companionIdOf(ApplicationEvent event) {
        JsonNode data = event.data();
        if (data == null) return null;
        JsonNode companionId = data.path("companionId");
        return companionId.isMissingNode() || companionId.isNull() ? null : companionId.asText(null);
    }

    /** {@code data.agentTrigger}, 缺省 false —— 见类注释里的两级闸门。 */
    private static Boolean agentTriggerOf(ApplicationEvent event) {
        JsonNode data = event.data();
        if (data == null) return Boolean.FALSE;
        return data.path("agentTrigger").asBoolean(false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(JsonNode data) {
        if (data == null || data.isNull() || !data.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(data, Map.class);
        } catch (Exception e) {
            log.warn("[AppEventSink] payload 转换失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
