package com.luxera.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Optional;

/**
 * Test-only bootstrap for the digital-human module.
 *
 * <p>In production this platform is launched by {@code bootstrap-app} alongside the chat platform,
 * which supplies the two ports below through their chat-side adapters. Here it is launched alone —
 * which is exactly the property worth testing: the digital human must live, think and act with no
 * chat platform on the classpath. The ports it cannot answer by itself are faked in-memory.
 */
// 排除 G2 的 chat 侧占位集成 —— 测试自己提供 InMemory 桩, 占位只服务
// server/openapi 两个真实服务进程(那边没有测试桩)。
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.luxera\\.companion\\.digitalhuman\\.integration\\..*"))
@EnableAsync
@EnableScheduling
public class DigitalHumanTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalHumanTestApplication.class, args);
    }

    /**
     * The chat platform owns conversations and messages. Module tests use an in-memory stand-in so
     * that a reply she writes can be read back; cross-platform behaviour is tested in
     * {@code bootstrap-app} against the real adapter.
     */
    @Bean
    ChatWorldPort inMemoryChatWorld() {
        return new InMemoryChatWorld();
    }

    /**
     * Device tokens are issued by the chat platform; with no chat platform present the device is
     * simply unknown, which is a state the connector already handles.
     */
    @Bean
    SimulatorAccessPort simulatorAccessPort() {
        return (deviceId, secret) -> Optional.empty();
    }

    /**
     * The application platform is a separate module. When the digital human is launched here it
     * has none, so its one application-shaped dependency is faked in-memory — and faked
     * <em>as a working application</em> rather than an empty port: {@link InMemoryGameApplication}
     * keeps real state, has a real opinion about whose turn it is, and can be played to the end.
     *
     * <p>An empty {@code Optional.empty()} port would let {@code AgentApplicationFlow} rot while
     * every assertion stayed green — the exact failure this refactor exists to prevent.
     */
    @Bean
    ApplicationRuntimePort applicationRuntimePort(ObjectMapper objectMapper) {
        return new InMemoryGameApplication(objectMapper);
    }
}
