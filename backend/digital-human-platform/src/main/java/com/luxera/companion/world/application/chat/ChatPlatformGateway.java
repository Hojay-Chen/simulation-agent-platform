package com.luxera.companion.world.application.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §6.4 —— <b>聊天平台的客户端网关</b>: 聊天应用对聊天平台的<b>唯一</b>认知。
 *
 * <h2>用户为什么要求它长得像 SDK 而不是 DAO</h2>
 * <blockquote>
 *   我要的效果就是这种聊天平台提供覆盖聊天平台的全部功能的接口并且符合我们真人平时使用时
 *   调用接口的逻辑, 甚至于你可以让<b>聊天平台前端和 agent 调用的是同一套聊天平台后端接口</b>
 *   (但这里要解决授权问题)。
 * </blockquote>
 * 这句话决定了本接口的每一个方法名: 它们对应的是<b>真人点开聊天软件会做的动作</b>
 * (登录 / 看会话列表 / 点开某人 / 上翻 / 打字发送 / 已读 / 设免打扰 / 看资料 / 搜索),
 * 而不是"读一张表"。所以它是 {@code login / listConversations / readMessages ...},
 * 不是 {@code messages(conversationId)}。
 *
 * <h2>{@code ChatWorldPort} 为什么被它取代</h2>
 * 设计文档 §6.4 写得很直接: 现有那个可以直接
 * {@code messages(conversationId)} 读全量的接口<b>不满足 V2.2 的要求</b>。
 * 理由是它把"聊天平台"当成了一个数据库 —— 而数据库没有权限、没有分页、
 * 没有"未读"、没有免打扰。一个能一次读全量的端口会让下面这三件事同时失效:
 * <ol>
 *   <li>三层授权里的第 ② 层(会话权限)—— 直读不需要令牌;</li>
 *   <li>"逐条通知"的语义 —— 直读看到的是结果, 不是"她收到了几个信号";</li>
 *   <li>"正文只能经 readMessages 进入认知"这条纪律 —— 直读就是那条捷径。</li>
 * </ol>
 *
 * <h2>三层授权, 互不替代</h2>
 * <pre>
 *   ① 平台授权     谁能访问这台服务器         —— JWT / API Key
 *   ② 应用会话     这个聊天账号能做什么        —— {@link ChatSession} 里的 token
 *   ③ 能力授权     agent 能不能执行这个 Action —— CapabilityDescriptor + ActionFabric
 * </pre>
 * 本接口只承担第 ② 层。<b>它不检查第 ③ 层</b> —— 那个判断发生在
 * {@code DefaultActionFabric} 的 5 步里(见其类注释), 而把它复制一份到这里,
 * 会得到两份各自漂移的授权逻辑。
 *
 * <h2>它<b>不</b>做什么</h2>
 * <ul>
 *   <li><b>不做通知聚合。</b>连接上推来的信号是<b>一条一个</b>的, 见
 *       {@link NotificationSignal}。"你收到 3 条新消息"式的合并信号在本设计里是错的
 *       —— 用户明确要求逐条通知;</li>
 *   <li><b>不做免打扰判定。</b>那是聊天平台自己的事(§6.2), 手机侧拿不到被拦掉的信号;</li>
 *   <li><b>不返回正文的地方绝不返回正文。</b>只有 {@link #readMessages} 返回
 *       {@link ClientMessage#content()}, 其余任何地方(尤其是信号)都没有它。</li>
 * </ul>
 */
public interface ChatPlatformGateway {

    // ═══════════════════════════ ① 登录 ═══════════════════════════

    /**
     * 用聊天账号登录 —— <b>就像真人打开聊天软件时发生的那一步</b>。
     *
     * <p>它必须拿回一个真的 {@link ChatSession}(带 token、带有效期), 而不是一个
     * "已登录"的布尔。理由见 {@link ChatSession} 的类注释: token 是第 ② 层授权的载体,
     * 而"内部对象可以直接操作"这条路必须被堵死。
     *
     * <p>失败(账号不存在/密码不对/被风控)应当抛出网关自己的异常或返回一个
     * 特殊会话 —— 但不应当 返回 null: 一个 null 会话会在第一次调用时炸出一个
     * 与"登录失败"毫无关系的 NPE。
     */
    ChatSession login(LoginRequest request);

    /**
     * 建立 WebSocket 长连接(§6.6)。
     *
     * <p>连接成功后, 服务端推来的每一条 {@link NotificationSignal} 都会交给
     * {@link #onSignal} 注册过的监听器。<b>补发机制在这里</b>: 断线重连后服务端
     * 会比对 {@code lastAckSignalId} 把错过的信号补推过来 ——
     * 而补推的仍然只是信号, <b>不是正文</b>。
     */
    void connect(ChatSession session);

    /**
     * 注册信号监听器 —— 连接上推来的东西都从这里进。
     *
     * <p>存在的理由是"推"是异步的: {@code connect} 返回时一条消息都还没来。
     * 没有注册点的话, 长连接就只是一个连上了但什么都不做的套接字。
     *
     * @param listener 收到信号时做什么。它<b>应当很快返回</b> —— 在推送线程上
     *                 做耗时的事会把后面的信号堵在缓冲里, 而表现是"她手机响得越来越晚"
     */
    void onSignal(SignalListener listener);

    /** 断开长连接。幂等: 断开一条已经断开的连接不该抛异常。 */
    void disconnect();

    /** 现在连着吗。 */
    boolean connected();

    // ═══════════════════════════ ② 会话与消息 ═══════════════════════════

    /**
     * 看会话列表 —— 真人打开聊天软件的第一屏。
     *
     * <p>返回里<b>没有消息正文</b>, 只有"谁、几条未读、最后活动时间、有没有免打扰"。
     * 这不是省流量, 是分工: 会话列表的作用是让她知道"哪里有人在找她",
     * 而她要不要看详情是下一个决定(见 {@link #readMessages})。
     */
    ConversationPage listConversations(ConversationQuery query);

    /**
     * 读一段会话的消息 —— <b>正文的唯一入口</b>(§9 验收标准 E)。
     *
     * <p>"消息正文不能被绕过本方法获得"这条纪律在有 "手机响了" 这件事之后才成立:
     * 没有它, 设计就退化成"消息一到她就知道了", 而"她没注意到"这个状态
     * 在结构上就不可能为真。
     *
     * @param accountId 与谁的会话(对方账号 id)
     * @param cursor    从哪一页开始。{@code null} 表示最新一页
     * @param limit     这一页要几条。分页由聊天平台控制(与真实客户端一致):
     *                  客户端不指定 offset, 因为"消息在被不断插入"的时候
     *                  offset 分页会重复或漏掉消息, 而游标不会
     */
    MessagePage readMessages(String accountId, MessageCursor cursor, int limit);

    /**
     * 发一条消息。
     *
     * <p>{@link SendMessageCommand#idempotencyKey()} 是<b>必须</b>的:
     * 她按下发送之后网络超时, 重试必须保证"她不会连发两条一样的"。
     * 重复投递只执行一次 —— 这是平台侧的责任, 不是聊天应用自己记一个
     * "我刚才发过"的集合(那个集合在进程重启后就没了)。
     */
    SendResult sendMessage(SendMessageCommand command);

    /**
     * 标记已读。
     *
     * <p>它<b>不</b>返回新内容: 已读是一个"她看过了"的声明, 不是一次读取。
     * 把两件事合成一个方法会让"她只是扫了一眼没细看"这个状态无法表达。
     */
    void markRead(String accountId, Collection<String> messageIds);

    /**
     * 设置某个会话的消息免打扰 —— <b>这是聊天平台的状态, 不是 agent 的状态</b>(§6.2)。
     *
     * <p>打开之后, 平台<b>一条信号都不会发</b>, 于是手机那边"什么都没收到" ——
     * 未读计数仍然 +1(她打开聊天软件时能看到红点)。
     *
     * <p>它返回设置之后的完整状态而不是 {@code void}: "设完了到底是什么样"是调用方
     * 一定要问的问题(诊断面板、"她刚才到底改成什么了"), 让它再查一次就是一次多余的往返。
     */
    ConversationNotificationSetting setNotification(String accountId, boolean muted);

    /**
     * 看某人的资料。
     *
     * <h2>⚠️ {@code displayName} 默认是空的</h2>
     * 设计文档 §6.3 第 9 项写得很清楚: 返回的
     * {@link ContactProfile#displayName()} <b>默认没有值</b>。理由是
     * "这个人叫什么"是<b>她自己的知识</b> —— 聊天平台只给账号 id,
     * 名字由她自己聊出来、自己记({@code RelationshipGraph})。
     *
     * <p>这既是产品要求(用户原话: "聊天账号 Id 对应的是谁, 这是 agent 自己聊天的时候
     * 需要构建的关系网"), 也顺手解决了一个隐私问题: <b>平台不需要向 agent 暴露
     * 用户的备注体系</b>。
     *
     * @return 空表示"这个账号不存在/不可见" —— 与"存在但没名字"是两件事,
     *         所以用 {@code Optional} 而不是一个空对象
     */
    Optional<ContactProfile> contactProfile(String accountId);

    // ═══════════════════════════ 连接上的东西 ═══════════════════════════

    /** 收到信号时做什么。函数式接口 —— 它只有一个动作, 不需要一个类。 */
    @FunctionalInterface
    interface SignalListener {

        /**
         * 服务端推来了一条通知信号。
         *
         * <p>实现通常只有三行: 把信号翻译成 {@code NotificationRequest},
         * 交给设备({@code Device.notify}), 记一条 debug 日志。
         * <b>不要在这里读正文</b> —— 那会把整条"她先听见、再决定看不看"的链压成
         * "她自动知道一切"。
         */
        void onSignal(NotificationSignal signal);
    }

    // ═══════════════════════════ 协议数据 ═══════════════════════════

    /**
     * 登录请求。
     *
     * @param accountId  要登录的聊天账号(agent 自己的账号, 如 {@code agent_m3k9})
     * @param credential 凭据。<b>不要把它写进日志或事件</b> —— 与 {@link ChatSession} 同理
     * @param deviceLabel 这台"设备"怎么自称。真实聊天软件靠它做多端管理,
     *                    而它在这里的作用是让"她在一台手机上登录了"这件事可见
     */
    record LoginRequest(String accountId, String credential, String deviceLabel) {

        public LoginRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("登录必须给账号 —— 匿名登录拿不到任何权限");
            }
            if (credential == null || credential.isBlank()) {
                throw new IllegalArgumentException("登录必须给凭据");
            }
            deviceLabel = deviceLabel == null || deviceLabel.isBlank() ? "unknown" : deviceLabel;
        }

        /** 覆盖 toString —— record 默认会打印 credential。 */
        @Override
        public String toString() {
            return "LoginRequest[" + accountId + " @" + deviceLabel + "]";
        }
    }

    /**
     * 查会话列表的条件。
     *
     * @param keyword     搜索词 —— 对应 §6.3 第 10 项"搜索会话"。空表示不筛
     * @param includeMuted 要不要把免打扰的会话也列出来。默认<b>要</b>:
     *                     真人看得见自己免打扰过的群, 只是不会被它打扰
     */
    record ConversationQuery(String keyword, boolean includeMuted, String cursor, int limit) {

        /** 默认查询: 前 50 个, 包含免打扰的。 */
        public static ConversationQuery first() {
            return new ConversationQuery(null, true, null, 50);
        }

        public static ConversationQuery of(String keyword) {
            return new ConversationQuery(keyword, true, null, 50);
        }

        public ConversationQuery {
            cursor = cursor == null || cursor.isBlank() ? null : cursor;
            if (limit <= 0) {
                limit = 50;
            }
            // 上限刻意存在: 一个"要多少给多少"的接口会被 LLM 用
            // {@code limit=100000} 调一次, 而那一次会把整个会话表拉进内存
            limit = Math.min(limit, 200);
        }
    }

    /**
     * 会话列表里的一行。
     *
     * <p>它<b>没有</b>消息正文, 也<b>没有</b>对方的姓名 —— 只有账号 id。
     * 两处缺失都是刻意的, 理由见 {@link #contactProfile} 与类注释。
     *
     * @param unreadCount   这个会话里她还没看的条数 —— 就是她看到的那个红点
     * @param lastActivityAt 最后一次活动时刻。列表按它倒序 —— 与所有真实聊天软件一致
     * @param muted         这个会话是不是被她免打扰了(<b>平台侧</b>的设置, 不是手机侧)
     */
    record ConversationSummary(String accountId, int unreadCount, Instant lastActivityAt,
                              boolean pinned, boolean muted) {

        public ConversationSummary {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("会话必须有一个对方账号 id");
            }
            unreadCount = Math.max(0, unreadCount);
        }
    }

    /**
     * 一页会话。
     *
     * @param nextCursor 下一页的游标; {@code null} 表示没有更多了。
     *                   分页逻辑刻意由聊天平台控制(§6.3 原文: "分页由聊天平台控制"),
     *                   客户端只负责把它原样带回来
     */
    record ConversationPage(List<ConversationSummary> conversations, String nextCursor,
                           boolean hasMore) {

        public ConversationPage {
            conversations = conversations == null ? List.of() : List.copyOf(conversations);
            nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
            // hasMore 与 nextCursor 不一致时以 nextCursor 为准 —— 两个字段表达同一件事,
            // 而不一致的那一次会让客户端要么漏一页、要么空转一次
            hasMore = hasMore && nextCursor != null;
        }

        public static ConversationPage empty() {
            return new ConversationPage(List.of(), null, false);
        }
    }

    /**
     * 消息分页游标。
     *
     * <p>它<b>不是</b> offset。理由: 消息在被不断插入, 而 offset 分页在数据变化时
     * 会重复或漏掉消息 —— 表现是"她上翻时看到同一条消息两次", 或者"跳过了用户
     * 那三句话中间的一句"。游标是"从这个位置继续", 与插入无关。
     */
    record MessageCursor(String value) {

        public MessageCursor {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "游标不能为空 —— 想从最新一页开始请传 null 而不是一个空游标");
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * 一条消息 —— <b>正文只在这里出现</b>。
     *
     * <h2>{@code content} 是 §9 验收标准 E 的对象</h2>
     * 标准 E 要求"消息正文不能被绕过 {@code chat.read-messages} 获得"。
     * 这条纪律靠三件事共同保证, 缺一不可:
     * <ol>
     *   <li>{@link NotificationSignal} 里<b>没有</b> content 字段(类型层面就没有);</li>
     *   <li>{@code NotificationRequest} 里也没有(同上);</li>
     *   <li>{@code device.phone.notification-raised.v1} 的载荷里也没有 ——
     *       于是"她的 Mind"第一次见到正文的时刻, 只可能是她主动调用了
     *       {@code chat.read-messages}。</li>
     * </ol>
     *
     * @param senderAccountId 谁发的 —— <b>账号 id, 不是姓名</b>
     * @param kind            消息种类。取值见 {@link MessageKinds}, 但<b>不是白名单</b>:
     *                        聊天平台可以新增种类(语音、位置、转账), 而平台不该
     *                        在类型层面拦住它
     * @param content         正文。**只有这个方法返回它**
     * @param deliveryStatus  投递状态 —— 她"发出去了但对方没收到"是一个真实状态
     */
    record ClientMessage(String messageId, String senderAccountId, String kind, String content,
                         Instant sentAt, String deliveryStatus) {

        public ClientMessage {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("消息必须有 id —— 已读、回复、引用都靠它");
            }
            if (senderAccountId == null || senderAccountId.isBlank()) {
                throw new IllegalArgumentException("消息必须说明是谁发的(账号 id)");
            }
            kind = kind == null || kind.isBlank() ? MessageKinds.TEXT : kind;
            content = content == null ? "" : content;
            Objects.requireNonNull(sentAt, "消息必须带发送时刻");
            deliveryStatus = deliveryStatus == null || deliveryStatus.isBlank()
                    ? "delivered" : deliveryStatus;
        }

        /** 一条摘要 —— <b>刻意不含正文</b>。这个方法可以被安全地打进日志。 */
        public String describe() {
            return "消息[" + messageId + " " + kind + " 来自 " + senderAccountId
                    + " 长度=" + content.length() + "]";
        }
    }

    /**
     * 平台认识的消息种类 —— <b>常量, 不是白名单</b>。
     *
     * <p>与 {@code CoreEventCatalog.Channels} 同一个手法: 平台给出它认识的取值,
     * 但语法保持开放, 因为"世界上有哪几种消息"会随生态生长(P4)。
     * 用枚举表达它, 等于要求平台作者替所有未来的聊天平台预先想清楚。
     */
    final class MessageKinds {

        private MessageKinds() {
        }

        public static final String TEXT = "TEXT";
        public static final String IMAGE = "IMAGE";
        public static final String SYSTEM = "SYSTEM";
        public static final String APPLICATION_CARD = "APPLICATION_CARD";

        /** 平台认识的全部种类。 */
        public static List<String> known() {
            return List.of(TEXT, IMAGE, SYSTEM, APPLICATION_CARD);
        }
    }

    /**
     * 一页消息。
     *
     * <p>{@code messages} 按<b>时间倒序</b> —— 最新在前。这与 §6.3 给的分页语义一致
     * (她点开某人看到的是最新那几条, 上翻才是更早的)。
     */
    record MessagePage(List<ClientMessage> messages, String nextCursor, boolean hasMore) {

        public MessagePage {
            messages = messages == null ? List.of() : List.copyOf(messages);
            nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
            hasMore = hasMore && nextCursor != null;
        }

        public static MessagePage empty() {
            return new MessagePage(List.of(), null, false);
        }
    }

    /**
     * 发送命令。
     *
     * @param idempotencyKey 幂等键, <b>必填</b>。理由见 {@link #sendMessage}:
     *                       重试必须保证不出现两条一样的消息, 而幂等键的生成
     *                       必须发生在<b>决定发送的那一侧</b>(否则每次重试都是一个新键,
     *                       幂等就完全失效了)
     */
    record SendMessageCommand(String accountId, String kind, String content,
                              String idempotencyKey) {

        public SendMessageCommand {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("发给谁? 必须给会话对方的账号 id");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException(
                        "发送消息必须带幂等键 —— 少了它, 一次网络重试就会让她连发两条一样的话");
            }
            kind = kind == null || kind.isBlank() ? MessageKinds.TEXT : kind;
            content = content == null ? "" : content;
        }

        /** 一行摘要 —— 刻意不含正文。 */
        public String describe() {
            return "发送[" + accountId + " " + kind + " 长度=" + content.length()
                    + " key=" + idempotencyKey + "]";
        }
    }

    /** 发送结果。 */
    record SendResult(String messageId, Instant sentAt) {

        public SendResult {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException(
                        "发送结果必须带 messageId —— 没有它, 她就无法引用自己刚发的那条");
            }
            Objects.requireNonNull(sentAt, "发送结果必须带时刻");
        }
    }

    /**
     * 一个会话的通知设置 —— <b>这是聊天平台的状态, 不是 agent 的状态</b>(§6.2)。
     *
     * <p>它控制的是"要不要<b>发</b>通知信号", 而这个决定必须由聊天平台做出:
     * 它是平台自己的用户设置(V2.1 已经点明: "Chat Platform 不应该把'是否通知'
     * 交给 Agent 决定")。
     *
     * <p>与手机侧的 {@code NotificationPolicy} 的分工见那张表: 平台这一层决定
     * "发不发", 手机那一层决定"收到后怎么响"。
     *
     * @param conversationId 哪个会话 —— 注意免打扰的粒度是<b>会话</b>, 不是整个应用:
     *                       "这个群我免打扰了"不等于"我不收任何消息"
     * @param muted          消息免打扰
     * @param pinned         置顶。与免打扰无关但常常一起被设置, 所以放在同一个对象里
     */
    record ConversationNotificationSetting(String conversationId, boolean muted, boolean pinned,
                                          Instant updatedAt) {

        public ConversationNotificationSetting {
            if (conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException(
                        "通知设置必须指明是哪个会话 —— 免打扰是会话级的, 不是全局的");
            }
            Objects.requireNonNull(updatedAt, "通知设置必须带更新时刻");
        }
    }

    /**
     * 某人资料。
     *
     * <h2>⚠️ {@code displayName} 默认是空的</h2>
     * 见 {@link #contactProfile} 的说明。这个"缺失"不是未实现, 而是设计:
     * <b>平台不向 agent 暴露用户的备注体系</b>, 名字只能由她自己聊出来。
     *
     * @param displayName 聊天平台上的名字; <b>通常为空</b>, 空表示"平台没给名字",
     *                    而不是"这个人没有名字"
     * @param avatarUrl   头像地址。这个信息平台可以给 —— 它与"她与这个人的关系"
     *                    无关, 只是一个可渲染的资源
     */
    record ContactProfile(String accountId, String displayName, String avatarUrl) {

        public ContactProfile {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("资料必须说明是哪个账号的");
            }
            displayName = displayName == null || displayName.isBlank() ? null : displayName;
            avatarUrl = avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl;
        }

        /** 平台有没有告诉她这个人叫什么。 */
        public boolean named() {
            return displayName != null;
        }

        public static ContactProfile anonymous(String accountId) {
            return new ContactProfile(accountId, null, null);
        }
    }

    /**
     * {@code NotificationSignal} —— WebSocket 推来的<b>通知信号</b>。
     *
     * <h2>⚠️ 这里<b>禁止</b>出现 {@code content} / {@code preview} / {@code senderName}</h2>
     * 这是整条链上最关键的一处类型设计, 也是 §2.2 第 ④ 条断言:
     * <blockquote>
     *   第 ④ 步的信号不含正文。<b>类型层面就不含</b> —— 不是"我们不读", 是"装不下"。
     * </blockquote>
     *
     * <p>"不是我们不读, 是装不下"这个区别值得展开。它的意思是: 这条纪律
     * <b>不依赖任何一个实现者的自觉</b>。一个新人接手、一个第三方实现这个网关、
     * 一次着急的线上修复 —— 都不会让正文从这里漏出去, 因为<b>它没有地方可以漏</b>。
     * 反过来说, 如果这里有一个 {@code content} 字段而只是约定"不要读它",
     * 那么第一个遇到"调试不方便, 先打一下看看"的人就会把它打破。
     *
     * <h2>它<b>是</b>什么</h2>
     * <pre>
     *   "某个会话来了新消息, 手机该响一下"
     * </pre>
     * 而不是
     * <pre>
     *   "用户给你发了'晚上一起吃饭吗'"
     * </pre>
     * 后者要等她主动去看({@code chat.read-messages})才会出现。
     *
     * @param signalId       信号 id。它是<b>补发机制的游标</b>: 断线重连后服务端比对
     *                       {@code lastAckSignalId} 补推错过的信号(§6.6),
     *                       而补推的仍然只是信号 —— <b>不补正文</b>
     * @param conversationRef 这是关于哪个会话的 —— 一个<b>坐标</b>, 不是内容。
     *                       手机用它记未读、用它向平台确认收到; 而它<b>绝不进事件载荷</b>,
     *                       所以"她的 Mind"看不到"是谁发的"
     * @param reason         为什么通知。取值见 {@code NotificationRequest.Reasons},
     *                       但同样是<b>开放字符串</b> —— 三方应用可以有自己的理由
     * @param soundProfile   应用自带的音色标识。手机用它决定"放哪一段声音",
     *                       而"用多大声"由手机侧的通知音量决定
     * @param unreadCount    这个会话当前未读数 —— 她在红点上看到的就是它
     * @param occurredAt     平台产生这条信号的时刻
     */
    record NotificationSignal(String signalId,
                              String conversationRef,
                              String reason,
                              String soundProfile,
                              int unreadCount,
                              Instant occurredAt) {

        public NotificationSignal {
            signalId = signalId == null || signalId.isBlank()
                    ? "signal-" + java.util.UUID.randomUUID() : signalId;
            if (conversationRef == null || conversationRef.isBlank()) {
                throw new IllegalArgumentException(
                        "信号必须带会话坐标 —— 手机靠它归并未读, 也靠它向平台确认收到");
            }
            reason = reason == null || reason.isBlank() ? "new-message" : reason;
            soundProfile = soundProfile == null || soundProfile.isBlank() ? "default" : soundProfile;
            if (unreadCount < 0) {
                throw new IllegalArgumentException("未读数不能为负, 收到 " + unreadCount);
            }
            Objects.requireNonNull(occurredAt, "信号必须带发生时刻");
        }

        public static NotificationSignal of(String signalId, String conversationRef,
                                            int unreadCount, Instant at) {
            return new NotificationSignal(signalId, conversationRef, "new-message", "default",
                    unreadCount, at);
        }

        /** 收到这条信号多久了 —— 重连补发时用它判断"这条是不是已经太旧了"。 */
        public Duration ageAt(Instant now) {
            return Duration.between(occurredAt, now);
        }

        /**
         * 一行摘要 —— <b>可以安全地打进日志</b>: 本类型里根本没有正文,
         * 所以这个方法<b>不可能</b>泄露它。
         */
        public String describe() {
            return "通知信号[" + signalId + " 会话=" + conversationRef + " 未读=" + unreadCount
                    + " @" + occurredAt + "]";
        }
    }
}
