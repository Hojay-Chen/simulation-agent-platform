package com.luxera.companion.world.application.chat;

import com.luxera.companion.boundary.action.ActionCommand;
import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.boundary.action.CapabilityDescriptor;
import com.luxera.companion.world.application.DeviceApplication;
import com.luxera.companion.world.device.Device;
import com.luxera.companion.world.device.NotificationRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * V2.2 §6 —— <b>聊天应用</b>: 装在设备上、把聊天平台接进世界的那个应用。
 *
 * <h2>用户的那半句话决定了本类的全部约束</h2>
 * <blockquote>
 *   把要让 agent 能够使用的三方平台软件, 让他们自己来实现这个"手机应用"接口的实现类,
 *   去对接他们的软件 api, <b>包括我们自己的聊天平台也是同样道理</b>。
 * </blockquote>
 * <b>包括我们自己的聊天平台也是同样道理</b> —— 所以本类:
 * <ul>
 *   <li>实现的是与第三方<b>完全相同</b>的 {@link DeviceApplication} 接口;</li>
 *   <li>拿不到任何特权: 它对世界的全部输出是 {@link Device#notify(NotificationRequest)}
 *       (与任何应用一样), 它对聊天平台的唯一通道是
 *       {@link ChatPlatformGateway}(与任何客户端一样);</li>
 *   <li>它<b>没有</b> {@code EventFabric}、<b>没有</b> {@code Mind}、<b>没有</b>
 *       直读数据库的端口 —— 换句话说, 就算把它换成第三方写的一个聊天应用,
 *       本项目的任何其他代码都不需要改一行。</li>
 * </ul>
 * 最后一条是这一整套设计的可检验形式: <b>如果我们的聊天应用能被第三方替换掉,
 * 那么"第三方可以自己实现接口"就不是一句口号</b>。
 *
 * <h2>它是 §2.2 那条链的第 ④⑤⑥ 步</h2>
 * <pre>
 *   ①用户发消息
 *   ②聊天平台落库并判定免打扰(平台侧, 见 ChatPlatformGateway#setNotification)
 *   ③产生 NotificationSignal —— <b>类型层面就没有正文</b>
 *   ④WebSocket 推到本应用  ←──────────────── 本类的 {@link #onSignal}
 *   ⑤本应用收到, 翻译成 NotificationRequest
 *   ⑥交给设备: {@code device.notify(request)}  ← 本类的 {@link #toNotification}
 *   ⑦设备按本机策略决定: 响铃 / 震动 / 亮屏 (NotificationSystem)
 *   ⑧投一条"手机响了"的世界事件(仍然没有正文)
 *   ⑨EventFabric → Human
 * </pre>
 *
 * <h2>⚠️ 本类里最重要的一条纪律: 信号只到"响"为止, 正文只从 read-messages 出</h2>
 * 第 ④ 步收进来的 {@link ChatPlatformGateway.NotificationSignal} <b>不含</b>正文,
 * 第 ⑤ 步造出的 {@link NotificationRequest} <b>也不含</b>正文。
 * 这不是"我们不读", 是<b>这两处根本没有那个字段</b>。
 *
 * <p>正文第一次进入她的认知, 只可能是 {@code chat.read-messages} ——
 * 即"她决定去看"这个动作(§9 验收标准 E)。为什么这条纪律值得用类型去钉死,
 * 设计文档 §1.3 P3 说得很直白: 一旦正文已经在她手里, "她在忙 / 没注意到 / 已读不回"
 * 就都只是<b>知道内容之后找的说法</b>。只有让"她没注意到"在结构上成立,
 * 它才可能是一个为真的状态。
 *
 * <p>反例(也就是<b>不</b>能做的做法): 在 {@link #onSignal} 里顺手
 * {@code gateway.readMessages(...)} 把正文取回来塞进通知, 好让她"一响就知道内容"。
 * 那样做的当天看起来体验更好, 而它摧毁的是整个仿真的意义 ——
 * 从此"她没回消息"永远只能解释成"她不想回"。
 *
 * <h2>逐条通知, 不聚合</h2>
 * 每一条信号都独立走一遍上面的链: <b>不合并、不节流、不"攒够 3 条再响一次"</b>。
 * 一条信号 = 一次设备决策 = 至多一条世界事件。理由是"她手机响了几下"是一个
 * <b>可观测的事实</b>, 而聚合会把它抹掉 —— 见 {@code NotificationSystem} 类注释。
 *
 * <h2>两层"不打扰"必须分开(最容易做错的一处)</h2>
 * <table border="1">
 *   <tr><th>层</th><th>在哪</th><th>决定什么</th><th>本类的关系</th></tr>
 *   <tr>
 *     <td>平台侧免打扰</td>
 *     <td>{@code ChatPlatformGateway#setNotification}</td>
 *     <td>要不要<b>发</b>通知信号</td>
 *     <td>本类的 {@code chat.set-mute} 改的就是它 —— 改的是<b>平台的状态</b>,
 *         而不是手机的状态</td>
 *   </tr>
 *   <tr>
 *     <td>本机静音</td>
 *     <td>{@code NotificationPolicy}(她的手机)</td>
 *     <td>收到信号之后<b>怎么响</b></td>
 *     <td>本类<b>不碰</b>它 —— 那是 {@code device.phone.set-notification-policy}</td>
 *   </tr>
 * </table>
 * 两者必须都能被表达, 因为它们回答的是不同的问题: "这个群我不想被打扰"
 * (平台知道)与"我现在在开会"(手机知道)。把事情合成一件,
 * "她没听见"就只剩一个解释, 而现实里它有两个, 两个都该可查证。
 *
 * <h2>为什么凭据不从能力参数里来</h2>
 * {@code chat.login} 的参数表里<b>没有</b> {@code credential}。凭据来自装配时的配置
 * (见构造器), 而不是来自 LLM 的一次工具调用。理由有两条, 第二条更重要:
 * <ol>
 *   <li>凭据一旦进了工具参数, 它就会出现在 LLM 的上下文、工具调用日志与诊断面板里 ——
 *       而它是一把钥匙;</li>
 *   <li>"登录"对 agent 来说是"把手机上的聊天软件打开"这个<b>动作</b>,
 *       不是"我知道我的密码"这个<b>知识</b>。真人也不会在每次打开软件时背一遍密码。</li>
 * </ol>
 *
 * <h2>本类不做的三件事</h2>
 * <ul>
 *   <li><b>不做未读持久化。</b>未读数在平台侧(它才是"她还没看"的权威), 本类只在本机
 *       记一份用于诊断与日志的快照 —— 见 {@link #unreadOf(String)};</li>
 *   <li><b>不做消息缓存。</b>缓存正文会让"她看过什么"变成一个不受 {@code readMessages}
 *       管辖的副本, 而那正是本类要守住的那条线的反面;</li>
 *   <li><b>不给自己留内部通道。</b>{@link #gateway} 是一个接口, 本类看到的只是它。
 *       连 {@code ChatApplication} 自己都无法绕过它去拿数据。</li>
 * </ul>
 */
@Slf4j
public class ChatApplication implements DeviceApplication {

    /** 应用的稳定标识 —— 它同时是能力的命名空间与通知的标签。 */
    public static final String APPLICATION_KEY = "chat";

    /** 平台安装这个应用时用的默认通知音标识 —— "她一听就知道是聊天软件在响"。 */
    public static final String DEFAULT_SOUND_PROFILE = "chat-notify";

    /**
     * 本应用对 agent 暴露的能力 key。
     *
     * <h2>与 §5.6 动作目录的差异: 下划线变成了连字符</h2>
     * 设计文档 §5.6 的目录用的是 {@code chat.read_messages} 这种下划线写法,
     * 但已实现的边界层里 {@code CapabilityDescriptor} 的 key 校验正则是
     * <pre>{@code [a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+}</pre>
     * —— <b>它不接受下划线</b>。
     *
     * <p>两者只能取一个, 而<b>取正则</b>是唯一可行的选择:
     * <ul>
     *   <li>改正则意味着动 {@code boundary/} 里的既有文件, 而边界层的语法已经
     *       被 {@code EventTypeId}、{@code CapabilityRegistry} 等共用 ——
     *       为一个命名偏好去改它是本末倒置;</li>
     *   <li>下划线与连字符在这个系统里表达的语义完全相同(分词), 没有信息损失;</li>
     *   <li>用连字符还与 {@code EventTypeId} 的命名空间写法一致
     *       ({@code device.phone.notification-raised}), 全系统只有一种分词风格。</li>
     * </ul>
     * 结论: 文档的<b>语义</b>被完整保留({@code chat.read-messages} 就是
     * {@code chat.read_messages}), 只换了分词符。这一点已在交付报告里作为
     * "文档与实现的差异"记录。
     */
    public static final class Capabilities {

        private Capabilities() {
        }

        /** 登录聊天平台 —— 之后所有其他能力都要求它先成功。 */
        public static final String LOGIN = "chat.login";

        /** 登出, 断开长连接。 */
        public static final String LOGOUT = "chat.logout";

        /** 看会话列表: 谁在找她、几条未读。<b>不含正文</b>。 */
        public static final String LIST_CONVERSATIONS = "chat.list-conversations";

        /** <b>读消息 —— 正文的唯一入口</b>。 */
        public static final String READ_MESSAGES = "chat.read-messages";

        /** 发一条消息。 */
        public static final String SEND_MESSAGE = "chat.send-message";

        /** 标记已读。 */
        public static final String MARK_READ = "chat.mark-read";

        /** 设置会话免打扰 —— 改的是<b>聊天平台</b>的状态(§6.2)。 */
        public static final String SET_MUTE = "chat.set-mute";

        /** 看某人的资料 —— 名字通常为空, 那是刻意的(§6.3 第 9 项)。 */
        public static final String CONTACT_PROFILE = "chat.contact-profile";

        /** 平台认识的全部能力 key —— 给文档、诊断与授权面板用。 */
        public static List<String> known() {
            return List.of(LOGIN, LOGOUT, LIST_CONVERSATIONS, READ_MESSAGES, SEND_MESSAGE,
                    MARK_READ, SET_MUTE, CONTACT_PROFILE);
        }
    }

    // ═══════════════════════════ 依赖与状态 ═══════════════════════════

    /** 对聊天平台的唯一通道 —— 接口, 不是实现类。见类注释"不给自己留内部通道"。 */
    private final ChatPlatformGateway gateway;

    /** 这台设备上的聊天账号(如 {@code agent_m3k9})。装配时给定, 不从能力参数来。 */
    private final String accountId;

    /** 登录凭据 —— <b>永不进日志、永不进事件载荷</b>, 只在 {@link #login} 里用一次。 */
    private final String credential;

    /**
     * 信号处理用的时钟。
     *
     * <p>为什么需要它: {@link #onSignal} 由<b>推送线程</b>调用, 身上没有
     * {@code CapabilityContext}(那是能力调用路径才有的东西), 所以拿不到仿真时刻。
     * 这与 {@code AudioSystem}/{@code ScreenSystem} 里那两处是同一个已知妥协:
     * <b>默认读墙上时钟, 但允许注入</b>。测试与回放里注入一个固定时钟即可,
     * 而生产路径上"信号到达时刻"本来就近似等于墙上时刻。
     */
    private final Supplier<Instant> clock;

    /** 应用的自描述 —— 构造一次, 之后不变(见 {@code DeviceApplication#descriptor})。 */
    private final Descriptor descriptor;

    /** 能力清单 —— 构造一次, 之后不变。理由同 {@code DeviceApplication#capabilities}。 */
    private final List<Capability> capabilities;

    /** 本机记的未读数快照: 会话坐标 → 平台告诉她的未读数。仅诊断与日志用。 */
    private final Map<String, Integer> unreadByConversation = new LinkedHashMap<>();

    /** 装在哪台设备上。{@code null} 表示"还没装"或"已卸载"。 */
    private volatile Device host;

    /** 当前会话。{@code null} 表示还没登录(或已登出/已失效)。 */
    private volatile ChatSession session;

    /** 信号监听器只注册一次 —— 重复注册会让一条信号被处理两遍, 也就是手机响两次。 */
    private volatile boolean signalListenerRegistered;

    /** 收到过多少条信号 —— 诊断用。"平台推了 10 条而手机只响 2 条"是一个要能看见的事实。 */
    private volatile int signalsReceived;

    // ═══════════════════════════ 构造 ═══════════════════════════

    /** 生产用的构造器: 时钟取墙上时钟。 */
    public ChatApplication(ChatPlatformGateway gateway, String accountId, String credential) {
        this(gateway, accountId, credential, Instant::now);
    }

    /**
     * 完整构造器。
     *
     * <p>{@code accountId} 与 {@code credential} 是<b>账号绑定的</b>: 一台设备上的聊天应用
     * 代表一个聊天账号, 就像她手机上登录的那个号。多账号的方案是"装两个应用实例",
     * 而不是"一个应用在运行期切换账号" —— 后者会让"这台设备上的聊天软件是谁的"
     * 这个问题在两次调用之间有不同的答案, 而那会让任何基于账号的记账与授权失效。
     *
     * @param gateway    对聊天平台的通道
     * @param accountId  这个应用代表哪个聊天账号
     * @param credential 登录凭据。<b>不要把它打出来</b>
     * @param clock      信号处理用的时钟, 见 {@link #clock}
     */
    public ChatApplication(ChatPlatformGateway gateway, String accountId, String credential,
                           Supplier<Instant> clock) {
        this.gateway = Objects.requireNonNull(gateway,
                "聊天应用必须有一条通往聊天平台的通道 —— 没有它, 这个应用只是个图标");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException(
                    "聊天应用必须知道它代表哪个聊天账号 —— 匿名应用无法做任何会话权限判定");
        }
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException(
                    "聊天应用必须有凭据 —— 不存在'不用登录就能读消息'的客户端");
        }
        this.accountId = accountId;
        this.credential = credential;
        this.clock = Objects.requireNonNull(clock, "信号处理必须有一个时钟");
        this.descriptor = Descriptor.of(APPLICATION_KEY, "聊天", "1.0.0")
                .description("她与别人说话的地方: 会话、消息、免打扰。"
                        + "注意「她看得见消息内容」与「手机响过」是两件事 —— "
                        + "响只是信号, 内容要她主动去看")
                .notificationSound(DEFAULT_SOUND_PROFILE)
                .tags("社交", "平台自带");
        this.capabilities = buildCapabilities();
    }

    // ═══════════════════════════ DeviceApplication ═══════════════════════════

    @Override
    public String id() {
        return APPLICATION_KEY;
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    /** 与任何第三方应用返回的东西同构的能力清单 —— 见 {@link #buildCapabilities()}。 */
    @Override
    public Collection<Capability> capabilities() {
        return capabilities;
    }

    /**
     * 装到设备上了。
     *
     * <p>这里做两件事: 记住宿主、把 {@link #onSignal} 注册到平台上。
     * <b>不在这里登录</b> —— 登录是一个 agent 可见的动作({@code chat.login}),
     * 也是一个需要凭据生效、可能失败的对外调用。把它塞进装配路径的后果是:
     * 平台一挂, 整台手机就装不上应用, 而"手机上有聊天软件但它现在离线"
     * 恰恰是一个需要被表达的真实状态。
     *
     * <p>注册监听器是廉价的本地动作(没有网络), 所以放在这里是合适的 ——
     * {@code DeviceApplication#onAttach} 的注释要求"启动, 不是等完"。
     */
    @Override
    public void onAttach(Device device) {
        this.host = Objects.requireNonNull(device, "要装在哪台设备上? 不能为空");
        if (!signalListenerRegistered) {
            gateway.onSignal(this::onSignal);
            signalListenerRegistered = true;
        }
        log.info("[ChatApplication/{}] 已装到设备 {} 上", accountId, device.id().value());
    }

    /**
     * 从设备上摘下来了 —— <b>断开长连接是这里唯一必须做对的事</b>。
     *
     * <p>不做的后果很具体: 一个已经被卸载的应用会继续收到信号, 继续唤醒一台
     * 已经不该再响的设备, 而这个 bug 在日志里表现为"这台手机卸载了聊天软件还在响"。
     *
     * <p>幂等: 卸载流程可能被调用第二次, 而"断开一条已经断开的连接"不该抛异常。
     */
    @Override
    public void onDetach() {
        this.host = null;
        this.session = null;
        unreadByConversation.clear();
        try {
            gateway.disconnect();
        } catch (RuntimeException e) {
            // 断开失败不该让卸载失败: 状态已经从设备上摘掉了, 抛出去只会让调用方
            // 以为"还装着"。记一条 ERROR 并继续 —— 与 Phone.uninstall 同一处置
            log.error("[ChatApplication/{}] 断开长连接时出错, 但它已被卸载", accountId, e);
        }
        log.info("[ChatApplication/{}] 已从设备上摘下来", accountId);
    }

    // ═══════════════════════════ 链的第 ④⑤⑥ 步 ═══════════════════════════

    /**
     * 平台推来了一条通知信号 —— <b>链的第 ④⑤ 步</b>。
     *
     * <h2>这个方法里被明令禁止的三件事</h2>
     * <ol>
     *   <li><b>不许读正文。</b>不要因为"反正连着平台"就顺手调
     *       {@code readMessages}。见类注释: 那会让"她没注意到"在结构上不可能;</li>
     *   <li><b>不许聚合。</b>一次信号一次响。攒起来合并会抹掉"响了几下"这个事实;</li>
     *   <li><b>不许直接投事件。</b>本应用没有 {@code EventFabric}, 也拿不到 ——
     *       世界的"手机响了"那条事件由<b>设备</b>投({@code NotificationSystem}),
     *       而不是由应用投。这是"应用只能请求, 设备负责决定"的分工。</li>
     * </ol>
     *
     * <h2>没有会话时为什么丢掉而不是排队</h2>
     * 一条 20 分钟前的"新消息"信号在登录后补响, 会让她经历一件现实中不可能的事:
     * 手机在为一条她(和平台)都早已处理过的消息响。丢弃并记一条 WARN,
     * 是对"信号有时效"这件事最诚实的处理。
     */
    public void onSignal(ChatPlatformGateway.NotificationSignal signal) {
        Objects.requireNonNull(signal, "信号不能为空");
        Device device = this.host;
        if (device == null) {
            log.warn("[ChatApplication/{}] 收到信号 {} 但应用未被装载在任何设备上, 丢弃",
                    accountId, signal.describe());
            return;
        }
        ChatSession current = this.session;
        Instant now = clock.get();
        if (current == null) {
            log.warn("[ChatApplication/{}] 收到信号 {} 但还没登录, 丢弃",
                    accountId, signal.describe());
            return;
        }
        if (current.expiredAt(now)) {
            log.warn("[ChatApplication/{}] 会话已于 {} 失效, 信号 {} 被丢弃 —— 需要重新登录",
                    accountId, current.expiresAt(), signal.describe());
            return;
        }

        // 未读快照: 平台是权威, 这里只记账。注意它<b>不</b>随"响不响"变化 ——
        // 手机静音时未读照样 +1, 见 NotificationSystem 类注释
        recordUnread(signal.conversationRef(), signal.unreadCount());
        signalsReceived++;

        log.debug("[ChatApplication/{}] {} → 交给设备 {}", accountId, signal.describe(),
                device.id().value());
        device.notify(toNotification(signal, now));
    }

    /**
     * 把平台信号翻译成一条设备能理解的通知请求 —— <b>链的第 ⑤ 步</b>。
     *
     * <p>它是本类里唯一一处"跨越应用/设备边界"的代码, 所以它被单独抽出来:
     * 一个方法只做一件事(信号 → 请求), 就能被单测直接盯住 ——
     * 而它值得被盯住, 因为<b>正文是在这里被挡在门外的那一道门</b>。
     *
     * <h2>为什么这里<b>没有</b> {@code signal.content()} 可以传</h2>
     * 因为 {@link ChatPlatformGateway.NotificationSignal} 里没有那个字段,
     * {@link NotificationRequest} 里也没有。这个方法是"装不下"这条纪律的
     * 交会点: 任何试图在这里补一个正文的人都必须先改两个 record 的定义 ——
     * 而那是一次<b>看得见</b>的、会被 review 拦下的改动。
     */
    public NotificationRequest toNotification(ChatPlatformGateway.NotificationSignal signal,
                                             Instant now) {
        return NotificationRequest.of(
                APPLICATION_KEY,
                signal.reason(),
                signal.conversationRef(),
                // 平台没指定音色时用本应用自带的 —— 音色是应用的属性,
                // 音量才是设备的属性(见 DeviceApplication.Descriptor 的注释)
                "default".equals(signal.soundProfile())
                        ? descriptor.notificationSound() : signal.soundProfile(),
                signal.unreadCount(),
                now);
    }

    // ═══════════════════════════ 状态读取 ═══════════════════════════

    /** 这个应用代表哪个聊天账号。 */
    public String accountId() {
        return accountId;
    }

    /** 现在登录着吗(有会话且未过期)。 */
    public boolean loggedIn() {
        ChatSession current = this.session;
        return current != null && !current.expiredAt(clock.get());
    }

    /**
     * 当前会话 —— <b>刻意返回 {@link ChatSession} 本体而不是 token</b>。
     *
     * <p>需要 token 的地方只有 {@code ChatPlatformGateway} 自己(它拿整个会话)。
     * 把 token 从这里取出来传给别人, 就是给它多开了一条泄露路径。
     */
    public Optional<ChatSession> session() {
        return Optional.ofNullable(session);
    }

    /** 本机记的某个会话未读数 —— 没有记录时返回 0。 */
    public int unreadOf(String conversationRef) {
        return unreadByConversation.getOrDefault(conversationRef, 0);
    }

    /** 本机记的全部未读数快照 —— 顺序稳定, 只读。 */
    public Map<String, Integer> unreadSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unreadByConversation));
    }

    /** 收到过多少条信号 —— 诊断面板用它回答"平台到底推了几条"。 */
    public int signalsReceived() {
        return signalsReceived;
    }

    /** 一行摘要 —— 日志与诊断用。<b>不含 token, 不含正文</b>。 */
    public String describe() {
        return "聊天应用[" + accountId + (loggedIn() ? " 已登录" : " 未登录")
                + " 收到信号 " + signalsReceived + " 条"
                + " 未读会话 " + unreadByConversation.size() + " 个]";
    }

    // ═══════════════════════════ 能力清单 ═══════════════════════════

    /**
     * 本应用贡献的全部能力。
     *
     * <p>构造一次并缓存 —— {@code CapabilityDescriptor} 会被频繁读取(拼 LLM 工具清单、
     * 渲染前端面板), 每次调用新建一份会让"她有很多能力"变成一次真实的卡顿。
     *
     * <p>顺序与 §6.3 的客户端动作目录一致: 登录在前, 其余的按"她平时会用的顺序"
     * 排列(看列表 → 读 → 发 → 已读 → 设置 → 看资料)。顺序不改变路由, 但改变
     * LLM 读到清单时的理解顺序 —— 而工具清单的顺序真的会影响它的选择。
     */
    private List<Capability> buildCapabilities() {
        List<Capability> list = new ArrayList<>();

        list.add(action(CapabilityDescriptor.of(Capabilities.LOGIN)
                .title("登录聊天平台")
                .description("登录聊天软件, 建立长连接。成功后她才能收发消息。"
                        + "凭据来自应用配置, 不需要也不接受参数 —— "
                        + "登录是'打开软件', 不是'背一遍密码'")
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("chat", "session")
                .build(), this::login));

        list.add(action(CapabilityDescriptor.of(Capabilities.LOGOUT)
                .title("登出聊天平台")
                .description("断开长连接并清掉本地会话。登出后她不再收到任何消息信号, "
                        + "但聊天平台上的消息不会丢 —— 重新登录就能看到")
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("chat", "session")
                .build(), this::logout));

        list.add(action(CapabilityDescriptor.of(Capabilities.LIST_CONVERSATIONS)
                .title("看会话列表")
                .description("看看谁在找她: 每个会话的对方账号、未读条数、最后活动时间、"
                        + "有没有免打扰。<b>不含消息内容</b> —— 内容要她点进去看(chat.read-messages)")
                .parameters(Map.<String, Object>of(
                        "keyword", Map.of("type", "string", "description", "搜索词, 空则列出全部"),
                        "limit", Map.of("type", "integer", "description", "最多几条, 默认 50")))
                .sideEffect(CapabilityDescriptor.SideEffect.READ)
                .tags("chat", "conversation")
                .build(), this::listConversations));

        list.add(action(CapabilityDescriptor.of(Capabilities.READ_MESSAGES)
                .title("读消息")
                .description("打开某个会话看她与对方的往来消息 —— <b>这是消息内容的唯一入口</b>。"
                        + "手机响过只代表'有人找她', 内容要她这一步才看得到")
                .parameters(Map.<String, Object>of(
                        "accountId", Map.of("type", "string", "description", "与谁的会话(对方账号 id)"),
                        "limit", Map.of("type", "integer", "description", "这一页几条, 默认 20"),
                        "cursor", Map.of("type", "string", "description", "上翻用, 留空为最新一页")))
                .sideEffect(CapabilityDescriptor.SideEffect.READ)
                .tags("chat", "message")
                .build(), this::readMessages));

        list.add(action(CapabilityDescriptor.of(Capabilities.SEND_MESSAGE)
                .title("发消息")
                .description("给某个会话发一条消息。发出去就收不回来了")
                .parameters(Map.<String, Object>of(
                        "accountId", Map.of("type", "string", "description", "发给谁(对方账号 id)"),
                        "content", Map.of("type", "string", "description", "消息内容"),
                        "kind", Map.of("type", "string", "description", "默认 TEXT")))
                .sideEffect(CapabilityDescriptor.SideEffect.IRREVERSIBLE)
                .requiresConfirmation(false)
                .tags("chat", "message")
                .build(), this::sendMessage));

        list.add(action(CapabilityDescriptor.of(Capabilities.MARK_READ)
                .title("标记已读")
                .description("把若干条消息标成已读。<b>'她看过了'是一次声明, 不是一次读取</b> —— "
                        + "所以它与 chat.read-messages 是两件事(她可以扫一眼但没点掉红点)")
                .parameters(Map.<String, Object>of(
                        "accountId", Map.of("type", "string"),
                        "messageIds", Map.of("type", "array", "description", "要标已读的消息 id 列表")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("chat", "message")
                .build(), this::markRead));

        list.add(action(CapabilityDescriptor.of(Capabilities.SET_MUTE)
                .title("设置消息免打扰")
                .description("把某个会话设为消息免打扰。注意这是<b>聊天平台</b>的设置: "
                        + "设成免打扰之后平台<b>一条信号都不发</b>, 手机根本不会响; "
                        + "未读红点仍然会涨。这与'手机静音'"
                        + "(device.phone.set-notification-policy)是两件事")
                .parameters(Map.<String, Object>of(
                        "accountId", Map.of("type", "string"),
                        "muted", Map.of("type", "boolean", "description", "true 表示免打扰")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("chat", "notification")
                .build(), this::setMute));

        list.add(action(CapabilityDescriptor.of(Capabilities.CONTACT_PROFILE)
                .title("看某人资料")
                .description("看一个聊天账号的公开资料。<b>名字通常是空的</b> —— "
                        + "平台不提供用户的备注, '这个人是谁'要她自己聊出来、自己记")
                .parameters(Map.<String, Object>of("accountId", Map.of("type", "string")))
                .sideEffect(CapabilityDescriptor.SideEffect.READ)
                .tags("chat", "contact")
                .build(), this::contactProfile));

        return List.copyOf(list);
    }

    // ═══════════════════════════ 能力的实现 ═══════════════════════════

    /**
     * {@code chat.login}。
     *
     * <p>已经登录且会话没过期时<b>不重复登录</b>: 重复登录会在平台侧留下一条新会话,
     * 而旧会话可能被平台作废 —— 表现是"她每隔一会儿就要重新登录一次"。
     * 这里返回 SUCCEEDED 并把 {@code alreadyLoggedIn} 放进返回值, 让调用方看得见
     * "什么都没发生", 而不是靠猜。
     */
    private ActionResult login(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.LOGIN;
        ChatSession current = this.session;
        if (current != null && !current.expiredAt(context.now())) {
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", current.accountId(),
                    "expiresAt", current.expiresAt().toString(),
                    "alreadyLoggedIn", true));
        }
        try {
            ChatSession fresh = gateway.login(new ChatPlatformGateway.LoginRequest(
                    accountId, credential, "device"));
            this.session = fresh;
            gateway.connect(fresh);
            log.info("[ChatApplication/{}] 登录成功, 有效期至 {}", accountId, fresh.expiresAt());
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", fresh.accountId(),
                    "expiresAt", fresh.expiresAt().toString()));
        } catch (RuntimeException e) {
            // 登录失败是 FAILED 而不是 REJECTED: 参数没有问题(根本没有参数),
            // 出问题的是平台那一侧或配置 —— 那是一件"需要被看见和修"的事
            log.warn("[ChatApplication/{}] 登录失败: {}", accountId, e.toString());
            return ActionResult.failed(key, context.now(),
                    "登录聊天平台失败: " + e.getClass().getSimpleName()
                            + " —— 这不是参数问题, 而是平台侧或配置问题");
        }
    }

    /** {@code chat.logout} —— 幂等: 没登录时登出返回成功, 因为结果已经是"登出状态"。 */
    private ActionResult logout(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.LOGOUT;
        this.session = null;
        unreadByConversation.clear();
        try {
            gateway.disconnect();
        } catch (RuntimeException e) {
            log.warn("[ChatApplication/{}] 登出时断开连接出错: {}", accountId, e.toString());
        }
        return ActionResult.succeeded(key, context.now(), Map.of("accountId", accountId));
    }

    private ActionResult listConversations(ActionCommand command,
                                           Capability.CapabilityContext context) {
        String key = Capabilities.LIST_CONVERSATIONS;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        try {
            String keyword = command.str("keyword").filter(s -> !s.isBlank()).orElse(null);
            int limit = command.integer("limit").orElse(50);
            ChatPlatformGateway.ConversationPage page =
                    gateway.listConversations(new ChatPlatformGateway.ConversationQuery(
                            keyword, true, null, limit));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ChatPlatformGateway.ConversationSummary c : page.conversations()) {
                // 逐字段搬运而不是直接回传 record: 这样"哪些字段出了这个应用"
                // 在代码里是<b>看得见</b>的。哪天有人想加一个 preview 字段,
                // 他必须在这一行显式地写下来, 而那一行会被 review 看见
                rows.add(Map.of(
                        "accountId", c.accountId(),
                        "unreadCount", c.unreadCount(),
                        "lastActivityAt", String.valueOf(c.lastActivityAt()),
                        "pinned", c.pinned(),
                        "muted", c.muted()));
            }
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "conversations", List.copyOf(rows),
                    "hasMore", page.hasMore(),
                    "nextCursor", page.nextCursor() == null ? "" : page.nextCursor()));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "看会话列表", e);
        }
    }

    /**
     * {@code chat.read-messages} —— <b>正文的唯一入口</b>(§9 验收标准 E)。
     *
     * <p>它只读: 读过<b>不</b>等于已读。真人也是这样 —— 扫一眼不点掉红点是常态,
     * 而"她看过但装着没看见"这种状态只有在两者分开之后才表达得出来。
     *
     * <p>返回的正文<b>不会</b>被本应用保存: 它是返回值, 不是状态。缓存它等于在
     * 应用里建了第二份正文, 而那一份不受任何"她主动去看"的约束。
     */
    private ActionResult readMessages(ActionCommand command,
                                      Capability.CapabilityContext context) {
        String key = Capabilities.READ_MESSAGES;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        String peer = command.str("accountId").orElse(null);
        if (peer == null || peer.isBlank()) {
            return ActionResult.rejected(key, context.now(),
                    "要读与谁的会话? 需要 accountId(对方账号 id)");
        }
        int limit = Math.max(1, Math.min(command.integer("limit").orElse(20), 100));
        String cursor = command.str("cursor").filter(s -> !s.isBlank()).orElse(null);
        try {
            ChatPlatformGateway.MessagePage page = gateway.readMessages(peer,
                    cursor == null ? null : new ChatPlatformGateway.MessageCursor(cursor), limit);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ChatPlatformGateway.ClientMessage m : page.messages()) {
                rows.add(Map.of(
                        "messageId", m.messageId(),
                        "senderAccountId", m.senderAccountId(),
                        "kind", m.kind(),
                        "content", m.content(),
                        "sentAt", String.valueOf(m.sentAt()),
                        "deliveryStatus", m.deliveryStatus()));
            }
            // 日志里只记条数, 不记内容 —— 与 ActionResult.describe() 不打 data 同一个理由
            log.debug("[ChatApplication/{}] 读了与 {} 的 {} 条消息", accountId, peer, rows.size());
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", peer,
                    "messages", List.copyOf(rows),
                    "hasMore", page.hasMore(),
                    "nextCursor", page.nextCursor() == null ? "" : page.nextCursor()));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "读消息", e);
        }
    }

    /**
     * {@code chat.send-message} —— 副作用<b>不可撤销</b>。
     *
     * <h2>幂等键从哪来</h2>
     * 优先用 {@link ActionCommand#idempotencyKey()}(调用方给的), 没有时退化为
     * {@code commandId}。<b>两者都不是"我们自己现编一个"</b> —— 一个每次调用都新生成的
     * 键等于没有键, 因为重试的正是同一条命令(它带着同一个 {@code commandId})。
     *
     * <p>跨命令的重试(她重新决定"再发一次")应当带<b>新的</b>幂等键: 那是真的想发第二条。
     * 这两件事的区别只有调用方知道, 所以键由调用方给, 而不是由这里猜。
     */
    private ActionResult sendMessage(ActionCommand command,
                                     Capability.CapabilityContext context) {
        String key = Capabilities.SEND_MESSAGE;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        String peer = command.str("accountId").orElse(null);
        if (peer == null || peer.isBlank()) {
            return ActionResult.rejected(key, context.now(), "发给谁? 需要 accountId");
        }
        String content = command.str("content").orElse(null);
        if (content == null || content.isEmpty()) {
            // 空消息被拒绝而不是"发一条空的": 平台上一条空消息是对方的困惑,
            // 而不是她的表达。想表达"什么都没说"的方式是不发
            return ActionResult.rejected(key, context.now(),
                    "消息内容不能为空 —— 想表达'不回复'的方式是不发送");
        }
        String kind = command.str("kind").orElse(ChatPlatformGateway.MessageKinds.TEXT);
        String idempotencyKey = command.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = command.commandId();
        }
        try {
            ChatPlatformGateway.SendResult result = gateway.sendMessage(
                    new ChatPlatformGateway.SendMessageCommand(peer, kind, content, idempotencyKey));
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "messageId", result.messageId(),
                    "accountId", peer,
                    "sentAt", String.valueOf(result.sentAt())));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "发消息", e);
        }
    }

    private ActionResult markRead(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.MARK_READ;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        String peer = command.str("accountId").orElse(null);
        if (peer == null || peer.isBlank()) {
            return ActionResult.rejected(key, context.now(), "哪个会话? 需要 accountId");
        }
        List<String> ids = stringList(command, "messageIds");
        if (ids.isEmpty()) {
            // 刻意不支持"整个会话已读"的简写: 那会让"她读到了哪一条"变得不可知,
            // 而"她读到第 3 条就没看了"是一个真实且重要的状态
            return ActionResult.rejected(key, context.now(),
                    "标已读需要 messageIds —— 逐条标记是有意的, 好让'她读到哪一条'可查");
        }
        try {
            gateway.markRead(peer, ids);
            // 本机的未读快照跟着归零: 平台是权威, 但我们刚刚看到它已经变了
            unreadByConversation.put(peer, 0);
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", peer, "marked", ids.size()));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "标记已读", e);
        }
    }

    /**
     * {@code chat.set-mute} —— 改的是<b>聊天平台</b>的消息免打扰设置(§6.2)。
     *
     * <p>它<b>不碰</b>手机的任何东西: 手机有没有静音由
     * {@code device.phone.set-notification-policy} 管, 而那是另一件事。
     * 一个把两者合成一个能力的实现会让"平台没发"与"手机没响"从此无法区分,
     * 而区分它们正是 {@link ChatPlatformGateway#setNotification} 与
     * {@code NotificationPolicy} 各占一层的原因。
     */
    private ActionResult setMute(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.SET_MUTE;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        String peer = command.str("accountId").orElse(null);
        if (peer == null || peer.isBlank()) {
            return ActionResult.rejected(key, context.now(), "哪个会话? 需要 accountId");
        }
        Boolean muted = command.bool("muted").orElse(null);
        if (muted == null) {
            return ActionResult.rejected(key, context.now(),
                    "要设成免打扰还是取消? 需要 muted(true/false)");
        }
        try {
            ChatPlatformGateway.ConversationNotificationSetting setting =
                    gateway.setNotification(peer, muted);
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", setting.conversationId(),
                    "muted", setting.muted(),
                    "pinned", setting.pinned(),
                    "updatedAt", String.valueOf(setting.updatedAt()),
                    // 把分工写进返回值, 让 LLM 看得见"这不会让手机静音"
                    "note", "这是聊天平台的设置: 免打扰后平台不再发通知信号。"
                            + "要让手机本身不响, 用 device.phone.set-notification-policy"));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "设置免打扰", e);
        }
    }

    /**
     * {@code chat.contact-profile} —— 名字通常是空的, <b>那是设计而不是缺失</b>。
     *
     * <p>平台不给备注名, 于是"这个人是谁"只能靠她自己聊出来、自己记。
     * 这一条同时满足两个目标: 产品上她要<b>构建关系网</b>, 隐私上平台不该把
     * 用户的备注体系交给 agent。
     */
    private ActionResult contactProfile(ActionCommand command,
                                        Capability.CapabilityContext context) {
        String key = Capabilities.CONTACT_PROFILE;
        Optional<ActionResult> guard = sessionGuard(key, context.now());
        if (guard.isPresent()) {
            return guard.get();
        }
        String peer = command.str("accountId").orElse(null);
        if (peer == null || peer.isBlank()) {
            return ActionResult.rejected(key, context.now(), "看谁的资料? 需要 accountId");
        }
        try {
            Optional<ChatPlatformGateway.ContactProfile> profile = gateway.contactProfile(peer);
            if (profile.isEmpty()) {
                // "查无此人"与"查到了但没名字"是两件事, 所以这里用 REJECTED 而不是
                // 返回一个空资料 —— 后者会让"这个人不存在"看起来像"这个人没名字"
                return ActionResult.rejected(key, context.now(),
                        "平台上没有 " + peer + " 这个账号(或它对当前身份不可见)");
            }
            ChatPlatformGateway.ContactProfile p = profile.get();
            return ActionResult.succeeded(key, context.now(), Map.of(
                    "accountId", p.accountId(),
                    // 空名字 → 空串。刻意<b>不</b>用 accountId 兜底填进名字字段:
                    // 那会让"她给他起了个名字"与"平台给了个名字"再也分不开
                    "displayName", p.displayName() == null ? "" : p.displayName(),
                    "avatarUrl", p.avatarUrl() == null ? "" : p.avatarUrl(),
                    "named", p.named()));
        } catch (RuntimeException e) {
            return platformFailure(key, context.now(), "看资料", e);
        }
    }

    // ═══════════════════════════ 内部工具 ═══════════════════════════

    /**
     * "还没登录"这条守卫 —— 每个需要会话的能力第一行都调它。
     *
     * <p>返回 {@link ActionResult#unavailable} 而不是 {@code REJECTED}:
     * 没登录是一个<b>暂时的</b>状态(先调 {@code chat.login} 就好了), 而
     * {@code REJECTED} 的含义是"这么做不行"。用错的那一个会让决策层得到
     * "这条路走不通"的错误结论, 而真相只是"还没开门"。
     */
    private Optional<ActionResult> sessionGuard(String capabilityKey, Instant now) {
        Device device = this.host;
        if (device == null) {
            return Optional.of(ActionResult.unavailable(capabilityKey, now,
                    "聊天应用没有被装在任何设备上 —— 先把它装到一台设备上"));
        }
        if (!device.usable()) {
            return Optional.of(ActionResult.unavailable(capabilityKey, now,
                    "设备不可用(" + device.power() + ") —— 没电的手机连不上聊天平台"));
        }
        ChatSession current = this.session;
        if (current == null) {
            return Optional.of(ActionResult.unavailable(capabilityKey, now,
                    "还没登录聊天平台 —— 先调用 " + Capabilities.LOGIN));
        }
        if (current.expiredAt(now)) {
            // 会话过期与"没登录"分开报: 这两句话指向两个不同的处置(重新登录 vs 先登录),
            // 而合成一句会让人以为"我明明登录过了"
            return Optional.of(ActionResult.unavailable(capabilityKey, now,
                    "会话已于 " + current.expiresAt() + " 过期 —— 重新调用 " + Capabilities.LOGIN));
        }
        return Optional.empty();
    }

    /**
     * 平台侧出问题时的统一处置。
     *
     * <p>一律 {@link ActionResult#failed} 而不是 {@code UNAVAILABLE}:
     * 网关抛异常表示<b>调用没有正常完成</b>(平台挂了、协议不对、反序列化失败),
     * 而不是"暂时够不着"。区别在重试策略上 —— 一个把异常当成"待会儿再试"的系统
     * 会在平台宕机时把所有 agent 变成重试机器。
     *
     * <p>消息里<b>只放异常类型</b>, 不放 {@code getMessage()}:
     * 网关的异常消息里可能带着请求参数, 而请求参数里有正文档可能还有凭据。
     */
    private ActionResult platformFailure(String capabilityKey, Instant now, String what,
                                         RuntimeException cause) {
        log.warn("[ChatApplication/{}] {} 失败: {}", accountId, what, cause.toString());
        return ActionResult.failed(capabilityKey, now,
                what + "失败: " + cause.getClass().getSimpleName() + " —— 见应用日志");
    }

    private void recordUnread(String conversationRef, int unreadCount) {
        if (conversationRef == null || conversationRef.isBlank()) {
            return;
        }
        unreadByConversation.put(conversationRef, Math.max(0, unreadCount));
    }

    /**
     * 从一个参数里取字符串数组。
     *
     * <p>宽容地接受 {@code List} 与单个字符串: 参数来自 LLM, 而它把数组写成
     * 一个元素的字符串、或者把字符串写成数组, 都是它的正常工作方式 ——
     * 见 {@code ActionCommand} 类注释"参数为什么是 JSON 形状的 Map"。
     */
    private static List<String> stringList(ActionCommand command, String key) {
        Object raw = command.arguments().get(key);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item));
                }
            }
            return List.copyOf(values);
        }
        String single = String.valueOf(raw);
        return single.isBlank() ? List.of() : List.of(single);
    }

    /** 会话剩余有效期 —— 诊断面板用它显示"还有多久过期"。 */
    public Optional<Duration> sessionRemaining() {
        ChatSession current = this.session;
        return current == null ? Optional.empty() : Optional.of(current.remainingAt(clock.get()));
    }

    // ═══════════════════════════ 能力的小工具 ═══════════════════════════

    /**
     * "描述 + 一段实现"的能力。
     *
     * <p>它与 {@code Phone} 里那个私有 record 长得一样, <b>这里是刻意重复的</b>:
     * {@code Capability} 有两个方法所以不是函数式接口, 而把一个 lambda 变成
     * {@code Capability} 需要一个适配器。把那个适配器提成公共类型会引入一个
     * 只为省 12 行而存在的文件 —— 而它一旦公共, 就会有人往它上面加东西。
     *
     * <p>重复的那 12 行不会漂移(它们没有任何业务逻辑), 所以这里的取舍是
     * "容忍一处无害的重复, 换掉一个会长大的公共类型"。
     */
    private static record AppCapability(
            CapabilityDescriptor descriptor,
            BiFunction<ActionCommand, Capability.CapabilityContext, ActionResult> body)
            implements Capability {

        @Override
        public ActionResult invoke(ActionCommand command, Capability.CapabilityContext context) {
            return body.apply(command, context);
        }
    }

    private static Capability action(CapabilityDescriptor descriptor,
                                     BiFunction<ActionCommand, Capability.CapabilityContext,
                                             ActionResult> body) {
        return new AppCapability(descriptor, body);
    }
}
