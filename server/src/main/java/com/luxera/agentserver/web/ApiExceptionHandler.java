package com.luxera.agentserver.web;

import com.luxera.companion.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.persistence.EntityNotFoundException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务异常 → HTTP 响应。**在这个类出现之前, 8091 一个异常处理器都没有。**
 *
 * <h2>为什么必须补上 (2026-09-17)</h2>
 *
 * 没有它的时候, 一个 {@code BusinessException(NOT_FOUND, "找不到这个 Agent")} 从 8091 抛出来,
 * 走的是 Spring Boot 的默认错误处理, 客户端收到的是:
 *
 * <pre>
 * HTTP 500
 * {"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/companions/xxx"}
 * </pre>
 *
 * 三件事同时错了: <b>状态码错</b>(404 变 500)、<b>消息错</b>(用户看到英文的 "Internal Server
 * Error")、<b>hint 丢失</b>(那句话是专门写给用户看的下一步)。
 *
 * <p>在此之前这只是"报错不好看"。做账号ID 时它变成了硬阻塞: 改号失败要分 400(形状不对) /
 * 409(被别人占了) / 429(一年三次用完了), 而这三者在用户眼里是三件要采取不同行动的事 ——
 * 全被压成同一个 500 的话, 界面只能显示"修改失败", 让用户自己去猜该改什么。所以这个类是
 * 账号ID 功能的**前置件**, 不是顺手做的清理。
 *
 * <h2>为什么住在 {@code com.luxera.agentserver} 而不是 {@code com.luxera.companion}</h2>
 *
 * 8091 与 8092 是两个进程, 但共用 {@code :common} 与 {@code :digital-human} 两个模块。
 * 放进那两个模块等于**同时改掉 8092 的对外行为** —— 而 8092 是三方机器调用的 OpenAPI 面,
 * 它的错误形状是对外契约, 不该被一次聊天侧的改动捎带着改。
 *
 * <p>{@code com.luxera.agentserver} 只被 8091 扫描(见 {@code SimulationAgentPlatformApplication}
 * 的 scanBasePackages), 所以这个类只影响 8091。8092 要不要同样的东西, 是一个独立的决定。
 *
 * <h2>与仓 1 的关系</h2>
 *
 * 仓 1 有一个同名同职责的 {@code GlobalExceptionHandler}(住在它的 {@code :common})。
 * 两边**刻意保持一致的响应形状** {@code {error, hint}} —— 因为浏览器只跟聊天平台一个域名
 * 说话, 伴侣域的请求由 8081 在服务端转发过来({@code CompanionDomainProxyController} 会
 * 原样透传状态码与响应体)。任何一侧的形状变了, 前端就得为"错误从哪来"分叉。
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    /** 与仓 1 的 {@code ApiError} 同形状。这里不 import 它 —— 那是仓 1 的类, 本仓不该依赖。 */
    private static Map<String, String> body(String error, String hint) {
        // Map.of 不接受 null —— hint 绝大多数时候是空的, 而传 null 进去会抛 NPE,
        // 于是"报一个错"变成"报错的时候自己又炸一次", 且只在没有 hint 的错误上复现
        return hint == null || hint.isBlank()
                ? Map.of("error", error)
                : Map.of("error", error, "hint", hint);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getMessage(), e.getHint()));
    }

    /**
     * {@code requireById} 一族在找不到东西时抛这个。
     *
     * <p>仓 1 的处理器也认它, 两边形状因此一致。
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(e.getMessage(), null));
    }

    /**
     * {@code @Valid} 没通过。
     *
     * <p>把字段名一起带上 —— "不能为空"不说是哪个字段是没法行动的。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body(msg, null));
    }

    /**
     * 凭证不对 / 没权限。
     *
     * <p>必须显式列出, **不能**让它们落到下面的兜底里 —— 那两个各有自己的状态码(401/403),
     * 被兜底收走就变成 500, 而 401 与 500 对客户端是"该重新登录"和"该报警"两种完全不同的反应。
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("用户名或密码错误", null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("没有权限访问", null));
    }

    /**
     * 兜底 —— 没预料到的异常。
     *
     * <p><b>刻意不回 {@code e.getMessage()}。</b> 仓 1 那个处理器把它放进了 hint, 但这里
     * 是一条会被浏览器直接读到的路径, 而意外异常的 message 里可能是 SQL 片段、类名、路径,
     * 甚至连接串的一部分。它进日志(带栈), 客户端只拿到一句"服务器内部错误"。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("服务器内部错误", null));
    }
}
