package com.luxera.agentserver.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.client.ClientConversation;
import com.luxera.companion.contracts.client.ClientMessage;
import com.luxera.companion.contracts.client.ContactProfile;
import com.luxera.companion.contracts.client.ConversationNotificationSetting;
import com.luxera.companion.contracts.client.MessagePage;
import com.luxera.companion.contracts.client.SendResult;
import com.luxera.companion.world.application.chat.ChatPlatformGateway;
import com.luxera.companion.world.application.chat.ChatSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpChatPlatformGateway} 的 REST 半边 —— 对着一个照着 {@code /api/client/**}
 * 真实形状搭的桩跑一遍。
 *
 * <h2>为什么这些断言值得写</h2>
 *
 * <p>因为这个类里每一处"世界要的"与"客户端面能给的"对不上的地方, 我都做了一<b>次</b>
 * 决定(抛 ? 降级 ? 丢掉 ? 补一次读 ?), 而每一个决定的错法都是<b>静默</b>的:
 * 把 {@code APPLICATION_CARD} 发成普通文本、把空集合当成"全读了"、把 pinned 顺手置为
 * false —— 这三件事都不会报错, 只会让数据与"她以为发生过的事"不一致。
 * 断言这些东西, 是让它们从"我读过代码"变成"它被跑过"。
 *
 * <h2>桩用 JDK {@code HttpServer}</h2>
 *
 * <p>与 {@code HttpAdapterSemanticsTest} 同一先例: 单测不该依赖一个真的 chat 进程,
 * 也不该为一个测试引 MockWebServer。这里额外需要的只有一件事 ——
 * <b>看得见请求</b>(方法、路径、查询串、请求头、请求体), 因为本类有一半的行为
 * 体现在"它发了什么"而不是"它返回了什么"上。
 *
 * <h2>这个测试<b>不</b>覆盖什么</h2>
 *
 * <p>WebSocket 那一半(连接、READY、补发、ACK、重连)不在这里 —— 那要一个真的 WS 服务端。
 * 本类里 {@code connect}/{@code onSignal}/补发游标的行为只能在那条路上验,
 * 所以别把"这个文件绿了"读成"整个类都验过了"。
 */
class HttpChatPlatformGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    /** 定住的墙上时钟 —— "签发时刻"必须可断言, 否则那条断言只是在读系统时钟。 */
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private HttpServer stub;
    private String base;
    private HttpChatPlatformGateway gateway;

    /** 桩收到过的请求 —— 见类注释: 本类一半的行为在"它发了什么"上。 */
    private final List<Seen> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.start();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
        gateway = new HttpChatPlatformGateway(base, MAPPER, () -> NOW,
                2000, 0, 1000, 30_000);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        stub.stop(0);
    }

    // ═══════════════════════ ① 登录 ═══════════════════════

    /**
     * 登录把钥匙放在 {@code X-Api-Key} 上, 并且<b>核对</b>平台回答的账号。
     *
     * <p>为什么核对是必须的: 平台不认请求体里的账号(那个字段根本不存在), 它从钥匙解出账号。
     * 所以"配置里把 A 的钥匙配给了 B"这件事, 平台侧完全看不出错 —— 只有这一层能看出来。
     */
    @Test
    void loginSendsTheKeyAndChecksTheAccountThePlatformAnswers() {
        stubJson("/api/client/login", 200, chatSession("agent_a", "tok-1",
                LocalDateTime.of(2026, 9, 20, 10, 0)));

        ChatSession session = gateway.login(new ChatPlatformGateway.LoginRequest(
                "agent_a", "sap_secret", "device"));

        Seen call = seen.get(0);
        assertEquals("POST", call.method());
        assertEquals("sap_secret", call.header("X-Api-Key"),
                "Agent 路径靠接入钥匙登录 —— 平台从钥匙本身解出账号, 请求体里没有账号可填");
        assertEquals("agent_a", session.accountId());
        assertEquals("tok-1", session.token());
        assertEquals(NOW, session.issuedAt(), "签发时刻取'我们拿到它的这一刻'");
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0)
                        .atZone(ZoneId.systemDefault()).toInstant(), session.expiresAt(),
                "过期时刻必须按平台的时间口径(Asia/Shanghai 的 LocalDateTime)换算过来");
    }

    /**
     * 平台说是别人 → 抛。这是本类唯一一处能发现"钥匙配错账号"的地方。
     *
     * <p>放过去的后果不是"读不到", 而是<b>用别人的身份读到了</b> —— 平台看不出这是错的,
     * 因为从它的角度看一切合法。
     */
    @Test
    void loginRefusesWhenTheKeyBelongsToAnotherAccount() {
        stubJson("/api/client/login", 200, chatSession("agent_OTHER", "tok-1",
                LocalDateTime.of(2026, 9, 20, 10, 0)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_secret", "d")));
        assertTrue(e.getMessage().contains("agent_OTHER") && e.getMessage().contains("agent_a"),
                "报错必须把两个账号都写出来, 否则排查的人不知道该去改哪一处配置: " + e.getMessage());
    }

    /**
     * Agent 路径没有有效期 → 抛。
     *
     * <p>真人路径的 {@code expiresAt} 确实可能为空(那个值是从令牌里解出来的), 但 Agent 路径
     * 由 {@code ClientSessionService.loginAsAgent} 显式 {@code plusNanos} 出来, 恒非空。
     * 为空意味着对面换了实现 —— 而这会让"她的会话还有多久"变成一个不可知的问题。
     */
    @Test
    void loginRefusesASessionWithoutExpiry() {
        stubJson("/api/client/login", 200,
                "{\"token\":\"tok-1\",\"accountId\":\"agent_a\",\"agentId\":null,\"expiresAt\":null}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_secret", "d")));
        assertTrue(e.getMessage().contains("有效期"), e.getMessage());
    }

    /**
     * 平台算出的过期时刻落在签发时刻之前 → 抛, 而且报错里要出现<b>时区</b>两个字。
     *
     * <p>这条不是在防平台 —— 它是在防<b>换算</b>。平台发的是 Asia/Shanghai 的
     * {@code LocalDateTime}, 而这里用 {@code ZoneId.systemDefault()} 换算。两边一致时是对的
     * (本机与两个 systemd 单元今天都是 Asia/Shanghai), 而一个 UTC 的容器会让偏差变成 8 小时。
     * 不写这条判断的话, 抛出的是 {@code ChatSession} 那句"生下来就过期的会话" ——
     * 信息正确, 但没有一个字指向真正的原因。
     */
    @Test
    void loginExplainsATimezoneMismatchInsteadOfBlamingThePlatform() {
        stubJson("/api/client/login", 200, chatSession("agent_a", "tok-1",
                LocalDateTime.of(2020, 1, 1, 0, 0)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_secret", "d")));
        assertTrue(e.getMessage().contains("时区"),
                "报错必须把时区列为原因, 否则排查要从'平台是不是坏了'开始: " + e.getMessage());
    }

    /**
     * ★ 只登录、<b>不连</b>长连接, REST 就该能用。
     *
     * <p>这条锁的是一个很容易被写错、而错了很难查的顺序依赖。如果令牌只在
     * {@code connect} 里被记下, 那么每一个 REST 方法的先决条件就变成了"长连接必须已建立",
     * 而漏掉它的报错是一句笼统的"还没登录聊天平台" —— 排查的人会去查钥匙、查令牌、查平台,
     * 而真正的原因只是调用顺序。
     *
     * <p>它在真实场景里也确实必要: 平台重启期间、重连退避期间, 长连接可能连着好几次都建不起来,
     * 而"她能不能看到有人找过她"不该由那条推送通道的运气决定。
     */
    @Test
    void loginAloneIsEnoughToUseTheRestApi() {
        stubJson("/api/client/login", 200, chatSession("agent_a", "tok-1",
                LocalDateTime.of(2026, 9, 20, 10, 0)));
        stubJson("/api/client/conversations", 200, "[]");

        gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_secret", "device"));

        // 刻意<b>不</b>调 gateway.connect(...)
        assertEquals(0, gateway.listConversations(null).conversations().size(),
                "登录之后 REST 就该可用 —— 否则每个方法的先决条件都变成了'长连接已建立'");
        assertFalse(gateway.connected(), "也确认一下这个用例真的没有连上长连接");
    }

    // ═══════════════════════ ② 会话列表 ═══════════════════════

    /** 关键词走 {@code ?q=}; 未读数与置顶免打扰原样过来; 时间是换算过的 Instant。 */
    @Test
    void listConversationsCarriesTheKeywordAndTheUnreadCount() throws Exception {
        signIn();
        stubJson("/api/client/conversations?q=" + java.net.URLEncoder.encode("小满", "UTF-8"),
                200, MAPPER.writeValueAsString(List.of(
                        new ClientConversation("conv-1", "acc-1", 3,
                                LocalDateTime.of(2026, 9, 19, 12, 0), true, false))));

        ChatPlatformGateway.ConversationPage page = gateway.listConversations(
                new ChatPlatformGateway.ConversationQuery("小满", true, null, 50));

        assertEquals("q=%E5%B0%8F%E6%BB%A1", seen.get(0).query(),
                "关键词必须编码后放进 q —— 中文搜索词直接拼进 URL 会让请求行不合法");
        assertEquals(1, page.conversations().size());
        ChatPlatformGateway.ConversationSummary row = page.conversations().get(0);
        assertEquals("acc-1", row.accountId(), "列表的主键是对方账号 id, 不是会话 id");
        assertEquals(3, row.unreadCount());
        assertTrue(row.pinned());
        assertFalse(row.muted());
        assertEquals(LocalDateTime.of(2026, 9, 19, 12, 0)
                        .atZone(ZoneId.systemDefault()).toInstant(), row.lastActivityAt());
    }

    /**
     * ★ {@code lastActivityAt} 为 null 时<b>不能抛</b>。
     *
     * <p>契约的 javadoc 写着"没说过话时为 null", 也就是说这是它的正常状态之一。
     * 而本类里有一个对空的时刻会抛的转换(它服务于"必须有值"的那些字段) ——
     * 用错这里的话, 现象是"只要有一个还没说过话的会话, 整个列表接口就炸",
     * 而那正好是新账号最常见的状态。
     */
    @Test
    void listConversationsToleratesAConversationThatNeverSaidAnything() throws Exception {
        signIn();
        stubJson("/api/client/conversations", 200, MAPPER.writeValueAsString(List.of(
                new ClientConversation("conv-1", "acc-1", 0, null, false, false))));

        ChatPlatformGateway.ConversationPage page = gateway.listConversations(null);

        assertEquals(1, page.conversations().size());
        assertNull(page.conversations().get(0).lastActivityAt(),
                "从没说过话的会话, 最后活动时刻就是没有 —— 不是 1970, 也不是抛异常");
    }

    /** 免打扰的会话在"不要免打扰的"查询里被这一层滤掉 —— 平台没有这个参数。 */
    @Test
    void listConversationsFiltersMutedOnesOnlyWhenAsked() throws Exception {
        signIn();
        stubJson("/api/client/conversations", 200, MAPPER.writeValueAsString(List.of(
                new ClientConversation("conv-1", "acc-1", 3,
                        LocalDateTime.of(2026, 9, 19, 12, 0), false, true),
                new ClientConversation("conv-2", "acc-2", 1,
                        LocalDateTime.of(2026, 9, 19, 13, 0), false, false))));

        assertEquals(1, gateway.listConversations(
                        new ChatPlatformGateway.ConversationQuery(null, false, null, 50))
                .conversations().size());
        assertEquals(2, gateway.listConversations(
                        new ChatPlatformGateway.ConversationQuery(null, true, null, 50))
                .conversations().size(), "默认要包含免打扰的: 真人看得见自己静音的会话");
    }

    /**
     * 传游标 → 抛。
     *
     * <p>不能忽略: 忽略的表现是"翻页的调用方第二页拿到空, 于是它认为没有更多会话" ——
     * 一个永远答"没有更多"的分页, 比一个明说做不到的异常危险得多。
     */
    @Test
    void listConversationsRefusesACursorInsteadOfIgnoringIt() {
        assertThrows(UnsupportedOperationException.class, () -> gateway.listConversations(
                new ChatPlatformGateway.ConversationQuery(null, true, "cursor-x", 50)));
        assertEquals(0, seen.size(), "它应当在发请求之前就拒绝, 而不是发一个注定被误解的请求");
    }

    // ═══════════════════════ ③ 读消息 ═══════════════════════

    /** 正文从这条路进来, 且只从这条路; limit 与 cursor 都进查询串。 */
    @Test
    void readMessagesCarriesLimitAndCursorAndReturnsContent() throws Exception {
        signIn();
        stubJson("/api/client/conversations/acc-1/messages?limit=20&cursor=cur-9", 200,
                MAPPER.writeValueAsString(new MessagePage(List.of(
                        new ClientMessage("m-1", "acc-1", "TEXT", "晚上一起吃饭吗",
                                LocalDateTime.of(2026, 9, 19, 12, 30), "DELIVERED")),
                        "cur-8", true)));

        ChatPlatformGateway.MessagePage page = gateway.readMessages("acc-1",
                new ChatPlatformGateway.MessageCursor("cur-9"), 20);

        assertEquals("/api/client/conversations/acc-1/messages", seen.get(0).path());
        assertEquals("limit=20&cursor=cur-9", seen.get(0).query());
        assertEquals("晚上一起吃饭吗", page.messages().get(0).content(),
                "正文只在这里出现 —— 这是 §9 验收标准 E 的那个入口");
        assertEquals("cur-8", page.nextCursor());
        assertTrue(page.hasMore());
    }

    /** 不传游标 = 最新一页, 查询串里<b>不该</b>出现 cursor。 */
    @Test
    void readMessagesWithoutACursorAsksForTheNewestPage() throws Exception {
        signIn();
        stubJson("/api/client/conversations/acc-1/messages?limit=20", 200,
                MAPPER.writeValueAsString(new MessagePage(List.of(), null, false)));

        gateway.readMessages("acc-1", null, 20);

        assertEquals("limit=20", seen.get(0).query());
    }

    // ═══════════════════════ ④ 发送 ═══════════════════════

    /**
     * 只有 {@code TEXT} 发得出去; 别的种类<b>在发请求之前</b>就抛。
     *
     * <p>关键在于断言"一个请求都没发" —— 如果实现是"发出去再报错", 那张卡片已经落库了。
     */
    @Test
    void sendMessageRefusesNonTextKindsBeforeSendingAnything() {
        assertThrows(UnsupportedOperationException.class, () ->
                gateway.sendMessage(new ChatPlatformGateway.SendMessageCommand(
                        "acc-1", ChatPlatformGateway.MessageKinds.APPLICATION_CARD,
                        "{\"card\":1}", "key-1")));
        assertEquals(0, seen.size(),
                "不允许的种类必须在发请求之前就拒绝 —— 发出去再报错的话, 卡片已经落库了");
    }

    /**
     * 发 {@code TEXT} 时请求体里只有 content 与 idempotencyKey, <b>没有 kind</b>。
     *
     * <p>这不是遗漏, 是客户端面的设计(见 {@code ClientApiController.SendBody} 的注释):
     * 那个面含真人浏览器, 开放 {@code SYSTEM} 等于让任何客户端伪造平台通告。
     * 断言"没发 kind"是为了在有人"顺手补上"时立刻红。
     */
    @Test
    void sendMessageSendsContentAndKeyButNeverAKind() throws Exception {
        signIn();
        stubJson("/api/client/conversations/acc-1/messages", 201,
                MAPPER.writeValueAsString(new SendResult("m-9",
                        LocalDateTime.of(2026, 9, 19, 14, 0))));

        ChatPlatformGateway.SendResult result = gateway.sendMessage(
                new ChatPlatformGateway.SendMessageCommand("acc-1",
                        ChatPlatformGateway.MessageKinds.TEXT, "在的", "key-7"));

        String body = seen.get(0).body();
        assertEquals("在的", MAPPER.readTree(body).path("content").asText());
        assertEquals("key-7", MAPPER.readTree(body).path("idempotencyKey").asText());
        assertFalse(MAPPER.readTree(body).has("kind"),
                "kind 不该被发出去: 客户端面刻意没有这个字段, 补上它等于让 Agent 能伪造平台通告");
        assertEquals("m-9", result.messageId());
        assertEquals(LocalDateTime.of(2026, 9, 19, 14, 0)
                .atZone(ZoneId.systemDefault()).toInstant(), result.sentAt());
    }

    // ═══════════════════════ ⑤ 已读 ═══════════════════════

    /**
     * ★ 空集合 → 抛, 而且一个请求都不发。
     *
     * <p>这是本类里最危险的一处语义反转: 平台上"不带 lastMessageId"= <b>全读了</b>
     * ({@code ReadBody} 的注释原文), 而世界侧的空集合 = "一条都没读"。
     * 传过去会把"她什么都没看"记成"她全看完了", 而平台那次写入<b>不可撤销</b>
     * (未读归零之后再也回不去)。
     */
    @Test
    void markReadRefusesAnEmptySetBecauseThatMeansMarkEverythingReadUpstream() {
        assertThrows(IllegalArgumentException.class, () -> gateway.markRead("acc-1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> gateway.markRead("acc-1", null));
        assertEquals(0, seen.size(),
                "空集合绝不能变成一个'不带 lastMessageId'的请求 —— 那个请求的含义正好相反");
    }

    /** 非空集合取首位 —— 见 {@code markRead} 的 javadoc: 列表是最新在前, 首位就是那一条。 */
    @Test
    void markReadSendsTheFirstIdAsTheCursor() throws Exception {
        signIn();
        stubJson("/api/client/conversations/acc-1/read", 200, "");

        gateway.markRead("acc-1", List.of("m-newest", "m-older"));

        assertEquals("m-newest", MAPPER.readTree(seen.get(0).body()).path("lastMessageId").asText(),
                "取列表首位: 调用方的列表来自 readMessages, 而那个顺序是最新在前");
    }

    // ═══════════════════════ ⑥ 免打扰 ═══════════════════════

    /**
     * ★ 设免打扰<b>不能</b>顺手把置顶取消掉。
     *
     * <p>客户端面的 {@code PUT} 是整体替换两个开关。而世界侧这个方法只有 {@code muted}
     * 一个入参 —— 所以实现必须先读一次现状, 把 pinned 原样带上。不这么做的话,
     * "把她免打扰了"会同时把用户自己置顶的会话从顶上拿下来, 而返回体里看不出发生过。
     */
    @Test
    void setNotificationPreservesPinnedInsteadOfSilentlyUnpinning() throws Exception {
        signIn();
        stubJson("/api/client/conversations", 200, MAPPER.writeValueAsString(List.of(
                new ClientConversation("conv-1", "acc-1", 0,
                        LocalDateTime.of(2026, 9, 19, 12, 0), true, false))));
        stubJson("/api/client/conversations/acc-1/notification", 200,
                MAPPER.writeValueAsString(new ConversationNotificationSetting(
                        "conv-1", true, true, LocalDateTime.of(2026, 9, 19, 15, 0))));

        ChatPlatformGateway.ConversationNotificationSetting setting =
                gateway.setNotification("acc-1", true);

        assertEquals(2, seen.size(), "先 GET 现状再 PUT —— 多一次往返换掉一整类'静默改动用户设置'");
        assertEquals("GET", seen.get(0).method());
        String put = seen.get(1).body();
        assertTrue(MAPPER.readTree(put).path("pinned").asBoolean(),
                "pinned 必须被原样带上。PUT 是整体替换, 而漏掉它等于把用户置顶的会话取消置顶");
        assertTrue(MAPPER.readTree(put).path("muted").asBoolean());
        assertTrue(setting.muted());
        assertTrue(setting.pinned());
    }

    /**
     * 置顶为 false 的会话, PUT 里也必须是 false —— 也就是"保住现状", 而不是"恒为 true"。
     *
     * <p>与上一条互补: 上一条防"漏掉 pinned", 这一条防"把 pinned 硬写成 true"
     * (那会让每个被设过免打扰的会话都被悄悄置顶, 同样是一次用户设置被改掉)。
     */
    @Test
    void setNotificationKeepsPinnedFalseWhenItWasFalse() throws Exception {
        signIn();
        stubJson("/api/client/conversations", 200, MAPPER.writeValueAsString(List.of(
                new ClientConversation("conv-1", "acc-1", 0, null, false, false))));
        stubJson("/api/client/conversations/acc-1/notification", 200,
                MAPPER.writeValueAsString(new ConversationNotificationSetting(
                        "conv-1", true, false, LocalDateTime.of(2026, 9, 19, 15, 0))));

        gateway.setNotification("acc-1", true);

        assertFalse(MAPPER.readTree(seen.get(1).body()).path("pinned").asBoolean(),
                "现状是没置顶, 那就保持没置顶 —— 硬写成 true 也是一次静默的用户设置改动");
    }

    // ═══════════════════════ ⑦ 资料与错误体 ═══════════════════════

    /**
     * 404 → {@code Optional.empty()}("查无此人"), 而 200 带 null 名字 → 一个资料对象
     * ("平台没给名字")。两者是不同的事, 而后者对 Agent 是<b>常态</b>。
     */
    @Test
    void contactProfileDistinguishesNobodyFromSomebodyWithoutAName() throws Exception {
        signIn();
        stubJson("/api/client/contacts/acc-none", 404,
                "{\"error\":\"没有这个账号的会话\"}");
        assertTrue(gateway.contactProfile("acc-none").isEmpty(),
                "404 = 查无此人, 不是'查询失败'");

        stubJson("/api/client/contacts/acc-1", 200, MAPPER.writeValueAsString(
                new ContactProfile("acc-1", null, null)));
        Optional<ChatPlatformGateway.ContactProfile> found = gateway.contactProfile("acc-1");
        assertTrue(found.isPresent(), "有这个人, 只是平台没给名字 —— 对 Agent 这才是常态");
        assertFalse(found.get().named(), "'这个人叫什么'是它自己聊出来的, 平台只给 id");
    }

    /**
     * 平台的错误体({@code {error, code?, hint?}})要被读成人话, 而不是一串原始 JSON。
     *
     * <p>因为这条信息最终会出现在某个 {@code chat.*} 能力的失败回执里, 而读它的是
     * 她自己(或诊断面板)。一个把 {@code {"error":"...","code":123}} 原样吐出来的报错
     * 等于没报。
     */
    @Test
    void aPlatformErrorBecomesAReadableMessage() {
        signIn();
        stubJson("/api/client/conversations/acc-1/messages?limit=20", 404,
                "{\"error\":\"没有与账号 acc-1 的会话\",\"code\":\"NO_CONVERSATION\","
                        + "\"hint\":\"先让对方发一条消息\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                gateway.readMessages("acc-1", null, 20));
        assertTrue(e.getMessage().contains("没有与账号 acc-1 的会话"), e.getMessage());
        assertTrue(e.getMessage().contains("先让对方发一条消息"),
                "hint 是平台给出的可操作建议, 丢掉它等于把一个能自查的失败变成要翻代码的失败: "
                        + e.getMessage());
    }

    /**
     * 没登录就用带令牌的端点 → 抛, 而且<b>不发</b>请求。
     *
     * <p>发出去的话拿到的是 401, 而那会被上面那层渲染成"平台拒绝了我" ——
     * 一个与真正原因(本进程还没登录)毫无关系的说法。
     */
    @Test
    void usingTheApiBeforeLoginFailsLocallyInsteadOfSendingAnAnonymousRequest() {
        assertThrows(IllegalStateException.class, () -> gateway.listConversations(null));
        assertEquals(0, seen.size(), "没登录就不该发请求: 401 会被误读成'平台拒绝了我'");
    }


    /**
     * 夹具: 先登录, 再把桩上的记录清空。
     *
     * <p>清空是必要的: 这一整个文件断言的东西大多是"它发了什么", 而登录本身也是一个请求 ——
     * 留着它会让每一条 {@code seen.get(0)} 都指到登录上去, 于是所有索引都得 +1,
     * 而那种 +1 在一次调整之后就会悄悄错位。登录是<b>夹具</b>, 不是被测行为:
     * 它自己的行为由上面那四条用例管。
     */
    private void signIn() {
        stubJson("/api/client/login", 200, chatSession("agent_a", "tok-1",
                LocalDateTime.of(2026, 9, 20, 10, 0)));
        gateway.login(new ChatPlatformGateway.LoginRequest("agent_a", "sap_secret", "device"));
        seen.clear();
    }

    // ═══════════════════════ 桩 ═══════════════════════

    /** 一个被收到过的请求 —— 见类注释: 本类一半的行为在"它发了什么"上。 */
    private record Seen(String method, String path, String query, String body,
                        java.util.Map<String, String> headers) {

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * 线上的<b>原始</b>查询串。
     *
     * <p>必须用 {@code getRawQuery()} 而不是 {@code getQuery()}: 后者会把
     * {@code %E5%B0%8F%E6%BB%A1} 解码回「小满」, 而这个文件里那条关于中文搜索词的断言
     * 要验的恰恰是"它在线上是编码过的"。用解码过的值去断言, 等于把这条断言变成
     * 一句永远为真的话。
     */
    private static String rawQuery(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? "" : raw;
    }

    private void stubJson(String pathWithQuery, int status, String body) {
        int q = pathWithQuery.indexOf('?');
        String path = q < 0 ? pathWithQuery : pathWithQuery.substring(0, q);
        String expectedQuery = q < 0 ? "" : pathWithQuery.substring(q + 1);

        stub.createContext(path, exchange -> {
            String actualQuery = rawQuery(exchange);
            if (!expectedQuery.equals(actualQuery)) {
                // 查询串对不上说明"它发了什么"与断言预期不同 —— 用 500 让调用方炸出来,
                // 而不是让桩悄悄返回一份正常响应、把这条差异藏过去
                respond(exchange, 500, "{\"error\":\"桩: 查询串不符, 期望 ["
                        + expectedQuery + "] 实际 [" + actualQuery + "]\"}");
                return;
            }
            record(exchange);
            respond(exchange, status, body);
        });
    }

    private void record(HttpExchange exchange) throws IOException {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) ->
                headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
        String body = exchange.getRequestBody() == null ? ""
                : new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                rawQuery(exchange), body, headers));
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (out.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, out.length);
        try (var os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static String chatSession(String accountId, String token, LocalDateTime expiresAt) {
        return "{\"token\":\"" + token + "\",\"accountId\":\"" + accountId
                + "\",\"agentId\":\"agent-1\",\"expiresAt\":\"" + expiresAt + "\"}";
    }
}
