package com.luxera.agentopenapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * G4 — openapi:8092 的安全配置。
 *
 * <p>{@code /api/v1/openapi/**} 全 permitAll —— 鉴权由 {@link OpenApiAuthFilter}
 * 在 Spring Security 之前做(两把钥匙两个面)。这不是"敞开": 过滤器拒掉一切
 * 无钥/错钥请求; Spring Security 这层对 openapi 域没有任何可表达的见解
 * (机器客户端没有 JWT), 留 anyRequest() 只会把每个请求都变 403 —— MCP 的坑
 * 在仓 1 踩过两次(README 有记), 这里直接绕开。
 *
 * <p>放行 {@code /docs/**}、{@code /swagger-ui/**} 与 {@code /v3/api-docs/**}
 * (springdoc 文档/spec 面, 三方接入第一步就是看文档, spec 不含密钥)与
 * {@code /api/health}。其余(如误配的内部端点)按默认 authenticated。
 *
 * <p><b>{@code /swagger-ui/**} 不能漏</b>(G7 部署时抓到的真缺陷): springdoc 的
 * {@code /docs} 不是一个页面, 而是一个 302 ——
 * {@code GET /docs → 302 Location: /swagger-ui/index.html}。只放行 {@code /docs}
 * 的结果是: 状态码看着对(302), 跟过去却吃 403 —— Swagger UI 从来就打不开。
 * G4 的验收脚本只查了 {@code /v3/api-docs}(spec JSON, 200), 没跟这条跳转,
 * 所以这个洞一路活到了 G7 部署。改动这里时请一并确认
 * {@code curl -L /docs} 最终落在 {@code /swagger-ui/index.html} 且是 200。
 */
@Configuration
@EnableWebSecurity
public class OpenApiSecurityConfig {

    private final OpenApiAuthFilter openApiAuthFilter;

    public OpenApiSecurityConfig(OpenApiAuthFilter openApiAuthFilter) {
        this.openApiAuthFilter = openApiAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests(auth -> auth
                        .antMatchers("/api/health").permitAll()
                        .antMatchers("/docs/**", "/docs", "/v3/api-docs/**").permitAll()
                        // /docs 的 302 落点 —— 少了它文档 UI 就是 403 (见类注释)
                        .antMatchers("/swagger-ui/**", "/swagger-ui").permitAll()
                        .antMatchers("/api/v1/openapi/**").permitAll()
                        .anyRequest().authenticated())
                // G4 的两把钥匙鉴权在 Spring Security 链内执行(permitAll 只放过了
                // Security 这层, OpenApiAuthFilter 在链内再拦一次无钥/错钥请求)
                .addFilterBefore(openApiAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
