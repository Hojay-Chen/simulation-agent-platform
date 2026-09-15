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
 * <p>放行 {@code /docs/**} 与 {@code /v3/api-docs/**}(springdoc 文档/spec 面,
 * 三方接入第一步就是看文档, spec 不含密钥)与 {@code /api/health}。
 * 其余(如误配的内部端点)按默认 authenticated。
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
                        .antMatchers("/api/v1/openapi/**").permitAll()
                        .anyRequest().authenticated())
                // G4 的两把钥匙鉴权在 Spring Security 链内执行(permitAll 只放过了
                // Security 这层, OpenApiAuthFilter 在链内再拦一次无钥/错钥请求)
                .addFilterBefore(openApiAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
