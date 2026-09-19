package com.luxera.agentserver.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.world.application.chat.ChatPlatformGateway;
import com.luxera.companion.world.application.chat.ChatSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * V2.2 §6.4 —— {@link ChatPlatformGateway} 的<b>唯一实现</b>: 走 chat-platform 的客户端面
 * ({@code /api/client/**} 与 {@code WS /api/client/stream})。
 *
 * <h2>为什么这是一个新包, 而不是 {@code http} 包里的第四个适配器</h2>
 *
 * <p>{@code com.luxera.agentserver.http} 里那三个适配器走的是 {@code /internal/**},
 * 签名方式是 HMAC({@code X-Lap-Timestamp} / {@code X-Lap-Signature} / {@code X-Lap-Service})
 * —— 那是<b>服务对服务</b>的身份。本类走的是 {@link ChatSession#token()} 的
 * {@code Authorization: Bearer}, 也就是 {@link ChatPlatformGateway} 类注释里那三层授权中的
 * 第 ② 层(<b>应用会话</b>)。两者不是同一个身份模型, 硬塞进同一个基类只会让 {@code open()}
 * 里出现一个"这次要不要签名"的分支 —— 而那正是层层授权开始互相冒充的地方。
 *
 * <h2>为什么"让 Agent 像真人一样用聊天软件"不是一句口号</h2>
 *
 * <p>本类调的每一个端点, 聊天平台自己的前端也在调同一份({@code ClientApiController}
 * 的类注释: 一份接口、两个调用方)。这不是巧合, 是 G2 §6.3 的要求 ——
 * <b>Agent 看到的世界必须与真人逐字相同</b>。于是这里没有一条"给 Agent 的快捷通道",
 * 也没有一条"因为我们是内部调用所以可以直读"的例外。
 *
 * <h2>三个刻意的"做不到", 每一个都宁可响也不肯静默降级</h2>
 *
 * <table border="1">
 *   <tr><th>世界侧要求</th><th>客户端面能给的</th><th>本类怎么办</th></tr>
 *   <tr><td>{@code sendMessage} 带 {@code kind}</td>
 *       <td>请求体里<b>没有</b> {@code kind}(见 {@code ClientApiController.SendBody}
 *           的注释: {@code SYSTEM} 在界面上渲染成居中系统提示, 开放它等于让任何客户端
 *           伪造平台通告)</td>
 *       <td>非 {@code TEXT} 直接抛, <b>不做降级</b>。把 {@code APPLICATION_CARD}
 *           悄悄发成一条普通文本, 会让"她发了一张卡片"这件事在数据上不存在</td></tr>
 *   <tr><td>{@code markRead} 给一串 messageId</td>
 *       <td>一个游标({@code lastMessageId}), 语义是"读到这一条为止",
 *           而<b>不传就是全读了</b></td>
 *       <td>空集合抛(它的含义与"全读了"正好相反, 传过去会把一条都没读变成全读);
 *           非空取列表首位 —— 见 {@link #markRead} 里那段关于"平台的已读是一个计数"的说明</td></tr>
 *   <tr><td>{@code setNotification} 只给 {@code muted}</td>
 *       <td>{@code PUT}, 语义是<b>整体替换</b> muted 与 pinned 两个开关。而且请求体为
 *           null 时两个开关都按 {@code false} 走({@code body != null && body.muted()})
 *           —— 也就是说"不带请求体"等于"全部关掉"</td>
 *       <td>先读一次现状把 {@code pinned} 保下来再 PUT。不这么做的话,
 *           "把她免打扰了"会顺手把用户自己置顶的会话从顶上拿下来,
 *           而返回体里看不出发生过这件事</td></tr>
 * </table>
 *
 * <h2>契约 1.0.2: 信号里的 {@code unreadCount}</h2>
 *
 * <p>世界侧的 {@code NotificationSignal} 要求一个未读数(手机的未读快照), 而契约 1.0.1
 * 的信封里没有它。本类<b>不自己数</b> —— 那会在免打扰的会话、超出补发容量的信号、
 * 平台重启这三处漏, 而漏的表现是红点偏小。所以那个字段被补进了契约(1.0.2), 由平台在
 * 消息提交之后读自己刚自增过的那一行给出来。本类只做一件事:
 * <b>{@code unreadCount} 为 null 时拒绝这条信号、并且不确认收到</b>(见
 * {@link #toWorldSignal} 与 {@link #onNotification}), 因为 null 意味着对面还在跑 1.0.1
 * —— 而把 null 当成 0 会让"她什么都没收到"看起来与"没有新消息"一模一样。
 *
 * <h2>它自己管着一条长连接 —— 这是它与那三个适配器最不一样的地方</h2>
 *
 * <p>{@code connect} 之后它必须一直活着, 断了要自己接回来, 因为"她收得到吗"这件事
 * 没有第二个地方在看 —— {@link #connected()} 在全仓<b>零调用</b>, 也就是说一条死掉的长连接
 * 除了日志之外没有任何地方会表现出来。见 {@link #scheduleReconnect} 与 {@link #startHeartbeat}。
 *
 * <h2>它<b>不</b>做什么</h2>
 * <ul>
 *   <li><b>不重试写操作。</b>发送失败就是失败, 由调用方(她)决定要不要再发一次 ——
 *       自动重发会让"她说了一遍"与"她说了两遍"在数据上分不开。
 *       幂等键能救的是<b>同一命令</b>的重试, 而不是替她做决定;</li>
 *   <li><b>不缓存正文。</b>只有 {@link #readMessages} 返回正文, 而它是返回值不是字段。
 *       类里没有任何一个地方存着消息正文;</li>
 *   <li><b>不判断能不能做。</b>第 ③ 层授权(能力)在 {@code DefaultActionFabric} 里,
 *       见 {@link ChatPlatformGateway} 类注释。</li>
 * </ul>
 *
 * <h2>类型名上的一个坑, 写给下一个读这个文件的人</h2>
 *
 * <p>本仓与契约里各有一套同名的客户端类型: 契约的是<b>线上形状</b>
 * ({@code com.luxera.companion.contracts.client.*}, 时间是 {@code LocalDateTime}),
 * 世界的是<b>她认知里的形状</b>({@link ChatPlatformGateway} 的嵌套类型, 时间是
 * {@code Instant})。本类的方法签名必须用<b>世界那一套</b>(它是接口的一部分), 所以
 * 本文件<b>不 import 契约的那几个同名类型</b> —— 一旦 import, 简单名就会指向契约那个,
 * 于是 {@code public MessagePage readMessages(…)} 编译期就报"返回类型不兼容"。
 * 契约类型在这里一律写全限定名。它们只出现在两类位置: 反序列化的目标类型,
 * 以及遍历线上返回值的时候。
 */
public final class HttpChatPlatformGateway implements ChatPlatformGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpChatPlatformGateway.class);

    /** 一次断线最多重连几次 —— 见 {@link #scheduleReconnect} 里"为什么是有限的"。 */
    public static final int DEFAULT_RECONNECT_ATTEMPTS = 12;
    /** 第一次重连前的等待。往后每一跳乘 2, 封顶 {@value #MAX_RECONNECT_DELAY_MS} ms。 */
    public static final long DEFAULT_RECONNECT_DELAY_MS = 1_000L;
    /** 重连间隔的上限。12 跳的等待合计约 5 分钟 —— 详见 {@link #reconnectDelay(long)}。 */
    public static final long MAX_RECONNECT_DELAY_MS = 60_000L;
    /**
     * 心跳间隔。协议里有 {@code PING}/{@code PONG}(见 {@code ClientStreamEndpoint}),
     * 而**服务端从不主动发 PING** —— 它只回。于是"这条连接还活着吗"只有客户端问得出来。
     */
    public static final long DEFAULT_PING_INTERVAL_MS = 30_000L;

    private final String baseUrl;
    private final String streamUrlPrefix;
    private final int timeoutMillis;
    private final int reconnectAttempts;
    private final long reconnectDelayMillis;
    private final long pingIntervalMillis;
    private final ObjectMapper mapper;
    private final Supplier<Instant> clock;

    private final List<SignalListener> listeners = new CopyOnWriteArrayList<>();
    /** 补发游标 —— 断线重连时交给平台, 它据此只补没确认过的那些。 */
    private final AtomicLong lastAckSignalId = new AtomicLong(0);
    /** 本轮断线已经连续失败几次 —— 连上就归零。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** 平台明确说过"这个令牌不能用了"。为真时<b>不重连</b> —— 拿一个死令牌重试是纯噪声。 */
    private final AtomicBoolean sessionRejected = new AtomicBoolean(false);
    /** 一条守护线程: 心跳与重连都排在它上面。 */
    private final ScheduledExecutorService link;

    private volatile ChatSession session;
    private volatile Session socket;
    /** 当前这条连接的心跳任务 —— 换连接时要把它停掉, 否则重连几次就攒下几个空转的定时任务。 */
    private volatile ScheduledFuture<?> heartbeat;
    /** 显式断开(登出/卸载)之后为真 —— 它把"断线"与"我不要了"分开。 */
    private volatile boolean shutDown = true;

    public HttpChatPlatformGateway(String baseUrl, ObjectMapper mapper, Supplier<Instant> clock) {
        this(baseUrl, mapper, clock, 5_000, DEFAULT_RECONNECT_ATTEMPTS,
                DEFAULT_RECONNECT_DELAY_MS, DEFAULT_PING_INTERVAL_MS);
    }

    public HttpChatPlatformGateway(String baseUrl, ObjectMapper mapper, Supplier<Instant> clock,
                                   int timeoutMillis, int reconnectAttempts,
                                   long reconnectDelayMillis, long pingIntervalMillis) {
        String trimmed = Objects.requireNonNull(baseUrl, "聊天平台地址不能为空").trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.streamUrlPrefix = toStreamPrefix(this.baseUrl);
        this.mapper = Objects.requireNonNull(mapper, "需要一个 ObjectMapper");
        // 墙上时钟: 本类是适配器层(server/), 不是 human/ world/ boundary/ —— 那三层不许读时钟,
        // 而"她这声铃是多久以前的"只有墙上时钟答得出来。仍然做成可注入的, 因为测试要把它定住
        this.clock = Objects.requireNonNull(clock, "需要一个时钟");
        this.timeoutMillis = Math.max(500, timeoutMillis);
        this.reconnectAttempts = Math.max(0, reconnectAttempts);
        this.reconnectDelayMillis = Math.max(100L, reconnectDelayMillis);
        this.pingIntervalMillis = Math.max(1_000L, pingIntervalMillis);
        this.link = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-platform-link");
            t.setDaemon(true);
            return t;
        });
    }

    // ═══════════════════════════ ① 登录 ═══════════════════════════

    /**
     * {@inheritDoc}
     *
     * <h2>身份在钥匙里, 不在请求体里</h2>
     *
     * <p>{@code POST /api/client/login} 收的是 {@code X-Api-Key: sap_…} 而<b>不是</b>
     * "账号 + 密码": {@code ClientSessionService} 从钥匙本身解出账号, 请求体里没有账号
     * 这个字段可填。"我是谁"因此不可能由调用方自报 —— 这是那三层授权里第 ① 层与第 ② 层的
     * 交接点。
     *
     * <p>于是 {@link LoginRequest#accountId()} 只能拿来<b>核对</b>: 平台说是谁, 就必须是
     * 谁。对不上说明配置里的钥匙与账号配错了(比如把 A 的钥匙配给了 B), 而那种错误如果被
     * 放过, 后果是<b>她拿着 A 的令牌去读 B 的会话</b> —— 平台不会拦, 因为从平台的角度看
     * 一切合法。所以这里宁可抛。
     *
     * <h2>登录成功的那一刻, 本类就把这个会话记下了</h2>
     *
     * <p>这不是实现细节, 而是"打开聊天软件"该有的样子: 登录之后她就能看会话列表、读消息、
     * 发消息 —— <b>不管那条推送长连接有没有连上</b>。平台重启期间、重连退避期间、乃至压根
     * 没有 WS 可用的环境里, 这些动作都该照常可用。
     *
     * <p>反过来做(只记在 {@code connect} 里)的代价, 值得写清楚: 那样一来
     * <b>每一个 REST 方法都要以 {@code connect} 为先决条件</b>, 而漏掉它的现象是一句
     * 笼统的"还没登录聊天平台"—— 排查的人会去查令牌、查钥匙、查平台, 而真正的原因只是
     * 调用顺序。一个把"没连接"与"没登录"混成同一句话的报错, 会浪费掉一整个下午。
     *
     * <p>登出({@link #disconnect})才把这个会话清掉 —— 那时"她不再登录"与"她不再连接"
     * 确实是同一件事。
     */
    @Override
    public ChatSession login(LoginRequest request) {
        Objects.requireNonNull(request, "登录请求不能为空");
        Wire wire = call("POST", "/api/client/login", "",
                Map.of("X-Api-Key", request.credential()));
        com.luxera.companion.contracts.client.ChatSession issued = read(wire,
                com.luxera.companion.contracts.client.ChatSession.class, "登录聊天平台");

        if (issued.token() == null || issued.token().isBlank()) {
            throw new IllegalStateException("登录成功了但没有拿到令牌 —— 第 ② 层授权没有载体, "
                    + "之后每一个请求都会 401。这不是可以继续的状态");
        }
        if (issued.accountId() == null || issued.accountId().isBlank()) {
            throw new IllegalStateException("登录成功了但平台没说这是哪个账号");
        }
        if (!issued.accountId().equals(request.accountId())) {
            throw new IllegalStateException("钥匙与账号配错了: 请求里是 " + request.accountId()
                    + ", 而这把钥匙在平台上属于 " + issued.accountId()
                    + " —— 放过去的话, 她会拿着别人的令牌去读别人的会话(平台看不出这是错的)");
        }
        if (issued.expiresAt() == null) {
            // 客户端面的 Agent 路径**总是**给 expiresAt(见 ClientSessionService.loginAsAgent:
            // 它显式地 plusNanos(…))。为 null 只可能是对面换了实现, 而那会让
            // "她的会话还有多久"变成一个不可知的问题。反过来说, 真人路径的 expiresAt 确实
            // 可能为 null(那个值是从令牌里解出来的), 所以这条判断只对 Agent 路径成立,
            // 而本类只走 Agent 路径 —— 客户端面对 Agent 与真人给出不同的回答形状,
            // 这件事本身是 ClientSessionService 说明过的
            throw new IllegalStateException("平台没给令牌有效期 —— 客户端面的 Agent 路径本应总是给。"
                    + "她不接受一个'不知道什么时候过期'的会话, 因为那会让'她还能不能看到她的人'"
                    + "变成一个不可知的问题");
        }
        // 签发时刻: 线上响应里没有这个字段(契约 ChatSession 只有 expiresAt), 而世界侧的
        // ChatSession 要求一个 issuedAt。取"我们拿到它的这一刻" —— 这是真的, 不是编的:
        // 这一份会话对象确实从此刻开始被她持有。remainingAt/expiredAt 读的都是 expiresAt,
        // 所以这个选择不影响任何判定
        Instant issuedAt = clock.get();
        Instant expiresAt = toInstant(issued.expiresAt(), "令牌有效期");
        if (expiresAt.isBefore(issuedAt)) {
            // 走到这里说明"平台说这个令牌还有效"与"我们算出来它已经过期了"同时成立, 而原因
            // 几乎只有一个: **两边的时区不一致**。平台的时刻是 LocalDateTime(Asia/Shanghai),
            // 而本类的转换用 ZoneId.systemDefault() —— 两边同为 Asia/Shanghai 时它是对的
            // (本机与两个 systemd 单元今天都是), 但一个 UTC 的容器就会让这个偏差变成 8 小时。
            //
            // 方向值得说明: 时区偏在"平台在后"这一侧时会算出一个未来 8 小时的过期时刻 ——
            // 那是**静默**的(会话活得比该活的久); 偏在这一侧时算出一个过去的时刻, 而那会
            // 让 ChatSession 的构造器抛 "生下来就过期的会话"。后者是响的, 但它的信息里
            // 不会有"时区"两个字, 排查要花掉一个下午。所以这里自己判一次, 把原因写出来
            throw new IllegalStateException("平台给的令牌有效期(" + expiresAt
                    + ")换算之后早于签发时刻(" + issuedAt + ") —— 两边时区不一致的可能性最大: "
                    + "平台发的是 Asia/Shanghai 的 LocalDateTime, 而本进程的默认时区是 "
                    + ZoneId.systemDefault());
        }
        ChatSession fresh = ChatSession.of(issued.accountId(), issued.token(), issuedAt, expiresAt);
        // 记下来 —— 见上面那段: 登录之后 REST 就该能用, 不必等长连接。
        // 刻意<b>不</b>动 shutDown: 那个字段管的是"要不要一条推送连接", 而"已登录但还没连"
        // 是一个完全成立的状态(平台在重启时她就是这个状态)
        this.session = fresh;
        log.info("[聊天网关] 登录成功: 账号 {} 有效期至 {}", fresh.accountId(), fresh.expiresAt());
        return fresh;
    }

    // ═══════════════════════════ ② 长连接 ═══════════════════════════

    /**
     * {@inheritDoc}
     *
     * <p>幂等: 连一条已经在连着的连接, 会先把旧的摘掉再连新的 —— 而<b>补发游标不清零</b>,
     * 所以重连不会把已经听过的铃再听一遍。
     */
    @Override
    public void connect(ChatSession session) {
        Objects.requireNonNull(session, "要连哪一份会话? 不能为空");
        this.session = session;
        this.sessionRejected.set(false);
        this.consecutiveFailures.set(0);
        this.shutDown = false;
        openSocket();
    }

    @Override
    public void onSignal(SignalListener listener) {
        Objects.requireNonNull(listener, "监听器不能为空 —— 一个什么都没注册的连接是个摆设");
        listeners.add(listener);
    }

    /**
     * {@inheritDoc}
     *
     * <p>幂等, 且<b>清掉一切</b>: 会话、连接、心跳都停。
     * 补发游标<b>保留</b> —— 登出再登入的是同一个账号, 而那期间响过的铃不该被重放
     * (平台的 {@code NotificationSignalLog} 本来就是按账号存的, 这是它的语义)。
     */
    @Override
    public void disconnect() {
        this.shutDown = true;
        this.session = null;
        closeSocket();
        log.info("[聊天网关] 长连接已断开(游标停在 {}), 不再重连", lastAckSignalId.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 全仓目前<b>没有调用方</b>(grep 过)。这不影响它的必要性, 但影响它的可信度:
     * 它是"这条连接还活着吗"的唯一一处直接回答, 而没有人问。所以 {@link #startHeartbeat}
     * 与 {@link #scheduleReconnect} 承担了全部的可观测性 —— 一条死连接只会出现在日志里。
     */
    @Override
    public boolean connected() {
        Session current = this.socket;
        return current != null && current.isOpen();
    }

    // ═══════════════════════════ ③ 会话与消息 ═══════════════════════════

    /**
     * {@inheritDoc}
     *
     * <h2>游标与 limit 为什么是"不支持"而不是"忽略"</h2>
     *
     * <p>客户端面的 {@code GET /api/client/conversations} 返回的是这个人<b>全部</b>会话,
     * 没有游标这一说({@code ClientApiController} 的返回值就是 {@code List}, 不是页)。
     * 传一个游标进来意味着调用方在翻页 —— 而一个"每页都返回全部"的分页会让它在
     * 第一页就拿到所有东西然后停下来, 看起来正常, 实际上把"还有更多"这件事永远答成否。
     *
     * <p>{@link ConversationQuery#limit()} 同理不能截断, 理由在上面那条的另一面:
     * {@code ConversationPage} 的构造器把 {@code hasMore} 收紧成
     * {@code hasMore && nextCursor != null}, 于是"我给你前 50 条, 但还有更多"这句话
     * <b>无法表达</b>。既然表达不出"还有更多", 就不能假装只给了 50 条 —— 那会让
     * 第 51 个人的消息在她的列表里消失, 而她与平台都不知道。
     *
     * <p>真相是这份列表本来就不长(它是"谁在找过她", 不是消息), 平台前端也是整份拿的。
     */
    @Override
    public ConversationPage listConversations(ConversationQuery query) {
        ConversationQuery q = query == null ? ConversationQuery.first() : query;
        if (q.cursor() != null) {
            throw new UnsupportedOperationException("客户端面的会话列表没有游标 —— 它整份返回, "
                    + "所以翻页的调用方拿到的第二页永远是空的, 而它会把这读成'没有更多会话'。"
                    + "要检索请用 ConversationQuery.keyword(平台侧会过滤)");
        }
        String path = "/api/client/conversations" + (q.keyword() == null ? ""
                : "?q=" + URLEncoder.encode(q.keyword(), StandardCharsets.UTF_8));
        Wire wire = call("GET", path, null, Map.of("Authorization", bearer()));
        List<com.luxera.companion.contracts.client.ClientConversation> raw =
                readList(wire, com.luxera.companion.contracts.client.ClientConversation.class,
                        "看会话列表");

        List<ConversationSummary> rows = new ArrayList<>(raw.size());
        for (com.luxera.companion.contracts.client.ClientConversation c : raw) {
            if (!q.includeMuted() && c.muted()) {
                // 世界侧要"别把免打扰的那些给我"。平台没有这个过滤参数(它的前端要显示全部,
                // 只是把它们排在后面), 所以过滤发生在这一层 —— 而它是**纯粹的投影**:
                // 一条会话要么在, 要么不在, 没有第三种状态
                continue;
            }
            rows.add(new ConversationSummary(c.accountId(), c.unreadCount(),
                    // lastActivityAt **可空**, 而且"可空"是它的正常状态之一(从没说过话的会话
                    // —— 契约的 javadoc 写着"没说过话时为 null")。世界侧的 ConversationSummary
                    // 也接受 null, 所以这里必须用那个宽容的转换: 用会抛的那个的话,
                    // 只要有一个从没说过话的会话, 整个列表接口就炸
                    toInstantOrNull(c.lastActivityAt(), "会话最后活动时刻"),
                    c.pinned(), c.muted()));
        }
        return new ConversationPage(rows, null, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>正文的唯一入口。分页语义与平台逐字一致: 不传游标 = 最新一页, 之后原样回传
     * {@code nextCursor}。{@code limit} 由平台侧封顶({@code ClientConversationService}
     * 的 {@code MAX_PAGE_SIZE = 100}), 这里不重复一遍那个数字 —— 两个地方各记一份上限,
     * 改了一处就会出现"她以为能看到 100 条, 实际拿到 20 条"。
     */
    @Override
    public MessagePage readMessages(String accountId, MessageCursor cursor, int limit) {
        String peer = requireText(accountId, "要读与谁的会话? 需要 accountId");
        StringBuilder path = new StringBuilder("/api/client/conversations/")
                .append(URLEncoder.encode(peer, StandardCharsets.UTF_8))
                .append("/messages?limit=").append(Math.max(1, limit));
        if (cursor != null) {
            path.append("&cursor=").append(URLEncoder.encode(cursor.value(), StandardCharsets.UTF_8));
        }
        Wire wire = call("GET", path.toString(), null, Map.of("Authorization", bearer()));
        com.luxera.companion.contracts.client.MessagePage page = read(wire,
                com.luxera.companion.contracts.client.MessagePage.class, "读消息");

        List<ClientMessage> rows = new ArrayList<>(page.messages().size());
        for (com.luxera.companion.contracts.client.ClientMessage m : page.messages()) {
            rows.add(new ClientMessage(m.messageId(), m.senderAccountId(), m.kind(), m.content(),
                    toInstant(m.sentAt(), "消息发送时刻"), m.deliveryStatus()));
        }
        return new MessagePage(rows, page.nextCursor(), page.hasMore());
    }

    /**
     * {@inheritDoc}
     *
     * <h2>只有 {@code TEXT} 发得出去, 而且这件事是响的</h2>
     *
     * <p>客户端面的发送请求体里没有 {@code kind}(见 {@code ClientApiController.SendBody}:
     * 它刻意不给, 因为 {@code SYSTEM} 在聊天前端会渲染成居中的平台通告, 而这个面含真人浏览器,
     * 开放它等于让任何客户端伪造通告)。所以世界侧要求的那几种消息种类在这里
     * <b>表达不出来</b>。
     *
     * <p>表达不出来就抛。<b>不做降级</b>: 把 {@code APPLICATION_CARD} 当作普通文本发出去,
     * 在她那边是"卡片发出去了", 在平台那边是一条长相不同的消息, 而两边都不会报错 ——
     * 这种不一致只有把两边并排看才发现。
     *
     * <p>幂等键原样带过去: 平台的 {@code SendBody.idempotencyKey} 与世界的
     * {@code SendMessageCommand.idempotencyKey} 是同一个概念, 由调用方给(见
     * {@code ChatApplication.sendMessage} 的说明: 一个每次新生成的键等于没有键)。
     */
    @Override
    public SendResult sendMessage(SendMessageCommand command) {
        Objects.requireNonNull(command, "要发什么? 不能为空");
        if (!MessageKinds.TEXT.equals(command.kind())) {
            throw new UnsupportedOperationException("客户端面只能发 " + MessageKinds.TEXT
                    + ", 要发 " + command.kind() + " 请走开放面 /api/v1/chat"
                    + "(那边接受 messageKind 且带白名单)"
                    + " —— 本类刻意不把它降级成一条普通文本: 那会让'她发了卡片'这件事在数据上"
                    + "不存在, 而两边都不会报错");
        }
        String peer = requireText(command.accountId(), "发给谁? 需要 accountId");
        Wire wire = call("POST", "/api/client/conversations/"
                + URLEncoder.encode(peer, StandardCharsets.UTF_8) + "/messages", json(Map.of(
                "content", command.content(),
                "idempotencyKey", command.idempotencyKey())), Map.of("Authorization", bearer()));
        com.luxera.companion.contracts.client.SendResult sent = read(wire,
                com.luxera.companion.contracts.client.SendResult.class, "发消息");
        return new SendResult(sent.messageId(), toInstant(sent.sentAt(), "发送时刻"));
    }

    /**
     * {@inheritDoc}
     *
     * <h2>平台的"已读"是一个<b>计数</b>, 不是一张逐条名单</h2>
     *
     * <p>这是本类里最需要被读懂的一处不匹配。世界侧给一串 messageId, 而
     * {@code POST .../read} 收一个 {@code lastMessageId} 游标; 但平台那边
     * {@code ConversationReadStateService.markRead} 实际做的只有两件事:
     * <b>把 unreadCount 归零</b>、把 lastMessageId 记下来。也就是说
     * "读了哪几条"这件事在平台上<b>不存在</b> —— 任何一条 id 都会让未读归零。
     *
     * <p>于是本类取列表首位。这不是随机的选择: 调用方(她)的列表来自
     * {@link #readMessages}, 而那个顺序是<b>最新在前</b>, 所以首位就是她要读到的那一条。
     * 更关键的是: 在今天的平台上这个选择<b>不可观测</b> ——
     * {@code ConversationReadState.lastReadMessageId} 全仓只写不读(grep 过)。
     * 写进 javadoc 是为了它将来被读起来的那一天: 那时这个选择会第一次显形, 而正确的做法
     * 是让平台接受一个集合, 不是在客户端这边挑一个。
     *
     * <h2>空集合为什么抛</h2>
     *
     * <p>平台那边"不带 lastMessageId"的含义是<b>全读了</b>({@code ReadBody} 的那个字段可以
     * 不传, 注释写着"不传就是全读了")。空集合在世界侧的含义是"一条都没读"。
     * 两者正好相反 —— 传过去会把"她什么都没看"记成"她全看完了",
     * 而这是一个<b>不可撤销</b>的写入(未读归零之后再也回不去)。
     */
    @Override
    public void markRead(String accountId, Collection<String> messageIds) {
        String peer = requireText(accountId, "哪个会话? 需要 accountId");
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("标已读需要 messageIds —— 空的 messageIds 传到平台上"
                    + "会变成'整个会话全读了'(平台把不传 lastMessageId 读作全读), "
                    + "而世界侧的空集合意思是'一条都没读'。两者正好相反, 而平台那次写入不可撤销");
        }
        String newest = messageIds.iterator().next();
        Wire wire = call("POST", "/api/client/conversations/"
                + URLEncoder.encode(peer, StandardCharsets.UTF_8) + "/read",
                json(Map.of("lastMessageId", newest)), Map.of("Authorization", bearer()));
        requireOk(wire, "标记已读");
    }

    /**
     * {@inheritDoc}
     *
     * <h2>为什么这里要多一次读</h2>
     *
     * <p>客户端面的 {@code PUT .../notification} 是<b>整体替换</b>两个开关(muted 与 pinned),
     * 而世界侧的这个方法只有 {@code muted} 一个入参。直接 PUT {@code pinned=false} 的话,
     * "把她免打扰了"会顺手把用户自己置顶的会话从顶上拿下来 —— 而这是一次
     * <b>用户设置被 agent 悄悄改掉</b>的事故, 返回体里也看不出发生过。
     *
     * <p>所以先读一次现状, 把 pinned 原样带上。多一次往返换掉一整类"静默改动用户设置",
     * 这个买卖在任何一天都划算。
     */
    @Override
    public ConversationNotificationSetting setNotification(String accountId, boolean muted) {
        String peer = requireText(accountId, "哪个会话? 需要 accountId");
        boolean pinned = currentPinned(peer);
        Wire wire = call("PUT", "/api/client/conversations/"
                + URLEncoder.encode(peer, StandardCharsets.UTF_8) + "/notification",
                json(Map.of("muted", muted, "pinned", pinned)),
                Map.of("Authorization", bearer()));
        com.luxera.companion.contracts.client.ConversationNotificationSetting setting = read(wire,
                com.luxera.companion.contracts.client.ConversationNotificationSetting.class,
                "设置免打扰");
        return new ConversationNotificationSetting(setting.conversationId(), setting.muted(),
                setting.pinned(), toInstant(setting.updatedAt(), "通知设置更新时刻"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>"查无此人"与"查到了但没名字"是两件事, 而它们在这里分得开: 前者是
     * {@link Optional#empty()}(平台的 404 —— {@code ClientConversationService.contact}
     * 在没有会话时抛 {@code notFound}), 后者是一个 {@code displayName} 为 null 的资料。
     * 对 Agent 身份来说<b>后者才是常态</b> —— 平台只给 id, "这个人是谁"是她自己聊出来的
     * (见 {@code ClientConversationService.contact}:
     * {@code me.isHuman() ? displayNameOf(accountId) : null})。
     * 所以本类<b>不</b>拿 accountId 去填名字字段: 那会让"她给他起了个名字"与
     * "平台给了个名字"再也分不开。
     */
    @Override
    public Optional<ContactProfile> contactProfile(String accountId) {
        String peer = requireText(accountId, "看谁的资料? 需要 accountId");
        Wire wire = call("GET", "/api/client/contacts/"
                + URLEncoder.encode(peer, StandardCharsets.UTF_8), null,
                Map.of("Authorization", bearer()));
        if (wire.status() == 404) {
            return Optional.empty();
        }
        com.luxera.companion.contracts.client.ContactProfile profile = read(wire,
                com.luxera.companion.contracts.client.ContactProfile.class, "看资料");
        return Optional.of(new ContactProfile(profile.accountId(), profile.displayName(),
                profile.avatarUrl()));
    }

    // ═══════════════════════════ 长连接的实现 ═══════════════════════════

    /**
     * 建立(或重连)那条 WebSocket。
     *
     * <p>{@code synchronized}: 心跳、重连计划、以及显式的 {@code connect} 都可能同时想开一条,
     * 而两条并存的连接会让<b>同一条消息响两次</b>(平台会给每个连接各发一份)。
     */
    private synchronized void openSocket() {
        ChatSession current = this.session;
        if (shutDown || current == null) {
            return;
        }
        closeSocket();
        URI uri = streamUri(current);
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            // 容器自带的空闲超时会**静默**掐掉一条长时间没有报文的连接。心跳由本类自己发
            // (协议里 PING 是客户端 → 服务端的方向, 服务端只回 PONG), 所以这里把容器的
            // 超时关掉, 让"这条连接还活着吗"只有一个判定来源
            container.setDefaultMaxSessionIdleTimeout(0L);
            Session opened = container.connectToServer(new StreamEndpoint(),
                    ClientEndpointConfig.Builder.create().build(), uri);
            this.socket = opened;
            this.consecutiveFailures.set(0);
            startHeartbeat(opened);
            log.info("[聊天网关] 长连接已建立: 账号 {} 补发游标 {}",
                    current.accountId(), lastAckSignalId.get());
        } catch (Exception e) {
            log.warn("[聊天网关] 长连接建立失败({}): {}", describeWithoutToken(uri), e.toString());
            scheduleReconnect();
        }
    }

    private synchronized void closeSocket() {
        stopHeartbeat();
        Session current = this.socket;
        this.socket = null;
        if (current == null) {
            return;
        }
        try {
            current.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "client-gone"));
        } catch (Exception e) {
            // 关一条已经断了的连接会抛。这不是错误 —— 我们要的结果(它不再存在)已经达到了
            log.debug("[聊天网关] 关闭连接时出错(忽略): {}", e.toString());
        }
    }

    /**
     * 心跳: 只为了一件事 —— 让"这条连接是不是已经死了"有一个能问出来的地方。
     *
     * <p>为什么非要有它: 半开连接(TCP 那头没了, 本地这头毫无察觉)在没有心跳时
     * <b>永远不会</b>触发 {@code onClose}, 于是 {@code connected()} 一直答"连着",
     * 而信号一条都到不了。整个类里没有别的东西能在有限时间内发现这件事 ——
     * 而由于 {@code connected()} 零调用, 连"问一下"的人都还没有。
     */
    private void startHeartbeat(Session opened) {
        if (pingIntervalMillis <= 0) {
            return;
        }
        this.heartbeat = link.scheduleAtFixedRate(() -> {
            if (shutDown || this.socket != opened || !opened.isOpen()) {
                return;
            }
            try {
                send(opened, new ClientStreamFrame(ClientStreamFrame.TYPE_PING, null, null, 0));
            } catch (Exception e) {
                log.warn("[聊天网关] 心跳发不出去, 这条连接已经不能用了: {}", e.toString());
                scheduleReconnect();
            }
        }, pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** 停掉当前心跳。不断掉它的话, 每次重连都会多留一个只做判断、什么都不做的定时任务。 */
    private void stopHeartbeat() {
        ScheduledFuture<?> task = this.heartbeat;
        this.heartbeat = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 断线之后自己接回来 —— 而且要<b>带上补发游标</b>, 否则错过的那几声响就永远没了。
     *
     * <h2>为什么重连次数是有限的</h2>
     *
     * <p>无限重连听起来更稳, 实际是两个坏东西: 一个被销号/被删掉的账号会让这条线程
     * 永远每 60 秒刷一次日志(而它刷的内容永远是同一句); 而"她现在是聋的"这件事
     * 也就永远不会被明确地说出来。所以这里给 12 跳(约 5 分钟)之后<b>放弃并报 ERROR</b> ——
     * 一句"她已经收不到信号了, 需要重新 login"比一万句 WARN 有用。
     *
     * <p>放弃不等于永久放弃: 世界侧下一次 {@code chat.login} 会调 {@link #connect}, 而那会把
     * 计数清零并重新开始。也就是说自愈的入口是<b>她重新登录</b>, 不是这个线程一直试 ——
     * 而"她要不要重新登录"是她的决定(那时她也可以选择不再连, 因为会话过期了)。
     *
     * <p>命中 {@code SESSION_EXPIRED} 时<b>不重连</b>: 平台已经说了这个令牌不能用,
     * 拿它重试只会得到同样一条回答。这种情况必须由上层重新登录, 所以它报 ERROR 而不是 WARN。
     */
    private void scheduleReconnect() {
        if (shutDown) {
            return;
        }
        if (sessionRejected.get()) {
            log.error("[聊天网关] 长连接被平台拒绝(令牌已失效) —— 她收不到任何信号, "
                    + "直到重新登录(chat.login)。本类不自动重登: 登录需要凭据与一次决定, "
                    + "而那个决定属于她(见 ChatApplication.login)");
            return;
        }
        if (this.session == null) {
            return;
        }
        int attempt = consecutiveFailures.incrementAndGet();
        if (attempt > reconnectAttempts) {
            log.error("[聊天网关] 长连接连续 {} 次没能接回来, 放弃重连 —— 她从此收不到任何信号。"
                    + "最后一条错误见上面那几行; 恢复入口是下一次 chat.login",
                    reconnectAttempts);
            return;
        }
        long delay = reconnectDelay(attempt);
        log.warn("[聊天网关] 长连接断了, {} ms 后重连(第 {} 次, 补发游标 {})",
                delay, attempt, lastAckSignalId.get());
        link.schedule(this::openSocket, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 第 {@code attempt} 跳等多久: 1s、2s、4s… 封顶 60s。
     *
     * <p>指数退避而不是固定间隔, 因为断的原因通常是"平台在重启" —— 固定间隔会在它还没起来时
     * 把 12 跳全花光(12 × 1s = 12 秒, 比一次重启还短), 于是"平台重启"这件五分钟后就自愈的事
     * 变成了"她聋了"。
     */
    private long reconnectDelay(long attempt) {
        long delay = reconnectDelayMillis;
        for (long i = 1; i < attempt && delay < MAX_RECONNECT_DELAY_MS; i++) {
            delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
        }
        return Math.min(delay, MAX_RECONNECT_DELAY_MS);
    }

    /**
     * 平台推来的一帧。
     *
     * <p>JSR-356 的端点: 容器为每一条连接各造一个实例, 而状态(监听器、游标)都在外部那个
     * {@link HttpChatPlatformGateway} 上 —— 于是重连之后新的实例接着用同一份状态,
     * 这正是补发游标能跨连接活着的原因。
     *
     * <p><b>{@code @OnMessage} 不能省。</b>没有这个注解容器不会报任何错 —— 它只是永远不把
     * 报文交给这个方法, 于是现象是"连上了、READY 也没有、什么都不来",
     * 而所有直接调 {@code onNotification} 的单元测试全绿。
     */
    private final class StreamEndpoint extends Endpoint {

        @Override
        public void onOpen(Session opened, EndpointConfig config) {
            log.debug("[聊天网关] WS 会话 {} 已就绪, 等平台的 READY", opened.getId());
        }

        @OnMessage
        public void onMessage(String text, Session opened) {
            ClientStreamFrame frame;
            try {
                frame = mapper.readValue(text, ClientStreamFrame.class);
            } catch (Exception e) {
                log.warn("[聊天网关] 收到一条读不懂的帧, 丢掉: {}", e.toString());
                return;
            }
            String type = frame.type() == null ? "" : frame.type().trim().toUpperCase(Locale.ROOT);
            switch (type) {
                case ClientStreamFrame.TYPE_READY ->
                        log.info("[聊天网关] 平台已确认长连接(鉴权已过、补发已发完)");
                case ClientStreamFrame.TYPE_NOTIFICATION -> onNotification(frame);
                case ClientStreamFrame.TYPE_PONG -> log.trace("[聊天网关] PONG");
                case ClientStreamFrame.TYPE_SESSION_EXPIRED -> {
                    sessionRejected.set(true);
                    log.warn("[聊天网关] 平台说这个会话令牌不能用了: {}", frame.reason());
                }
                case ClientStreamFrame.TYPE_ERROR -> log.warn(
                        "[聊天网关] 平台对上一帧回了 ERROR: {}", frame.reason());
                default -> log.warn("[聊天网关] 平台发来一种不认识的帧型 {} —— 契约加了新帧型? "
                        + "这一条被丢掉, 而丢掉的后果取决于它是什么", type);
            }
        }

        @Override
        public void onClose(Session opened, CloseReason reason) {
            if (shutDown) {
                return;
            }
            synchronized (HttpChatPlatformGateway.this) {
                if (socket == opened) {
                    socket = null;
                    stopHeartbeat();
                }
            }
            log.info("[聊天网关] 长连接关闭: {} {}", reason.getCloseCode(), reason.getReasonPhrase());
            scheduleReconnect();
        }

        @Override
        public void onError(Session opened, Throwable error) {
            // 对面关掉窗口/断网在这里与"程序出错"长得一样, 而它们要的处理也确实是同一个
            log.warn("[聊天网关] 长连接出错: {}", error.toString());
        }
    }

    /** 一条通知信号: 翻成世界侧的话, 交给监听器, <b>然后</b>才确认收到。 */
    private void onNotification(ClientStreamFrame frame) {
        com.luxera.companion.contracts.client.NotificationSignal wire = frame.signal();
        if (wire == null) {
            log.warn("[聊天网关] NOTIFICATION 帧里没有信号 —— 契约不允许这样, 丢掉这一条");
            return;
        }
        NotificationSignal signal;
        try {
            signal = toWorldSignal(wire);
        } catch (RuntimeException e) {
            // ★ 刻意<b>不</b>确认收到。平台的 NotificationSignalLog 会保留这条(容量 256),
            // 于是把对面升级到 1.0.2 之后, 下一次重连会把错过的这些<b>全部补回来</b> ——
            // 也就是说这个失败是可自愈的, 而确认掉它就等于永久丢掉一次"有人找过她"。
            // 用一次会在日志里反复出现的 ERROR, 换掉一次不可察觉的丢失
            log.error("[聊天网关] 这条信号读不成世界能懂的样子, 不确认收到(平台重连时会补发): {}",
                    e.getMessage());
            return;
        }
        for (SignalListener listener : listeners) {
            try {
                listener.onSignal(signal);
            } catch (RuntimeException e) {
                // 一个监听器抛异常不该让后面的收不到 —— 但也<b>不</b>确认收到:
                // "应用没能处理它"与"应用处理了"必须分得开, 否则那次铃就白响了
                log.warn("[聊天网关] 一个信号监听器抛了异常, 这一条不确认收到: {}", e.toString());
                return;
            }
        }
        long ack = signalIdOf(signal);
        lastAckSignalId.accumulateAndGet(ack, Math::max);
        Session current = this.socket;
        if (current != null && current.isOpen()) {
            try {
                send(current, new ClientStreamFrame(ClientStreamFrame.TYPE_ACK, null, null, ack));
            } catch (RuntimeException e) {
                // 确认发不出去不是灾难: 平台会重发这一条(于是可能响两次), 而客户端侧
                // 按 signalId 去重是协议本来就要求的(见 ClientStreamEndpoint 类注释第 2 步)
                log.warn("[聊天网关] 确认收到发不出去(平台会重发, 可能重复响一次): {}", e.toString());
            }
        }
    }

    /**
     * 契约的信号 → 世界能懂的那条信号。
     *
     * <h2>三个刻意丢掉的字段</h2>
     * <ul>
     *   <li>{@code fromAccountId} —— <b>丢</b>, 而且不是省事: 世界侧的
     *       {@code NotificationSignal} 里<b>没有</b>"谁发的"这个位置,
     *       因为"她的 Mind 看不到是谁发的"(见那个类型的 conversationRef 说明)。
     *       正文与发件人都在"她主动去看"那一步之后才出现;</li>
     *   <li>{@code reason} / {@code soundProfile} —— 世界侧有这两个位置, 而平台不发它们
     *       (它今天只有一种理由: 未免打扰的会话来了消息)。填默认值不是编造:
     *       "new-message"此刻就是事实。等平台有了第二种理由, 它们会进契约</li>
     * </ul>
     *
     * <h2>{@code unreadCount} 为 null 时为什么抛</h2>
     * 见类注释与 {@code NotificationSignal#unreadCount} 的 javadoc: null 意味着对面还在发
     * 1.0.1 的信封。它<b>不是 0</b> —— 0 会让红点永远不亮, 而那与"确实没有新消息"
     * 在界面上完全一样。抛出去之后 {@link #onNotification} 不会确认收到这条,
     * 于是升级对面之后它会自己补回来。
     */
    private NotificationSignal toWorldSignal(
            com.luxera.companion.contracts.client.NotificationSignal wire) {
        if (wire.unreadCount() == null) {
            throw new IllegalStateException("信号里没有 unreadCount —— 聊天平台还在发契约 1.0.1 的信封, "
                    + "而本仓消费的是 1.0.2(见 NotificationSignal#unreadCount)。"
                    + "这不能当成 0: 那会让她的红点永远不亮, 而现象是'她什么都没收到'");
        }
        if (wire.raisedAt() == null) {
            // 世界侧的 occurredAt 是非空的, 而它的用途(ageAt)要在"这声铃是不是已经太旧了"
            // 这个判断里用。一个没有时刻的信号没法被丢掉, 于是它会被当成刚发生的
            throw new IllegalStateException("信号里没有 raisedAt —— 一个不知道什么时候发生的铃声, "
                    + "在'信号有时效'的判定(见 ChatApplication.onSignal)里没法被丢弃, "
                    + "于是它会被当成刚发生的");
        }
        return NotificationSignal.of(String.valueOf(wire.signalId()), wire.conversationId(),
                wire.unreadCount(), toInstant(wire.raisedAt(), "信号发生时刻"));
    }

    /**
     * 世界侧的 {@code signalId} 是 String(平台自己发号时也用 {@code "signal-<uuid>"},
     * 见那个类型的紧凑构造器), 而线路上是 long。
     *
     * <p>解析不了就返回 0 —— 也就是"还没有确认过任何一条"。那意味着这一条会被重复补发,
     * 而<b>重复响一次</b>比"确认掉一个错误的序号、从此永久跳过一段"安全得多:
     * 前者是多一声铃, 后者是丢一段人生。
     */
    private static long signalIdOf(NotificationSignal signal) {
        try {
            return Long.parseLong(signal.signalId());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ═══════════════════════════ HTTP 底座 ═══════════════════════════

    /**
     * 一次调用。用 JDK {@code HttpURLConnection}, 与 {@code HttpClientSupport} 同一先例
     * (不引新依赖) —— 但<b>不</b>继承它: 那个基类在 {@code open()} 里签 HMAC, 而本类带的是
     * Bearer。见类注释第一节。
     *
     * <p>失败抛 {@link IllegalStateException}: 读路径也抛。那三个适配器对读路径是宽容的
     * (空世界与"平台挂了"等价), 但这里不是 —— 她看会话列表拿到"空"与"平台连不上"
     * 是两件完全不同的事, 而前者会让她以为没有人在找她。
     */
    private Wire call(String method, String path, String jsonBody, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            headers.forEach(conn::setRequestProperty);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            String body = readFully(status < 400 ? conn.getInputStream() : conn.getErrorStream());
            return new Wire(status, body == null ? "" : body);
        } catch (IOException e) {
            throw new IllegalStateException("到不了聊天平台(" + method + " " + path + " @ " + baseUrl
                    + "): " + e.getClass().getSimpleName(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void requireOk(Wire wire, String what) {
        if (wire.status() / 100 == 2) {
            return;
        }
        throw new IllegalStateException(what + " 失败: 平台回答 " + wire.status() + " —— "
                + errorOf(wire.body()));
    }

    private <T> T read(Wire wire, Class<T> type, String what) {
        requireOk(wire, what);
        try {
            T value = mapper.readValue(wire.body(), type);
            if (value == null) {
                throw new IllegalStateException(what + " 的响应是空的 —— 平台回答 2xx 却没有内容");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException(what + " 的响应读不懂(期望 " + type.getSimpleName()
                    + "): " + snippet(wire.body()), e);
        }
    }

    private <T> List<T> readList(Wire wire, Class<T> element, String what) {
        requireOk(wire, what);
        try {
            return mapper.readValue(wire.body(),
                    mapper.getTypeFactory().constructCollectionType(List.class, element));
        } catch (IOException e) {
            throw new IllegalStateException(what + " 的响应读不懂(期望 " + element.getSimpleName()
                    + " 的列表): " + snippet(wire.body()), e);
        }
    }

    /** 平台的错误体是 {@code {error, code?, hint?}}(见 {@code ClientApiExceptionHandler})。 */
    private String errorOf(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String error = node.path("error").asText(null);
            if (error != null) {
                String hint = node.path("hint").asText(null);
                return hint == null ? error : error + " (" + hint + ")";
            }
        } catch (Exception ignored) {
            // 不是 JSON —— 那就把原文截一段出来, 至少比 "(no body)" 有用
        }
        return snippet(body);
    }

    /** 截断, 且<b>只截响应体</b> —— 令牌在请求头里, 永远不进日志。 */
    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 当前会话的 {@code Authorization} 头。没登录就抛 —— 而不是发一个匿名请求拿 401。 */
    private String bearer() {
        ChatSession current = this.session;
        if (current == null) {
            throw new IllegalStateException("还没登录聊天平台 —— 会话列表/消息/发送都走第 ② 层授权"
                    + "(Bearer 令牌), 而那个令牌只有 login 拿得到");
        }
        return "Bearer " + current.token();
    }

    /**
     * 某个会话现在置顶了没有 —— 为了在 {@code PUT} 里把它保住, 见 {@link #setNotification}。
     *
     * <p>查不到那个会话时返回 {@code false}: 这时接下来那次 PUT 一定会 404(平台自己会说
     * "没有与账号 X 的会话", 见 {@code ClientConversationService} 里那个 notFound),
     * 所以这个值永远影响不到任何结果 —— 不必为它编一个更复杂的答案。
     */
    private boolean currentPinned(String peerAccountId) {
        Wire wire = call("GET", "/api/client/conversations", null, Map.of("Authorization", bearer()));
        for (com.luxera.companion.contracts.client.ClientConversation c
                : readList(wire, com.luxera.companion.contracts.client.ClientConversation.class,
                        "看会话列表")) {
            if (peerAccountId.equals(c.accountId())) {
                return c.pinned();
            }
        }
        return false;
    }

    private void send(Session session, ClientStreamFrame frame) {
        try {
            session.getBasicRemote().sendText(mapper.writeValueAsString(frame));
        } catch (IOException e) {
            throw new IllegalStateException("往长连接上写帧失败: " + e.getClass().getSimpleName(), e);
        }
    }

    // ═══════════════════════════ 小工具 ═══════════════════════════

    /**
     * {@code http://host:8081} → {@code ws://host:8081}, {@code https} → {@code wss}。
     *
     * <p>不是字符串替换, 而是显式判断两种前缀: 一个 wss 的部署被降级成 ws 的话,
     * 握手失败的信息只会说"连不上", 而原因(明文连一个只收 TLS 的端口)完全看不出来。
     */
    private static String toStreamPrefix(String baseUrl) {
        if (baseUrl.startsWith("https://")) {
            return "wss://" + baseUrl.substring("https://".length());
        }
        if (baseUrl.startsWith("http://")) {
            return "ws://" + baseUrl.substring("http://".length());
        }
        throw new IllegalArgumentException("聊天平台地址必须是 http(s):// 开头, 实际: " + baseUrl);
    }

    /** 令牌走查询串 —— 浏览器发不了自定义头的那个限制在这里同样成立(JSR-356 的握手没有头)。 */
    private URI streamUri(ChatSession session) {
        return URI.create(streamUrlPrefix + "/api/client/stream"
                + "?token=" + URLEncoder.encode(session.token(), StandardCharsets.UTF_8)
                + "&lastAckSignalId=" + lastAckSignalId.get());
    }

    /** 日志里连 URI 都不能整条打 —— 它在查询串上带着令牌。 */
    private static String describeWithoutToken(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
    }

    /** 字段<b>必须</b>有值时的转换 —— 与 {@link #toInstantOrNull} 的分工见那边。 */
    private static Instant toInstant(LocalDateTime value, String what) {
        if (value == null) {
            throw new IllegalStateException(what + " 是空的 —— 平台的时间口径一直是 LocalDateTime"
                    + "(Asia/Shanghai), 而世界侧一律用 Instant, 转换必须有东西可转");
        }
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 字段<b>允许</b>为空时的转换。
     *
     * <p>两者分开是必要的, 而不是为了少写一个 if: 契约里有的时刻是"没有就是没有"
     * ({@code ClientConversation.lastActivityAt} 的 javadoc 写着"没说过话时为 null"),
     * 有的是"必须有的"(消息的发送时刻、通知设置的更新时刻 —— 平台那些位置用的是
     * {@code LocalDateTime.now()} / {@code @UpdateTimestamp})。用错那一侧的表现是:
     * 前者会让"有一个从没说过话的会话"变成整个列表接口抛异常, 而后者会让一条
     * 不该存在的空时刻悄悄流进她的认知里。
     */
    private static Instant toInstantOrNull(LocalDateTime value, String what) {
        return value == null ? null : toInstant(value, what);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("请求体序列化失败: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String readFully(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Spring 关闭容器时会调它(bean 实现了 {@link AutoCloseable}) —— 于是那条守护线程
     * 不会活过一个已经关掉的上下文。行为与 {@link #disconnect()} 一致: 停掉一切, 且不再重连。
     */
    @Override
    public void close() {
        disconnect();
        link.shutdownNow();
    }

    private record Wire(int status, String body) {
    }
}
