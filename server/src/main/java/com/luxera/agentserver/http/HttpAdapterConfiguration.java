package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * G3 落地: 仿真 Agent 平台(server)对 chat-platform 的三个跨服务端口全部改走 HTTP。
 * 注册本配置后, {@code digitalhuman/integration/ChatPlatformIntegration} 的
 * {@code @ConditionalOnMissingBean} 占位自动退位（README G2 已预告删除, 本轮随本
 * 配置一起删）。
 *
 * <p>适配器注册为普通 bean（非 @Component）—— 装配决策(哪个进程提供哪个端口)是
 * 服务级决策, 不该散在业务类上。openapi 服务(8092)不扫本包: 它 G4 才按需引入,
 * 到时自己决定用哪几个端口。
 */
@Configuration
public class HttpAdapterConfiguration {

    @Bean
    ChatWorldPort chatWorldPort(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBaseUrl,
            @Value("${app.agent-platform.internal-service-key:}") String serviceKey,
            @Value("${app.chat-platform.timeout-ms:5000}") int timeoutMs,
            ObjectMapper objectMapper) {
        return new HttpChatWorldAdapter(chatBaseUrl, serviceKey, objectMapper, timeoutMs);
    }

    @Bean
    ApplicationRuntimePort applicationRuntimePort(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBaseUrl,
            @Value("${app.agent-platform.internal-service-key:}") String serviceKey,
            @Value("${app.chat-platform.timeout-ms:5000}") int timeoutMs,
            ObjectMapper objectMapper) {
        return new HttpApplicationRuntimeAdapter(chatBaseUrl, serviceKey, objectMapper, timeoutMs);
    }

    @Bean
    SimulatorAccessPort simulatorAccessPort(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBaseUrl,
            @Value("${app.agent-platform.internal-service-key:}") String serviceKey,
            @Value("${app.chat-platform.timeout-ms:5000}") int timeoutMs,
            ObjectMapper objectMapper) {
        return new HttpSimulatorAccessAdapter(chatBaseUrl, serviceKey, objectMapper, timeoutMs);
    }
}
