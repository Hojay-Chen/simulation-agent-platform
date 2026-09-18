package com.luxera.companion.phone;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.simulator.CapabilityResult;
import com.luxera.companion.digitalhuman.simulator.ChatSimulatorClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V11 §7.2 —— 手机的默认实现。<b>它唯一真正难的地方是"择优传输"。</b>
 *
 * <h2>为什么不能只留一条路</h2>
 * 设备通道({@code chat.readMessages} over WebSocket)在语义上是"正确"的那条: 它是
 * <b>她的手在动</b> —— 读取留痕、可被聊天平台审计、也真的会失败(没信号)。
 * 而 {@code ChatWorldPort.messages} 是平台内部直读, 语义上是"上帝视角偷看"。
 *
 * <p>但今天的事实是: 平台里只有 <b>1</b> 台设备是 ACTIVE 的, 其余 51 个 agent 都停在
 * PAIRING —— 也就是说<b>只有 1 个 agent 有手</b>。如果读取只走设备通道, 剩下 52 个
 * 会立刻变成哑巴: 收得到通知, 却永远读不到内容, 于是永远不回话。
 * 那不是一个"更纯粹"的实现, 那是一次停机。
 *
 * <p>所以这里择优: 设备连着就走设备, 没连着就走平台直读, 并且<b>把用了哪条记进结果</b>
 * ({@link MessageBatch#transport()})。设备通道会随着配对推进逐步接管, 而这个方法
 * 不需要再改一行 —— 这正是"迁移不许 Big Bang"在代码里的样子。
 *
 * <h2>择优的另一半: 设备通道答不了的问题要回退</h2>
 * {@code chat.readMessages} 只能"读最近 N 条", 没法"按 id 读指定的几条"。所以
 * {@link ReadPolicy.ByIds} 走设备通道时是"读最近一批再筛", 而<b>筛完为空时必须回退</b>
 * —— 那说明那几条比窗口更老(比如她睡了一夜才醒)。不回退的话, 表现是
 * "她明明有手机却看不到昨晚那条消息", 而且只在消息稍旧时出现, 极难复现。
 */
@Service
@Slf4j
public class DefaultPhoneCapability implements PhoneCapability {

    /**
     * 走设备通道按 id 读时, 先拉多少条来筛。
     *
     * <p>取 50: 一次送达通常只有个位数消息, 而"她醒来后要补看的那一批"也很少超过几十条。
     * 太小则老消息频繁落空回退(每次回退都是一次额外的平台直读), 太大则白白多传正文 ——
     * 而多传的每一条正文都是"她其实没读, 但内容已经过网络"的那类问题。
     */
    private static final int SIMULATOR_SCAN_WINDOW = 50;

    /** 快照里最多带几条待读通知。超过这个数她也不会一条条看。 */
    private static final int SNAPSHOT_PENDING_LIMIT = 20;

    private final PhoneNotificationRepository notifications;
    private final PhoneStateService phoneStates;
    /**
     * 设备通道。<b>必须用 {@link ObjectProvider} 而不是直接注入</b> ——
     * {@code ChatSimulatorClient} 是 {@code @ConditionalOnProperty(app.simulator.backend=websocket)}
     * 的, 而默认配置下这个 bean <b>不存在</b>。
     *
     * <p>直接注入的后果不是"设备通道用不了", 而是<b>整个应用起不来</b>:
     * {@code UnsatisfiedDependencyException: No qualifying bean of type ChatSimulatorClient},
     * 因为本类连着 {@code ReadMessagesAction} → {@code V11DeliveryPath} → {@code AgentRuntime},
     * 一路把"设备通道可选"这件事变成了一条硬依赖。
     *
     * <p>这也正是"择优传输"在依赖注入层面的样子: 设备通道可能是<b>缺席的</b>,
     * 缺席时安静地走平台直读, 而不是让整个认知链陪葬。
     */
    private final ObjectProvider<ChatSimulatorClient> simulator;
    private final ChatWorldPort chatWorld;

    public DefaultPhoneCapability(PhoneNotificationRepository notifications,
                                  PhoneStateService phoneStates,
                                  ObjectProvider<ChatSimulatorClient> simulator,
                                  ChatWorldPort chatWorld) {
        this.notifications = notifications;
        this.phoneStates = phoneStates;
        this.simulator = simulator;
        this.chatWorld = chatWorld;
    }

    // ─────────────────────────── 通知 ───────────────────────────

    @Override
    public NotificationSnapshot notifications(String agentId) {
        int unread = (int) (long) notifications.countByCompanionIdAndReadFalse(agentId);

        List<NotificationSnapshot.PendingNotification> pending = notifications
                .findByCompanionIdOrderByCreatedAtDesc(agentId).stream()
                .filter(n -> !n.isRead())
                .limit(SNAPSHOT_PENDING_LIMIT)
                .map(n -> new NotificationSnapshot.PendingNotification(
                        n.getMessageId(), n.getConversationId(),
                        n.isHeard(), n.isSeen(), n.isOpened()))
                .toList();

        // 手机现在会不会把响动传给她 —— 勿扰模式是"她主动不想被打扰", 与"她没看见"是两件事
        boolean available = true;
        try {
            PhoneState s = phoneStates.current(agentId, LocalDateTime.now());
            available = s != null && !s.isDoNotDisturb();
        } catch (Exception e) {
            // 读不到手机状态时当作可用: 宁可让她被打扰一次, 也不要让她静默地错过所有消息
            log.debug("[Phone] 读取 {} 的手机状态失败, 按可用处理", agentId, e);
        }

        String conversationId = pending.isEmpty() ? null : pending.get(0).conversationId();
        return new NotificationSnapshot(agentId, conversationId, unread, pending, available,
                LocalDateTime.now());
    }

    // ─────────────────────────── 读取 ───────────────────────────

    @Override
    public MessageBatch readMessages(String agentId, String conversationId, ReadPolicy policy) {
        if (conversationId == null || conversationId.isBlank()) {
            return MessageBatch.unreachable("没有会话可读");
        }
        if (policy == null || policy.upperBound() == 0) {
            return new MessageBatch(List.of(), MessageBatch.Transport.CHAT_WORLD_PORT, "这次没有要读的消息");
        }

        if (simulatorConnected(agentId)) {
            MessageBatch viaDevice = readViaDevice(agentId, conversationId, policy);
            if (!viaDevice.isEmpty() || !policy.deliveryDriven()) {
                return viaDevice;
            }
            // 设备通道答不了(按 id 读, 但那几条比扫描窗口更老)→ 回退, 而不是回一个空
            log.debug("[Phone] {} 的设备通道没扫到目标消息, 回退平台直读", agentId);
        }

        return readViaPlatform(agentId, conversationId, policy);
    }

    /** 设备连着吗。连着的判据是"她自己的那条 WS 连接还在", 不是"库里有一台设备"。 */
    private boolean simulatorConnected(String agentId) {
        ChatSimulatorClient client = simulator == null ? null : simulator.getIfAvailable();
        if (client == null) {
            // 设备通道路由都没启用(app.simulator.backend 不是 websocket) —— 这不是故障,
            // 是当前部署的形态, 所以按 DEBUG 记, 不要每次消息都 WARN 一遍
            log.debug("[Phone] 设备通道未启用, {} 走平台直读", agentId);
            return false;
        }
        try {
            return client.isConnected(agentId);
        } catch (Exception e) {
            log.warn("[Phone] 查询 {} 的设备连接状态失败, 按未连接处理", agentId, e);
            return false;
        }
    }

    private MessageBatch readViaDevice(String agentId, String conversationId, ReadPolicy policy) {
        ChatSimulatorClient client = simulator == null ? null : simulator.getIfAvailable();
        if (client == null) {
            return MessageBatch.unreachable("设备通道未启用");
        }
        int limit = policy instanceof ReadPolicy.ByIds ids
                ? Math.max(SIMULATOR_SCAN_WINDOW, ids.upperBound())
                : policy.upperBound();
        try {
            CapabilityResult result = client.readMessages(agentId, conversationId, limit);
            if (result == null || !result.success()) {
                return MessageBatch.unreachable("设备读取失败: " + (result == null ? "无响应" : result.message()));
            }
            List<MessageView> read = toMessageViews(result);
            List<MessageView> wanted = filter(read, policy);
            String note = wanted.isEmpty() && !read.isEmpty()
                    ? "设备读了 " + read.size() + " 条, 其中没有这次要的那几条"
                    : "设备读了 " + wanted.size() + " 条";
            return new MessageBatch(wanted, MessageBatch.Transport.SIMULATOR, note);
        } catch (Exception e) {
            log.warn("[Phone] {} 设备读取异常, 回退", agentId, e);
            return MessageBatch.unreachable("设备读取异常: " + e.getMessage());
        }
    }

    private MessageBatch readViaPlatform(String agentId, String conversationId, ReadPolicy policy) {
        try {
            List<MessageView> read;
            if (policy instanceof ReadPolicy.ByIds ids) {
                // 逐条取, 而不是"把整个会话拉下来筛" —— 后者正是本次要消灭的形状:
                // 它会让整个会话的正文在她还没决定看之前就进入这个进程
                read = new ArrayList<>();
                for (String id : ids.messageIds()) {
                    chatWorld.message(id).ifPresent(read::add);
                }
                read.sort(java.util.Comparator.comparing(MessageView::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            } else {
                read = chatWorld.recentMessages(conversationId, policy.upperBound());
            }
            return new MessageBatch(read, MessageBatch.Transport.CHAT_WORLD_PORT,
                    "平台直读 " + read.size() + " 条");
        } catch (Exception e) {
            log.error("[Phone] {} 平台直读失败: {}", agentId, e.getMessage());
            return MessageBatch.unreachable("读取失败: " + e.getMessage());
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private static List<MessageView> filter(List<MessageView> read, ReadPolicy policy) {
        if (policy instanceof ReadPolicy.ByIds ids) {
            Set<String> wanted = Set.copyOf(ids.messageIds());
            return read.stream().filter(m -> wanted.contains(m.getId())).toList();
        }
        int limit = policy.upperBound();
        return read.size() <= limit ? read : read.subList(read.size() - limit, read.size());
    }

    /**
     * 设备通道返回的是 {@code List<Map<String,Object>>}(WS 上的 JSON), 这里转成契约类型。
     *
     * <p>转换放在能力实现里而不是调用方: 否则每个调用方都要知道 {@code chat.readMessages}
     * 的 JSON 长什么样, 于是"换一条传输"会变成一次跨模块的改字段。
     */
    @SuppressWarnings("unchecked")
    private static List<MessageView> toMessageViews(CapabilityResult result) {
        Object raw = result.data() == null ? null : result.data().get("messages");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MessageView> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) m;
            out.add(MessageView.builder()
                    .id(str(msg.get("id")))
                    .conversationId(str(msg.get("conversationId")))
                    .senderType(str(msg.get("senderType")))
                    .content(str(msg.get("content")))
                    .deliveryStatus(str(msg.get("deliveryStatus")))
                    .messageKind(str(msg.get("messageKind")))
                    .createdAt(parseTime(msg.get("createdAt")))
                    .metadata(new LinkedHashMap<>())
                    .build());
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static LocalDateTime parseTime(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(o));
        } catch (Exception e) {
            // 时间戳解析不了不该让整次读取失败 —— 消息本身是好的
            return null;
        }
    }

    /** 供测试与诊断用: 这一次会选择哪条传输。 */
    public MessageBatch.Transport transportFor(String agentId) {
        return simulatorConnected(agentId)
                ? MessageBatch.Transport.SIMULATOR
                : MessageBatch.Transport.CHAT_WORLD_PORT;
    }
}
