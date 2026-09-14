package com.luxera.companion.config;

import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 从 SecurityContext 读取当前登录用户(与 blog-platform 相同: principal = userId)。
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 当前请求的 userId,未登录返回 null */
    public String userId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof String uid) {
            return uid;
        }
        return null;
    }

    /** 当前请求的 userId,未登录抛 401 */
    public String requireUserId() {
        String uid = userId();
        if (uid == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("未登录");
        }
        return uid;
    }

    public User requireUser() {
        return userRepository.findById(requireUserId())
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("用户不存在"));
    }

    public User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("用户不存在"));
    }

    /** 当前请求上下文(便于在异步/工具方法里取 token 等) */
    public static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable(attrs.getRequest());
        }
        return Optional.empty();
    }
}
