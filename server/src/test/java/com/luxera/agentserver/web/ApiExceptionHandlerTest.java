package com.luxera.agentserver.web;

import com.luxera.companion.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.persistence.EntityNotFoundException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务异常 → HTTP 响应。**这个类存在的理由是它之前不存在。**
 *
 * <h2>它钉住的那个症状</h2>
 *
 * 2026-09-17 之前 8091 没有任何 {@code @ExceptionHandler}, 于是一个
 * {@code BusinessException(NOT_FOUND, ...)} 到客户端手里是:
 *
 * <pre>
 * HTTP 500 {"error":"Internal Server Error", ...}
 * </pre>
 *
 * 三条断言分别对应这里面的三个错: 状态码没被透传、消息被换成了英文、hint 整个丢了。
 * 任何一条挂了, 界面上就会出现"修改失败"这种用户没法据以行动的话。
 *
 * <p>为什么是纯单测而不是 {@code @SpringBootTest} + MockMvc: 那一套要拉一整个 8091 上下文
 * (认知链、LLM、数据源), 为了断言"状态码原样出来"付的代价太大, 而且它验的是 Spring 的
 * 消息转换而不是这个类的逻辑。**装配**(这个 advice 真的被 8091 扫到了)是另一回事 ——
 * 它由部署后的实测覆盖, 不是这组单测能替代的, 也不该假装能。
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void businessStatusIsPassedThroughInsteadOfBecomingA500() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.BAD_REQUEST, HttpStatus.CONFLICT, HttpStatus.TOO_MANY_REQUESTS}) {
            ResponseEntity<Map<String, String>> resp = handler.handleBusiness(
                    new BusinessException(status, "账号ID「xiaoman」已经被占用了", "可以试试 xiaoman_k3f"));

            assertEquals(status, resp.getStatusCode(),
                    status + " 必须原样透传 —— 400/409/429 是三件要采取不同行动的事");
        }
    }

    @Test
    void theUsersOwnLanguageSurvivesAndTheHintComesWithIt() {
        ResponseEntity<Map<String, String>> resp = handler.handleBusiness(
                new BusinessException(HttpStatus.CONFLICT,
                        "账号ID「xiaoman」已经被占用了", "可以试试 xiaoman_k3f"));

        assertEquals("账号ID「xiaoman」已经被占用了", resp.getBody().get("error"));
        assertEquals("可以试试 xiaoman_k3f", resp.getBody().get("hint"),
                "hint 是专门写给用户的下一步, 丢了它界面只能说「修改失败」");
    }

    /**
     * 没有 hint 的错误是**绝大多数**, 而这里是 {@code Map.of} 最容易炸的地方 ——
     * 传 null 进去抛 NPE, 于是"报一个错"变成"报错的时候自己又炸一次"。
     */
    @Test
    void anErrorWithoutAHintIsStillSerializable() {
        ResponseEntity<Map<String, String>> resp = handler.handleBusiness(
                new BusinessException(HttpStatus.FORBIDDEN, "这个东西不是你的", null));

        assertEquals("这个东西不是你的", resp.getBody().get("error"));
        assertFalse(resp.getBody().containsKey("hint"),
                "没有 hint 就不该有这个键 —— 塞一个 null 会让前端显示 \"null\"");
    }

    @Test
    void aBlankHintIsTreatedAsNoHint() {
        ResponseEntity<Map<String, String>> resp = handler.handleBusiness(
                new BusinessException(HttpStatus.BAD_REQUEST, "格式不对", "   "));

        assertFalse(resp.getBody().containsKey("hint"));
    }

    @Test
    void notFoundIsA404NotA500() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleNotFound(new EntityNotFoundException("伴侣的 Person 不存在"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("伴侣的 Person 不存在", resp.getBody().get("error"));
    }

    /**
     * 兜底不能把内部细节吐给客户端。
     *
     * <p>用一条**看起来像**真实泄漏的 message: 意外异常的 message 里可能是 SQL 片段、
     * 类名或连接串的一部分, 而这条路径是浏览器直接读的。
     */
    @Test
    void theCatchAllKeepsInternalsOutOfTheResponse() {
        ResponseEntity<Map<String, String>> resp = handler.handleGeneric(
                new IllegalStateException(
                        "could not execute statement; SQL [insert into persons ...]; "
                                + "nested exception is org.postgresql.util.PSQLException: "
                                + "ERROR: duplicate key value violates unique constraint"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("服务器内部错误", resp.getBody().get("error"));
        assertFalse(resp.getBody().containsKey("hint"));
        // 那句话一个字都不该出现在响应里 —— 它在日志里, 带栈
        String whole = resp.getBody().toString();
        assertFalse(whole.contains("PSQLException"), "内部异常细节泄漏到了响应体: " + whole);
        assertFalse(whole.contains("insert into"), "SQL 泄漏到了响应体: " + whole);
    }

    @Test
    void illegalArgumentIsA400WithItsMessage() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleBadRequest(new IllegalArgumentException("persona 不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("persona 不能为空", resp.getBody().get("error"));
    }

    /** 响应形状必须与仓 1 的 {@code ApiError} 一致 —— 浏览器分不出错误是从哪个进程来的。 */
    @Test
    void theBodyShapeMatchesRepoOnesApiError() {
        ResponseEntity<Map<String, String>> resp = handler.handleBusiness(
                new BusinessException(HttpStatus.BAD_REQUEST, "x", "y"));

        assertNotNull(resp.getBody());
        assertEquals(2, resp.getBody().size(), "只该有 error 和 hint 两个键");
        assertTrue(resp.getBody().keySet().containsAll(java.util.List.of("error", "hint")));
    }
}
