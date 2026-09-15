package com.luxera.agentserver.internal;

import com.luxera.companion.config.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * server(8091) 的安全配置 —— G3 落地 JWT 认识链 + /internal 面放行。
 *
 * <p><b>JWT 同源校验是 G3 check-split 抓出的缺口</b>: G2 拆分时
 * {@code JwtUtil}/{@code JwtAuthenticationFilter} 留在了仓 1 common, 8091 上 JWT
 * 从未被解析过 —— "同 secret 认同一个 token"只是设计意图, 带真 JWT 的请求在
 * 过滤器上就 401。现在补上: 同 io.jsonwebtoken 栈、同 {@code app.jwt.secret}
 * (users 表共享), chat 签发的令牌在此被同源验签。
 *
 * <p>放行 {@code /internal/**}: 调用方是 chat-platform 进程, 不是登录用户 —— JWT
 * 这一层表达不了服务身份, 与仓 1 MCP/Developer API 同一个道理。鉴权由
 * {@link InternalAuthFilter} 前置完成(HMAC 签名 + 共享 internal-service-key),
 * 没配密钥的部署上 /internal 是 503 死端点。
 *
 * <p>业务面(/api/companions 等)吃 JWT —— 真人/管理操作从仓 1 前端带同一 token 过来。
 */
@Configuration
@EnableWebSecurity
public class ServerSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public ServerSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests(auth -> auth
                        .antMatchers("/internal/**").permitAll()
                        .antMatchers("/api/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
