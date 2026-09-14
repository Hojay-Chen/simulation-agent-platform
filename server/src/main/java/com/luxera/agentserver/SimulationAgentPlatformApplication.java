package com.luxera.agentserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仿真 Agent 功能服务启动类 —— 仓 2 的第 1 个服务（端口 8091）。
 *
 * <p>扫描仿真 Agent 平台的 41 个顶层包（digitalhuman 及全部认知/生活/记忆包）+
 * 仓 2 公共件（auth/config/common/outbox）。对 chat 世界与 LAP 的全部认知
 * 止于 contract artifact 的 SPI 端口 —— ChatWorldPort / ApplicationRuntimePort /
 * SimulatorAccessPort 的 HTTP 适配器由 G3 落地, 当前进程内由占位/桩承担。
 *
 * <p>本类住在 {@code com.luxera.agentserver}（业务包之外）, JPA 的仓储/实体扫描
 * 显式指向业务包根 —— 与仓 1 ChatPlatformApplication 同一模式。
 */
@SpringBootApplication(scanBasePackages = {
        // 仿真 Agent 平台主体（41 个顶层包全在 com.luxera.companion 树下）
        // + 仓 2 公共件（auth/config/common/outbox 同在这棵树下）
        "com.luxera.companion",
        // 本服务自己的装配件（ChatPlatformIntegration 的 G2→G3 占位端口）
        // —— scanBasePackages 一旦显式指定, 启动类所在包的默认扫描就被替换,
        // 必须把 agentserver 也列进来
        "com.luxera.agentserver",
})
@EnableJpaRepositories(basePackages = "com.luxera.companion")
@EntityScan(basePackages = "com.luxera.companion")
@EnableAsync
@EnableScheduling
public class SimulationAgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulationAgentPlatformApplication.class, args);
    }
}
