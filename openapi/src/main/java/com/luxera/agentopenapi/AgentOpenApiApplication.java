package com.luxera.agentopenapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
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
 * <p>G4 起本服务依赖 digital-human 的<b>persona 传递闭包</b>: persona →
 * relationship/person/llm/state/usermodel/selfmodel(闭包止于 common/契约, 不再外扩)。
 * 三个扫描列表(component/JPA/Entity)必须同步 —— 这个闭包是逐包试错跑出来的,
 * check-agent.sh 边界守卫静态断言它不再长大: 认知链包(runtime/cognition/life/
 * behavior/emotion/memory/…)永不进本服务 —— 那是 server:8091 的职责, openapi 是
 * 纯同步 API 面, 拉起认知链会两边争抢同一批表与定时任务。
 */
@SpringBootApplication
// G4 组件白名单 —— 不整包扫描 DH: DH 包"服务与控制器同居"的结构下, 整包扫描
// 会把认知链重服务(RelationshipEngine→memory, AvailabilityService→CompanionSchedule,
// PersonaEvolutionService→usermodel/selfmodel)连串拉起, 最终把 server:8091 的认知链
// 在本进程里复制一份。这里精确点名 persona 闭包里的<b>薄件</b>:
//   CompanionService / PersonaService / PersonaService(版本) / PersonaCompiler
//   / LlmRouter 线(llm 包全收, 只依赖 common+自身) / state 包的纯函数件
//   (AgentState 实体 + EmotionReducer + StateController 不进, AvailabilityService 不进)
//   / relationship 的两层薄件(Service+Repository 层, Engine/Narrative/Promise/Thread 不进)
// 仓储经 @EnableJpaRepositories 独立注册, 不依赖组件扫描 — 薄件的全部数据访问照常。
//
// 2026-09: AgentSwitchService 进白名单。它不是"又一个薄件" —— 它是 LlmRouter 的
// 构造参数(LLM 硬闸要从它问"这个 agent 停了吗"), 而 LlmRouter 早就在白名单里。
// 漏掉它的症状是 8092 **启动即失败**(无参可注入), 而不是某个功能悄悄失效 —— 这一条
// 是编译期/启动期的, 属于白名单里少有的"忘了会立刻知道"的情况。
// 它只依赖 CompanionRepository(persona 包, 已由 @EnableJpaRepositories 注册),
// 不拉起任何认知链组件, 所以它进得来。
@ComponentScan(basePackages = {
        "com.luxera.agentopenapi",            // 本服务自己的包
        // DH 范围包 — 列在这里只是给 includeFilters 一个搜索范围;
        // useDefaultFilters=false 下没过白名单正则的类(Engine/Narrative/Controller/
        // EvolutionService/AvailabilityService 等认知链件)全不注册。
        "com.luxera.companion.persona",
        "com.luxera.companion.relationship",
        "com.luxera.companion.person",
        "com.luxera.companion.state",
        "com.luxera.companion.llm",
        "com.luxera.companion.config",        // 仓 2 kernel(AppProperties/CurrentUser)
        "com.luxera.companion.common",
}, useDefaultFilters = false, includeFilters = @Filter(type = FilterType.REGEX, pattern =
        "com\\.luxera\\.companion\\.(persona\\.(CompanionService|PersonaService|PersonaCompiler|AgentSwitchService)"
        + "|relationship\\.RelationshipService"
        + "|person\\.PersonService"
        + "|state\\.(AgentStateService|EmotionReducer)"
        + "|llm\\.(LlmRouter|LlmCallService|LlmCallRepository|OpenAiCompatibleGateway|AnthropicGateway|MockLlmGateway|LlmGateway))"
        + "|com\\.luxera\\.agentopenapi\\..*(Controller|Filter|SecurityConfig)"
        + "|com\\.luxera\\.companion\\.config\\..*"))
@EnableJpaRepositories(basePackages = {
        "com.luxera.companion.persona",
        "com.luxera.companion.relationship",
        "com.luxera.companion.person",
        "com.luxera.companion.usermodel",
        "com.luxera.companion.selfmodel",
        "com.luxera.companion.llm",
        "com.luxera.companion.state",
        "com.luxera.companion.auth",
        "com.luxera.agentopenapi.client",   // OpenApiClientRepository
})
@EntityScan(basePackages = {
        "com.luxera.companion.persona",
        "com.luxera.companion.relationship",
        "com.luxera.companion.person",
        "com.luxera.companion.usermodel",
        "com.luxera.companion.selfmodel",
        "com.luxera.companion.llm",
        "com.luxera.companion.state",
        "com.luxera.companion.auth",
        "com.luxera.agentopenapi.client",
})
@EnableAsync
@EnableScheduling
public class AgentOpenApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentOpenApiApplication.class, args);
    }
}
