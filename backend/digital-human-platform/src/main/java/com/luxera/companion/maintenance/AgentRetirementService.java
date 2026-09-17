package com.luxera.companion.maintenance;

import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.digitalhuman.summary.SessionSummaryService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.phone.PhoneNotificationRepository;
import com.luxera.companion.runtime.pipeline.PendingMessageStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 「删除一个 Agent」的完整含义 —— 分两步, 顺序是本类唯一重要的事。
 *
 * <h2>它为什么不是 {@code CompanionService.delete} 的一部分</h2>
 *
 * 因为它需要的三样东西({@code phone} 包的仓储、{@code runtime.pipeline} 包的仓储、
 * {@code ChatWorldPort})在 8092(openapi)那个进程里**一样都没有**, 而且按架构就该没有:
 * 那两个包的仓储不在 openapi 的 EntityScan 里, {@code ChatWorldPort} 的 HTTP 适配器只在
 * server 侧装配。往 {@code CompanionService} 的构造函数里塞任何一个, 8092 就直接起不来
 * (试过了, {@code Failed to load ApplicationContext: No qualifying bean of type
 * PhoneNotificationRepository})。
 *
 * <p>所以完整的退役住在 {@code com.luxera.companion.maintenance} —— 一个 openapi 的扫描
 * 白名单之外的包, 只有 server(8091) 会注册它。第三方 API 那条路走的是
 * {@code CompanionService.softDelete}(软删, 那是它文档里写明的语义), 漏下的残骸由
 * {@link GhostChatSweeper} 兜底。
 *
 * <h2>两步为什么不能合成一个事务</h2>
 *
 * 第一步 {@link #retire} 落定"它不再存在"这件权威事实 —— 软删除 + 清掉本仓两条**活队列**
 * ({@code phone_notifications} / {@code pending_message_states}), 一个事务要么都成要么都不成。
 *
 * <p>第二步 {@link #purgeChatWorld} 是不可逆的硬删除(用户 2026-09-17 明确选的「连消息
 * 一起删掉」)。如果把它塞进第一步那个事务, 事务一旦在它之后回滚 —— 哪怕只是本地某张表删
 * 失败 —— 就会留下 <b>Agent 还活着、历史已经没了</b>。那个状态不可恢复。
 *
 * <p>反过来, 两步之间失败留下的最坏结果是 <b>Agent 删了、聊天窗口还在</b> —— 那正是这个
 * 功能原本的行为, 重跑一次就好。
 *
 * <p>铁律: <b>绝不为一个可能还活着的东西销毁历史。</b> 两步的分法就是这条铁律的写法 ——
 * 所以调用方必须先调 {@link #retire}, 提交之后再调 {@link #purgeChatWorld}。
 *
 * <h2>清哪些、不清哪些</h2>
 *
 * 清了 {@code phone_notifications} / {@code pending_message_states} / {@code session_summaries},
 * 但**不动** {@code relationships} / {@code agent_states} / {@code life_events} /
 * {@code personas}: 那几张是"这个 Agent 的历史", 与软删除后的 {@code companions} 行一起
 * 构成一份自洽的存档, 而这个 Agent 已经不在任何人的通讯录里, 没人能寻址到它, 留着不产生
 * 任何下游行为。
 *
 * <p>界线是"有没有人读它", 不是"干不干净": 前三张是**活队列** —— 有定时任务在扫、有指针
 * 指向会话 —— 不删就会持续产生指向空气的动作({@code PendingMessageReevaluationJob} 会把
 * 已删 Agent 的待复审行反复捞起来, 每次都去拉一个已经不存在的会话)。
 */
@Slf4j
@Service
public class AgentRetirementService {

    private final CompanionService companionService;
    private final PhoneNotificationRepository phoneNotifications;
    private final PendingMessageStateRepository pendingStates;
    private final ChatWorldPort chatWorld;
    private final SessionSummaryService sessionSummaryService;

    public AgentRetirementService(CompanionService companionService,
                                  PhoneNotificationRepository phoneNotifications,
                                  PendingMessageStateRepository pendingStates,
                                  ChatWorldPort chatWorld,
                                  SessionSummaryService sessionSummaryService) {
        this.companionService = companionService;
        this.phoneNotifications = phoneNotifications;
        this.pendingStates = pendingStates;
        this.chatWorld = chatWorld;
        this.sessionSummaryService = sessionSummaryService;
    }

    /**
     * 第一步: 本仓的账。软删除 + 清两条活队列, 一个事务。
     *
     * <p>清了活队列却**不**碰历史存档, 理由见类注释。
     */
    @Transactional
    public void retire(String userId, String companionId) {
        companionService.softDelete(userId, companionId);
        clearActiveQueues(companionId);
    }

    /**
     * 第一步的**补做**: 只清活队列, 不碰软删除。
     *
     * <p>给两种调用方: {@link GhostChatSweeper}(面对的是历史上早就软删除、队列却从没清过的
     * Agent —— 它不能再走一遍 {@code requireOwned}, 那会 404; 也不该重设 {@code deletedAt}),
     * 以及将来任何"接上一个半成品状态"的修复路径。
     */
    @Transactional
    public void clearActiveQueues(String companionId) {
        phoneNotifications.deleteByCompanionId(companionId);
        pendingStates.deleteByCompanionId(companionId);
    }

    /**
     * 第二步: 把它在聊天平台的会话连消息一起销毁。
     *
     * <p><b>必须在 {@link #retire} 的事务提交之后调用。</b> 见类注释。
     *
     * <p>失败**不吞**: 吞掉 = 用户以为删干净了, 而聊天列表里那条窗口还在 —— 那就是要修的
     * 那个 bug 本身。让异常冒到 HTTP 层, 客户端才知道这次删除没完成。
     *
     * <p>先问 chat 再删本地摘要, 顺序也不能反: {@code session_summaries} 按
     * {@code conversationId} 存, 而那个 id 是 chat 分配的 —— 本仓从来没留底, 算不出来。
     * 只能等 chat 把被销毁的 id 还回来再删。见 {@code ChatWorldPort#purgePeer} 对
     * "为什么必须返回 id"的说明。
     */
    public void purgeChatWorld(String companionId) {
        List<String> purged = chatWorld.purgePeer(companionId);
        sessionSummaryService.deleteForConversations(purged);
    }
}
