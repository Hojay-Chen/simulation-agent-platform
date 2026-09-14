package com.luxera.agentopenapi;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * G2 骨架期的最小安全配置: 放行健康端点, 其余走默认。
 * 对外 API 的认证体系(API Key + OpenAPI 3.1 spec)是 G4 的内容,
 * 到时本配置会被真正的鉴权链替换, 这里只保证骨架可探活。
 */
@Configuration
@EnableWebSecurity
public class OpenApiSecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .authorizeRequests()
                .antMatchers("/api/health").permitAll()
                .anyRequest().authenticated();
    }
}
