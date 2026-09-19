package com.luxera.agentserver.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.contracts.client.NotificationSignal;
import com.luxera.companion.world.application.chat.ChatPlatformGateway;
import com.luxera.companion.world.application.chat.ChatSession;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpChatPlatformGateway} 的 WebSocket 半边 —— 对着一个<b>真的</b> WS 服务端跑。
 *
 * <h2>为什么这一半非要在真端口上跑一次</h2>
 *
 * <p>因为这个类里有一处错误<b>读代码读不出来、跑起来也不报</b>: 端点那一侧到底哪个方法是
 * 投递点。{@code StreamEndpoint} 是{@link javax.websocket.Endpoint} 的<b>子类实例</b> ——
 * 也就是<b>程序化端点</b>, 而 javax.websocket 1.1 的 {@code Endpoint} 基类里压根
 * <b>没有消息方法</b>(只有 {@code onOpen}/{@code onClose}/{@code onError}),
 * 于是"覆写基类的 {@code onMessage}"这条路上没有东西可覆写。注解也不行:
 * {@code @OnMessage} 是给 POJO 端点用的, 标在这么一个实例上<b>实测不生效</b> ——
 * 编译通过、连接建立、{@code onOpen} 照常、心跳照常、日志干净, 而那个方法一次都没被调到,
 * 一条入站帧都到不了。规范给程序化端点的路只有一条: 在 {@code onOpen} 里
 * {@code session.addMessageHandler(...)}。现象就是"她连上了、READY 也没有、什么都不来"。
 *
 * <p>这个坑是真踩过的 —— 而当时所有"直接调内部方法"的单元测试全绿。所以本文件的第一条
 * 能证明入站的断言, 用的是"一条信号有没有进监听器", 而不是"连接是不是建立了"。
 *
 * <p>另外三件事也只有在线路上才成立:
 * <ul>
 *   <li><b>令牌真的走查询串</b> —— JSR-356 的握手发不了自定义头, 凭据只能放 URL 上。
 *       那是一件有安全含义的事(令牌会进 access log), 值得被断言而不是被注释;</li>
 *   <li><b>确认收到发生在投递<i>之后</i></b> —— 顺序错了的话, "她收到了但没能处理"会被
 *       记成"处理完了", 而那次铃就白响了;</li>
 *   <li><b>读不懂的信号不被确认</b> —— 契约 1.0.1 的平台发来的信号没有 {@code unreadCount},
 *       本类必须拒绝它<b>并且不确认</b>, 这样对面升级之后平台会把这条补回来。</li>
 * </ul>
 *
 * <h2>服务端怎么搭的</h2>
 *
 * <p>内嵌 Tomcat + {@code WsSci}, <b>不起 Spring 上下文</b>: 起容器只为了一条 WS 路径,
 * 而一个 Spring Boot 上下文会顺手把 JPA 与数据源一起拉进来 —— 那些东西这个测试一件都用不到,
 * 却会让它在别的机器上因为连不上数据库而失败。端点的 JSON 用契约里的
 * {@link ClientStreamFrame} 拼 —— 桩没有自己发明一套信封, 那正是"契约是两边共同语言"的意思。
 *
 * <h2>这个文件<b>不</b>覆盖什么</h2>
 *
 * <p>REST 那一半在 {@code HttpChatPlatformGatewayTest} 里。两个文件加起来才是
 * "这个类被验过了"。
 */
class WsChatPlatformGatewayTest {

    /** 定住的墙上时刻 —— 见 {@link #signal} 里关于时区的那段。 */
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private WsServer server;
    private String base;
    private HttpChatPlatformGateway gateway;
    private final List<ChatPlatformGateway.NotificationSignal> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        FakeChatStream.reset();
        server = new WsServer();
        base = "http://127.0.0.1:" + server.port;
        // 重连次数给 0: 这个文件里大多数用例只关心"一条连接上发生了什么", 而重连的退避
        // 会让失败用例多等几秒才报错。要验重连方向的那两条自己调上去
        gateway = newGateway(0, 60_000);
        gateway.onSignal(received::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        gateway.close();
        server.close();
    }

    /** 临时诊断: 自己用裸 socket 说一次握手, 看服务端到底回了什么。 */
    @Test
    void rawHandshakeProbe() throws Exception {
        // 已经查清: 缺默认 servlet 时 Mapper 在过滤器之前就回 404。留着它是因为它比
        // JSR-356 客户端更能说清"到底是握手没成, 还是握手成了而客户端没处理"
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.port)) {
            java.io.OutputStream out = s.getOutputStream();
            out.write(("GET /api/client/stream?token=tok-real HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + server.port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            assertNotNull(statusLine, "握手没有任何回应 —— 连接被直接关掉了");
            assertTrue(statusLine.startsWith("HTTP/1.1 101"),
                    "服务端拒绝了这次升级: " + statusLine
                            + " —— 101 之前的一切(端点注册、令牌、路径)都白搭");
        }
    }

    // ═══════════════════════ 握手 ═══════════════════════

    /**
     * 握手真的完成了, 而且服务端确实走上了 {@code onOpen} 并发了 READY。
     *
     * <p>它验的是<b>升级那一步</b>(WsFilter 认出了这次升级、端点映射匹配上了) ——
     * 也就是"101 之前的一切"。它<b>不</b>证明客户端收到了 READY: 客户端那一侧收到 READY
     * 只会打一行日志, 没有任何可断言的状态(<b>入站投递</b>的证据在下面那条
     * {@code aSignalIsDeliveredToTheListenerAndOnlyThenAcknowledged} 里,
     * 那条才是"她是不是聋了"的判据)。名字与断言对齐, 免得下一个人把这条绿读成
     * "整条入站路径都验过了"。
     */
    @Test
    void theHandshakeCompletesAndTheServerSendsReady() {
        gateway.connect(signIn());

        assertTrue(await(() -> FakeChatStream.readySent.get() == 1),
                "服务端没有走到 onOpen —— 升级多半没成(而不是'客户端没收到')");
        assertTrue(gateway.connected(),
                "这是 socket 层面的活着, 与'平台确认过这个会话'不是一回事(READY 没到它也是 true)");
    }

    /**
     * 令牌与补发游标都走查询串 —— 没得选, 但代价要知道。
     *
     * <p>JSR-356 的握手<b>不接受</b>自定义头(浏览器侧的 WebSocket 构造函数也不接受),
     * 所以服务端只能从 URL 上取凭据。代价是令牌会出现在 access log 里 —— 那是部署时要知道的
     * 事(nginx 侧的日志), 这里解决不了; 但"它确实在 URL 上"必须被钉住: 换个位置服务端就
     * 认不出她, 而现象是每一条连接都被判成 SESSION_EXPIRED。
     */
    @Test
    void theHandshakeCarriesTheTokenAndTheReplayCursorOnTheQueryString() {
        ChatSession session = signIn();
        gateway.connect(session);

        assertTrue(await(() -> !FakeChatStream.live.isEmpty()));
        FakeChatStream stream = FakeChatStream.live.get(0);
        assertEquals(session.token(), stream.token,
                "令牌必须从 ?token= 过来 —— 握手没有自定义头可用");
        assertEquals("0", stream.lastAck,
                "第一次连接的补发游标是 0, 也就是'一条都没确认过'");
    }

    /**
     * 令牌不对时服务端发一条 SESSION_EXPIRED 再关 —— 而客户端<b>不</b>重连。
     *
     * <p>这个区分是有用的: 拿一个已经失效的令牌一直重试, 只会得到同样一条回答, 而它的表现是
     * "日志里每 60 秒出现一次一模一样的 WARN" —— 真正该做的事(重新登录)反而没有任何东西在提示。
     */
    @Test
    void aRejectedSessionIsNotRetried() throws Exception {
        // 重连次数给足, 这样"没有重连"只可能是代码的选择, 不是次数用完了
        gateway.close();
        gateway = newGateway(5, 60_000);
        received.clear();

        gateway.connect(ChatSession.of("agent_a", "BAD.TOKEN", NOW, NOW.plusSeconds(3600)));
        assertTrue(await(() -> FakeChatStream.rejected.get() == 1),
                "服务端必须先解释一句再关, 而不是静默断连 —— "
                        + "静默断连会让客户端分不出'令牌过期'与'服务端挂了'");

        int connectionsAfterRejection = FakeChatStream.connections.get();
        Thread.sleep(1200);   // 足够跑过好几轮 200ms 退避
        assertEquals(connectionsAfterRejection, FakeChatStream.connections.get(),
                "被平台明确拒绝之后不该再重连 —— 拿一个死令牌重试只会得到同样一条回答");
    }

    // ═══════════════════════ 信号: 投递、确认、补发 ═══════════════════════

    /**
     * 一条信号: 先进监听器, <b>然后</b>才被确认; 而确认过的序号会成为下次连接带的游标。
     */
    @Test
    void aSignalIsDeliveredToTheListenerAndOnlyThenAcknowledged() {
        ChatSession session = signIn();
        gateway.connect(session);
        assertTrue(await(() -> !FakeChatStream.live.isEmpty()));

        FakeChatStream.live.get(0).push(signal(7L, "conv-1", 3));

        assertTrue(await(() -> received.size() == 1), "平台推来的信号必须进监听器");
        ChatPlatformGateway.NotificationSignal got = received.get(0);
        assertEquals("conv-1", got.conversationRef());
        assertEquals(3, got.unreadCount());
        assertEquals(NOW, got.occurredAt(),
                "时刻要按平台口径换算 —— 平台发的是 LocalDateTime, 而世界侧要 Instant");

        assertTrue(await(() -> FakeChatStream.acks.contains(7L)),
                "投递成功之后必须确认收到 —— 不确认的话, 平台每次重连都会把这条再发一遍");

        // 再连一次: 游标必须是刚确认过的那个, 否则断线期间错过的那几声响就永远没了。
        // 断言写成"某条连接带了 7"而不是"live 里那条带了 7": 关旧连接与开新连接之间有一个
        // 两条并存的瞬间, 而那个瞬间不该让用例红
        gateway.connect(session);
        assertTrue(await(() -> FakeChatStream.live.stream()
                        .anyMatch(s -> "7".equals(s.lastAck))),
                "重连必须带上补发游标 7 —— 不带的话, 平台不会再发那些错过的信号。"
                        + "实际看到的游标: "
                        + FakeChatStream.live.stream().map(s -> s.lastAck).toList());
    }

    /**
     * ★ 没有 {@code unreadCount} 的信号: <b>不进监听器, 也不被确认</b>。
     *
     * <p>这是对面还在跑契约 1.0.1 时会发生的事, 而这个状态在本项目里真实存在 ——
     * 两个仓各自独立构建, 而部署是就地覆盖 fat jar。
     *
     * <p>"不确认"是这条用例里<b>最要紧</b>的断言: 平台的 {@code NotificationSignalLog}
     * 保留最近 256 条, 所以不确认的那条会在对面升级之后被补回来 —— 这个失败是<b>可自愈</b>的。
     * 反过来, 如果把 null 当成 0 或者干脆确认掉, 那就变成一次不可察觉的永久丢失:
     * 红点永远不亮, 而"她什么都没收到"与"确实没有新消息"在界面上完全一样。
     */
    @Test
    void aSignalWithoutUnreadCountIsRefusedAndDeliberatelyNotAcknowledged() throws Exception {
        ChatSession session = signIn();
        gateway.connect(session);
        assertTrue(await(() -> !FakeChatStream.live.isEmpty()));

        // 手写这一帧: 契约 1.0.1 的 JSON 里根本没有 unreadCount 这个键
        FakeChatStream.live.get(0).sendRaw("{\"type\":\"NOTIFICATION\",\"signal\":{"
                + "\"signalId\":11,\"conversationId\":\"conv-1\",\"fromAccountId\":\"acc-1\","
                + "\"raisedAt\":\"2026-09-19T18:00:00\"},\"lastAckSignalId\":0}");

        Thread.sleep(600);
        assertTrue(received.isEmpty(),
                "没有未读数的信号不该进她的认知 —— 把它当成 0 会让红点永远不亮");
        assertFalse(FakeChatStream.acks.contains(11L),
                "而且**不能**确认收到: 确认掉它等于永久丢掉一次'有人找过她'。"
                        + "不确认的话, 平台升级之后会把这条补回来");
    }

    /**
     * 信号没有发生时该有的时刻 → 同样拒绝、同样不确认。
     *
     * <p>理由与缺未读数不同: 一条没有时刻的铃, 在"这声铃是不是已经太旧了"({@code ageAt}
     * 那个判断)里没法被丢掉, 于是它会被当成刚发生的 —— 一个几小时前的消息会让手机现在响一下。
     */
    @Test
    void aSignalWithoutATimestampIsRefusedToo() throws Exception {
        ChatSession session = signIn();
        gateway.connect(session);
        assertTrue(await(() -> !FakeChatStream.live.isEmpty()));

        FakeChatStream.live.get(0).sendRaw("{\"type\":\"NOTIFICATION\",\"signal\":{"
                + "\"signalId\":12,\"conversationId\":\"conv-1\",\"fromAccountId\":\"acc-1\","
                + "\"unreadCount\":2},\"lastAckSignalId\":0}");

        Thread.sleep(600);
        assertTrue(received.isEmpty(), "没有时刻的铃没法被判新旧, 于是会被当成刚发生的");
        assertFalse(FakeChatStream.acks.contains(12L), "同样不确认 —— 让平台补发");
    }

    /**
     * 监听器抛异常 → 那条信号<b>不</b>被确认。
     *
     * <p>"应用没能处理它"与"应用处理了"必须分得开。合成一个的话, 一次 LLM 超时或者一个
     * 空指针就会让那次铃永远消失, 而日志里只有一行 WARN。
     */
    @Test
    void aSignalWhoseListenerThrowsIsNotAcknowledged() {
        gateway.close();
        gateway = newGateway(0, 60_000);
        gateway.onSignal(s -> {
            throw new IllegalStateException("监听器炸了");
        });

        ChatSession session = signIn();
        gateway.connect(session);
        assertTrue(await(() -> !FakeChatStream.live.isEmpty()));

        FakeChatStream.live.get(0).push(signal(21L, "conv-1", 1));

        assertTrue(await(() -> FakeChatStream.pushes.get() == 1), "服务端确实推了");
        assertFalse(FakeChatStream.acks.contains(21L),
                "监听器没处理成功就不该确认 —— 否则那次铃白响了, 而日志里只有一行 WARN");
    }

    // ═══════════════════════ 心跳 ═══════════════════════

    /**
     * 心跳真的会发出去, 而且服务端回的 PONG 不会把它弄断。
     *
     * <p>它验的是"半开连接能被发现"的唯一手段: 协议里服务端<b>从不主动发</b> PING, 它只回。
     * 所以"这条连接还活着吗"只有客户端问得出来 —— 没有心跳的话, 一条 TCP 那头已经没了的连接
     * 会永远不触发 {@code onClose}, 而 {@code connected()} 一直答"连着"(它还零调用,
     * 连个问的人都没有)。
     */
    @Test
    void theHeartbeatActuallyGoesOut() throws Exception {
        gateway.close();
        // 心跳间隔调到 300ms, 让这条用例在一秒内就能看到结论
        gateway = newGateway(0, 300);
        gateway.onSignal(received::add);

        gateway.connect(signIn());

        assertTrue(await(() -> FakeChatStream.pings.get() >= 2),
                "心跳没有发出去 —— 一条半开连接将永远不会被发现。已发出: "
                        + FakeChatStream.pings.get());
        assertTrue(gateway.connected(), "服务端回了 PONG, 连接应当仍然可用");
    }

    // ═══════════════════════ 夹具 ═══════════════════════

    private HttpChatPlatformGateway newGateway(int reconnectAttempts, long pingIntervalMillis) {
        return new HttpChatPlatformGateway(base, FakeChatStream.MAPPER, () -> NOW,
                2000, reconnectAttempts, 200, pingIntervalMillis);
    }

    /**
     * 走<b>真的</b>登录 —— 桩上有 {@code POST /api/client/login}。
     *
     * <p>不直接 {@code ChatSession.of(...)} 造一个: 那样"登录"这一步在这个文件里就没人验了,
     * 而它恰好有过一个真实的缺陷(登录不记会话, 于是每个 REST 方法都报"还没登录聊天平台")。
     * 用一个假会话会把那条路绕过去。
     */
    private ChatSession signIn() {
        return gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_ws_test", "device"));
    }

    /**
     * 一条通知信号 —— 时刻按<b>平台口径</b>写。
     *
     * <p>{@code LocalDateTime.ofInstant(NOW, systemDefault())} 与生产代码里 {@code toInstant}
     * 的换算是同一个函数, 所以这里验的是"往返一致": 平台按自己的时区发, 客户端按同一个时区收回来。
     *
     * <p>刻意<b>不</b>写死成 {@code LocalDateTime.of(2026, 9, 19, 18, 0)} —— 那等于把
     * "这台机器的默认时区是 Asia/Shanghai"写进断言里, 于是在一个 UTC 的容器里它就会红,
     * 而红的理由与它想验的东西无关。绝对时区是否一致是部署层面的事实(本机与两个 systemd 单元
     * 今天都是 Asia/Shanghai), 由 {@code login} 里那条时区预检负责在真实环境里报出来。
     */
    private static NotificationSignal signal(long id, String conversationId, int unread) {
        return new NotificationSignal(id, conversationId, "acc-1",
                LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()), unread);
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * 一个只认识 {@code /api/client/login} 与 {@code /api/client/stream} 的内嵌 Tomcat。
     */
    private static final class WsServer implements AutoCloseable {

        private final Tomcat tomcat;
        final int port;

        WsServer() throws Exception {
            tomcat = new Tomcat();
            tomcat.setBaseDir(Files.createTempDirectory("ws-test").toString());
            tomcat.setPort(0);
            tomcat.getConnector();
            Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());

            Tomcat.addServlet(ctx, "login", new LoginServlet());
            ctx.addServletMappingDecoded("/api/client/login", "login");

            // ★ 默认 servlet 必须自己补上。`Tomcat.addContext` 不装它(那是 `addWebapp` 与
            // Spring Boot 的工厂在做的活), 而缺少它的后果是**静默且具有欺骗性的**:
            // WebSocket 的路径没有对应的 servlet 映射 → Mapper 在**过滤器之前**就回了 404,
            // 于是 WsFilter 与诊断过滤器都不会被调到, 现象是"握手 404, 而容器里明明
            // 注册了端点、findMapping 也不为 null"。
            // 真实部署里 WS 路径正是落在默认 servlet 上 —— 升级由 WsFilter 抢在前面做,
            // 请求根本到不了默认 servlet。
            org.apache.catalina.Wrapper defaultServlet =
                    Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
            defaultServlet.setLoadOnStartup(1);
            ctx.addServletMappingDecoded("/", "default");

            // WsSci 才是把 ServerContainer 放进 ServletContext 的那一步 —— 少了它, 下面那个
            // getAttribute 会拿到 null, 而报错会变成"端点没注册", 与真正的原因差得很远
            ctx.addServletContainerInitializer(new WsSci(), null);
            tomcat.start();

            ServerContainer container = (ServerContainer) ctx.getServletContext()
                    .getAttribute("javax.websocket.server.ServerContainer");
            assertNotNull(container, "WsSci 没跑起来 —— ServerContainer 不在 ServletContext 里");
            container.addEndpoint(ServerEndpointConfig.Builder
                    .create(FakeChatStream.class, "/api/client/stream").build());
            this.port = tomcat.getConnector().getLocalPort();
        }

        @Override
        public void close() throws Exception {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /**
     * {@code POST /api/client/login} 的桩 —— 回的形状照契约的
     * {@code com.luxera.companion.contracts.client.ChatSession}
     * ({@code token / accountId / agentId / expiresAt})。
     *
     * <p>有效期写成一个<b>固定</b>的未来时刻, 而不是 {@code now().plusHours(1)}: 网关的时钟在
     * 这个文件里是定住的 {@code NOW}, 而"现在几点"不是一个测试该依赖的东西
     * (真按现在算的话, 这个文件在某个时段会无缘无故地红)。
     */
    private static final class LoginServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            String apiKey = request.getHeader("X-Api-Key");
            response.setContentType("application/json;charset=UTF-8");
            if (apiKey == null || apiKey.isBlank()) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"没有 X-Api-Key\",\"code\":\"NO_KEY\"}");
                return;
            }
            response.getWriter().write(FakeChatStream.MAPPER.writeValueAsString(Map.of(
                    "token", "tok-real",
                    "accountId", "agent_a",
                    "expiresAt", "2027-01-01T00:00:00")));
        }
    }

    /**
     * 服务端的桩 —— 照 {@code ClientStreamEndpoint} 的协议行为写。
     *
     * <p>状态是<b>静态</b>的, 因为 JSR-356 给每一条连接各造一个实例, 而用例要看的是"跨连接"的
     * 事实(补发游标、总共连了几次)。这不是偷懒: 生产代码里同样的跨连接状态挂在
     * {@code HttpChatPlatformGateway} 实例上, 这里挂在测试类的静态字段上 —— 两者都是
     * "比一条连接活得久"的那一层。
     */
    @ServerEndpoint("/api/client/stream")
    public static class FakeChatStream {

        static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
        static final List<FakeChatStream> live = new CopyOnWriteArrayList<>();
        static final List<Long> acks = new CopyOnWriteArrayList<>();
        static final AtomicInteger pings = new AtomicInteger();
        static final AtomicInteger connections = new AtomicInteger();
        static final AtomicInteger readySent = new AtomicInteger();
        static final AtomicInteger rejected = new AtomicInteger();
        static final AtomicInteger pushes = new AtomicInteger();

        volatile Session session;
        volatile String token;
        volatile String lastAck;

        static void reset() {
            live.clear();
            acks.clear();
            pings.set(0);
            connections.set(0);
            readySent.set(0);
            rejected.set(0);
            pushes.set(0);
        }

        @OnOpen
        public void onOpen(Session s, EndpointConfig config) {
            this.session = s;
            connections.incrementAndGet();
            Map<String, List<String>> params = s.getRequestParameterMap();
            this.token = first(params, "token");
            this.lastAck = first(params, "lastAckSignalId");

            if (token == null || token.isBlank() || token.startsWith("BAD")) {
                // 与真实端点一致: 先解释一句, 再关 —— 静默断连会让客户端分不出
                // "令牌过期"与"服务端挂了"
                sendFrame(ClientStreamFrame.sessionExpired("令牌无效或已过期"));
                rejected.incrementAndGet();
                try {
                    s.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "bad-token"));
                } catch (Exception ignored) {
                    // 关一条已经断了的连接会抛, 而我们要的结果(它不存在了)已经达到
                }
                return;
            }
            live.add(this);
            sendFrame(ClientStreamFrame.ready());
            readySent.incrementAndGet();
        }

        @OnMessage
        public void onMessage(String text) {
            String type;
            try {
                type = MAPPER.readTree(text).path("type").asText("");
            } catch (Exception e) {
                return;
            }
            if (ClientStreamFrame.TYPE_PING.equals(type)) {
                pings.incrementAndGet();
                sendFrame(ClientStreamFrame.pong());
            } else if (ClientStreamFrame.TYPE_ACK.equals(type)) {
                try {
                    acks.add(MAPPER.readTree(text).path("lastAckSignalId").asLong());
                } catch (Exception ignored) {
                    // 读不懂的确认等于没确认 —— 用例会因为"没看到确认"而红, 这正是想要的
                }
            }
        }

        @OnClose
        public void onClose(Session s) {
            live.remove(this);
        }

        /** 推一条通知 —— 走契约类型拼帧, 桩不自己发明信封。 */
        void push(NotificationSignal signal) {
            pushes.incrementAndGet();
            sendFrame(ClientStreamFrame.notification(signal));
        }

        /** 推一段<b>原始</b>报文 —— 契约 1.0.1 那种缺字段的 JSON 只能这样造出来。 */
        void sendRaw(String json) {
            pushes.incrementAndGet();
            sendText(json);
        }

        private void sendFrame(ClientStreamFrame frame) {
            try {
                sendText(MAPPER.writeValueAsString(frame));
            } catch (Exception e) {
                throw new IllegalStateException("桩推不出去: " + e, e);
            }
        }

        private void sendText(String json) {
            try {
                session.getBasicRemote().sendText(json);
            } catch (Exception e) {
                throw new IllegalStateException("桩推不出去: " + e, e);
            }
        }

        private static String first(Map<String, List<String>> params, String key) {
            List<String> values = params.get(key);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
