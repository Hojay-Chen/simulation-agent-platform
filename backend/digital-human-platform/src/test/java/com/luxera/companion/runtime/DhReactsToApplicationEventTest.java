package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.InMemoryGameApplication;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <b>数字人平台独自在场时也能应用事件的往返</b> —— 这是模块拆分真正的验收点。
 *
 * <p>链路上没有任何 application-platform 的类: 应用是测试自带的
 * {@link InMemoryGameApplication}, 事件从数字人自己的 {@link ApplicationEventSink} 进来,
 * 决策之后经 {@link ApplicationRuntimePort} 回到同一个应用。
 *
 * <p>这和 {@code bootstrap-app} 里的 {@code LapEndToEndTest} 是两件事: 那一条证明两个平台
 * 合起来能跑, 这一条证明它们各自能跑 —— 少了这一条, "拆开了"就只是目录结构的说法。
 */
@ActiveProfiles("test")
@SpringBootTest
class DhReactsToApplicationEventTest {

    @Autowired
    ApplicationRuntimePort port;

    @Autowired
    ApplicationEventSink sink;

    @Autowired
    ObjectMapper mapper;

    /** 真实 LLM 太慢也不确定; 这里只关心"链路把 LLM 的选择变成了落子"。 */
    @MockBean
    LlmRouter llmRouter;

    @Test
    void theDigitalHumanAnswersThroughThePortAlone() throws Exception {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class))).thenReturn(new StructuredResult("""
                {"input":{"position":4},"reason":"占据中心"}
                """, mapper));

        // user_id / companion_id 是 varchar(36), 别把前缀拼进去
        String userId = UUID.randomUUID().toString();
        String companionId = UUID.randomUUID().toString();
        String uri = InMemoryGameApplication.newRoomUri();

        // 真人开局并落子在左上角 —— 全程只经过端口, 没有一条 SQL 是测试自己写的
        port.execute(new ActionRequest("game.create", uri, null, null),
                InvocationContext.human(userId, "corr-create"));
        port.execute(new ActionRequest("game.make_move", uri,
                        mapper.readTree("{\"position\":0}"), null),
                InvocationContext.human(userId, "corr-move"));

        // 应用把"轮到你了"这件事告诉数字人(真实场景里由应用在提交后发射)
        sink.emit(new ApplicationEvent(uri + "#MOVE-0", "game.move", "in-memory-game", uri,
                Instant.now(), mapper.valueToTree(Map.of(
                        "companionId", companionId, "userId", userId, "agentTrigger", true))));

        InMemoryGameApplication app = (InMemoryGameApplication) port;
        String[] board = awaitMark(app, uri, "O", 8_000);
        assertNotNull(board, "数字人应经通用链路应手");
        assertEquals("O", board[4], "LLM 选了中心(4), 链路应把它变成棋盘上的一枚 O");
        assertEquals("X", board[0], "真人的那一手必须还在");
    }

    /**
     * {@code agentTrigger=false} 的事件必须连读都不读 —— 事件链是异步的, 所以这里断言的是
     * "一段时间之内棋盘没变", 而不是"某次调用没发生"。
     */
    @Test
    void aNonTriggeringEventIsIgnored() throws Exception {
        String uri = InMemoryGameApplication.newRoomUri();
        String userId = UUID.randomUUID().toString();
        port.execute(new ActionRequest("game.create", uri, null, null),
                InvocationContext.human(userId, "corr-create-2"));

        sink.emit(new ApplicationEvent(uri + "#START", "game.start", "in-memory-game", uri,
                Instant.now(), mapper.valueToTree(Map.of(
                        "companionId", UUID.randomUUID().toString(), "agentTrigger", false))));

        Thread.sleep(500);
        InMemoryGameApplication app = (InMemoryGameApplication) port;
        assertEquals("", app.cellAt(uri, 4), "没被唤起就不该落子");
    }

    private String[] awaitMark(InMemoryGameApplication app, String uri, String mark, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String[] board = new String[9];
            boolean found = false;
            for (int i = 0; i < 9; i++) {
                board[i] = app.cellAt(uri, i);
                if (mark.equals(board[i])) found = true;
            }
            if (found) return board;
            Thread.sleep(100);
        }
        return null;
    }
}
