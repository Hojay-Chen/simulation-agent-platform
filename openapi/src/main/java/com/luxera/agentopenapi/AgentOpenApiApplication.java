package com.luxera.agentopenapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 对外 OpenAPI 服务启动类 —— 仓 2 的第 2 个服务（端口 8092）。
 *
 * <p>用户拍板（2026-09-13）: 仿真 Agent 平台有两个服务 —— 功能服务(server 项目,
 * 拉起数字人认知链)与本服务(对外 OpenAPI, 让外部服务/三方平台创建、删除、管理、
 * 使用自己的仿真 agent)。两个启动类各自在各自项目目录, 不共用。
 *
 * <p>G2 只立骨架: 启动类 + 健康端点 + 与功能服务同库的数据访问面。
 * REST + API Key + OpenAPI 3.1 spec(springdoc) 的业务端点是 G4 的内容。
 */
@SpringBootApplication(scanBasePackages = {
        // G2 骨架: 只扫仓 2 公共件(kernel 类)与本服务自己的包。
        // digital-human 的认知链不进本服务 —— G4 接业务端点时再按需引入。
        "com.luxera.companion",
        "com.luxera.agentopenapi",
})
@EnableJpaRepositories(basePackages = "com.luxera.companion")
@EntityScan(basePackages = "com.luxera.companion")
@EnableAsync
@EnableScheduling
public class AgentOpenApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentOpenApiApplication.class, args);
    }
}
