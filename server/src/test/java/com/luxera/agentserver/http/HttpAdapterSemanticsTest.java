package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仓 2 三个 HTTP 适配器的<b>缺席语义</b> —— G2 占位哲学在 HTTP 面上的钉死。
 *
 * <p>适配器对"chat 平台不在"的三种姿态（与被删的 {@code ChatPlatformIntegration} 占位
 * 判据一一对应, README G2 有写）:
 * <ul>
 *   <li><b>读</b> → 空（"世界暂时是空的", fire-and-forget 下这不是失败）;</li>
 *   <li><b>写/变更</b> → 抛 {@code IllegalStateException}（沉默地假装写成功比诚实地说
 *     做不到糟糕得多）;</li>
 *   <li><b>fire-and-forget</b> → 吞掉（chat 缺席时认知链照常跑）;</li>
 *   <li><b>SimulatorAccess</b> → {@code Optional.empty()}（"拿不到 token 不连", 链路安全）。</li>
 * </ul>
 *
 * <p>HTTP 桩用 JDK {@code HttpServer} —— 单测不该依赖一个真的 chat 进程, 也不该为
 * 测试引 MockWebServer 依赖。两种缺席场景: <b>连接拒绝</b>（端口没人听）与
 * <b>5xx</b>（chat 进程在但正在重启/过载）—— 适配器对两者必须同样处理。
 */
class HttpAdapterSemanticsTest {

    private static final String KEY = "test-internal-service-key";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private HttpServer chatStub;
    private HttpChatWorldAdapter world;
    private HttpSimulatorAccessAdapter simulator;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        chatStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chatStub.start();
        port = chatStub.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        world = new HttpChatWorldAdapter(base, KEY, MAPPER, 2000);
        simulator = new HttpSimulatorAccessAdapter(base, KEY, MAPPER, 2000);
    }

    @AfterEach
    void tearDown() {
        chatStub.stop(0);
    }

    /** 桩上注册一个 handler, 返回固定状态码; 同时统计调用次数。 */
    private AtomicInteger stub(String path, int status, String body) {
        AtomicInteger hits = new AtomicInteger();
        chatStub.createContext(path, exchange -> {
            hits.incrementAndGet();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        return hits;
    }

    // ── 读: 缺席 → 空 ────────────────────────────────────────────────────────

    @Test
    void readsReturnEmptyWhenChatAnswers500() {
        stub("/internal/world/conversations/c1/messages", 500, "boom");
        assertTrue(world.messages("c1").isEmpty(), "5xx 等价于'世界暂时是空的'");
        assertTrue(world.conversation("c1").isEmpty());
    }

    @Test
    void readsReturnEmptyWhenChatIsUnreachable() throws IOException {
        chatStub.stop(0);   // 端口从此没人听 —— 连接拒绝
        assertTrue(world.messages("c1").isEmpty(), "连接拒绝等价于'世界暂时是空的'");
        assertTrue(world.recentMessages("c1", 10).isEmpty());
        assertEquals(0, world.countByCompanionAndKindSince("comp", "PROACTIVE",
                LocalDateTime.now().minusDays(1)));
    }

    @Test
    void readsReturnEmptyOn404() {
        stub("/internal/world/messages/m1", 404, "");
        assertTrue(world.message("m1").isEmpty(), "404 = 没有这条消息, 不是错误");
    }

    // ── 写: 缺席 → 抛 ────────────────────────────────────────────────────────

    @Test
    void writesThrowWhenChatAnswers500() {
        stub("/internal/world/messages", 500, "boom");
        assertThrows(IllegalStateException.class,
                () -> world.append(MessageAppendCommand.of("c1", "companion", "hi")),
                "5xx 的写必须诚实抛错, 不能装成功");
    }

    @Test
    void writesThrowWhenChatIsUnreachable() throws IOException {
        chatStub.stop(0);
        assertThrows(IllegalStateException.class,
                () -> world.ensureConversation("u1", "comp", "名字"));
        assertThrows(IllegalStateException.class,
                () -> world.append(MessageAppendCommand.of("c1", "companion", "hi")));
    }

    // ── fire-and-forget: 缺席 → 吞 ───────────────────────────────────────────

    @Test
    void fireAndForgetSwallowsAllFailures() throws IOException {
        chatStub.stop(0);
        assertDoesNotThrow(() -> world.markRead("comp", List.of("m1", "m2")));
        assertDoesNotThrow(() -> world.updateDeliveryStatus("comp", List.of("m1"), "READ"));
        assertDoesNotThrow(() -> world.publishEvent("comp", "companion.message", java.util.Map.of()));
    }

    @Test
    void fireAndForgetSwallowsEven500() {
        stub("/internal/world/messages/mark-read", 500, "boom");
        assertDoesNotThrow(() -> world.markRead("comp", List.of("m1")));
    }

    // ── Simulator: 缺席 → empty ─────────────────────────────────────────────

    @Test
    void simulatorAccessReturnsEmptyOnAnyFailure() throws IOException {
        chatStub.stop(0);
        assertEquals(Optional.empty(), simulator.refreshToken("device-1", "secret"));
    }

    @Test
    void simulatorAccessReturnsEmptyOn404() {
        stub("/internal/simulator/refresh-token", 404, "");
        assertEquals(Optional.empty(), simulator.refreshToken("device-1", "wrong-secret"));
    }

    @Test
    void simulatorAccessReturnsTokenWhenChatAnswers() {
        stub("/internal/simulator/refresh-token", 200, "{\"token\":\"tok-123\"}");
        assertEquals(Optional.of("tok-123"), simulator.refreshToken("device-1", "secret"));
    }

    // ── 成功路径: 签名头真的在发 ─────────────────────────────────────────────

    @Test
    void requestsCarryHmacSignatureHeaders() {
        AtomicInteger hits = new AtomicInteger();
        chatStub.createContext("/internal/world/conversations/c1/messages", exchange -> {
            hits.incrementAndGet();
            String ts = exchange.getRequestHeaders().getFirst("X-Lap-Timestamp");
            String sig = exchange.getRequestHeaders().getFirst("X-Lap-Signature");
            String service = exchange.getRequestHeaders().getFirst("X-Lap-Service");
            assertNotNull(ts, "时间戳头必须在");
            assertNotNull(sig, "签名头必须在");
            assertEquals("agent", service, "服务自报身份必须是 agent");
            // 用收到的 body 验签 —— 与 chat 侧 InternalAuthFilter 同一算式
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            assertTrue(sig.startsWith("sha256="), "签名带方案前缀");
            byte[] out = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        List<MessageView> result = world.messages("c1");
        assertNotNull(result);
        assertEquals(1, hits.get(), "请求确实发到了桩");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
