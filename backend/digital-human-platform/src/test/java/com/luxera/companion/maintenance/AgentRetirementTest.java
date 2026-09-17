package com.luxera.companion.maintenance;

import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.digitalhuman.summary.SessionSummary;
import com.luxera.companion.digitalhuman.summary.SessionSummaryRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.phone.PhoneNotification;
import com.luxera.companion.phone.PhoneNotificationRepository;
import com.luxera.companion.runtime.pipeline.PendingMessageState;
import com.luxera.companion.runtime.pipeline.PendingMessageStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除一个 Agent ⇒ 它在聊天平台的会话连消息一起消失 —— 用户 2026-09-17 那个
 * 「同一个 agent 出现多个聊天窗口」的回归测试。
 *
 * <h2>为什么这一组断言必须存在</h2>
 *
 * 修好之前, "删除 Agent"的全部实现就是给 {@code deleted_at} 写个时间戳。通讯录问的是
 * 这边(SQL 带 {@code deleted_at is null}), 聊天列表问的是聊天平台自己的会话表(只问
 * "我是不是参与者") —— 删了别人的账不销, 于是攒下 36 个幽灵窗口, 而名字都是 LLM 从
 * 描述里生成的、反复收敛到同一个, 看起来就像"同一个账号开了 36 个窗口"。
 *
 * <p>所以这里要钉住的不是"某一行被删了", 而是**两件事都做了**: 本仓的活队列清了, 并且
 * 跨服务的会话真的销毁了。任何一半单独做成都不会让那个症状消失。
 *
 * <p>走真 Spring 上下文(与 {@code PlanServiceTest} 同一风格), 聊天侧用
 * {@code InMemoryChatWorld} —— 它的 {@code purgePeer} 是完整实现而不是打日志的桩,
 * 否则"会话被销毁了"这句断言就是空的。
 */
@ActiveProfiles("test")
@SpringBootTest
class AgentRetirementTest {

    @Autowired
    AgentRetirementService retirement;
    @Autowired
    CompanionService companionService;
    @Autowired
    CompanionRepository companions;
    @Autowired
    ChatWorldPort chatWorld;
    @Autowired
    PhoneNotificationRepository phoneNotifications;
    @Autowired
    PendingMessageStateRepository pendingStates;
    @Autowired
    SessionSummaryRepository summaries;

    private final String userId = UUID.randomUUID().toString();
    /** 要被退役的那个 —— 每次测试一个新 id, 测试库是全类共享的 */
    private String companionId;
    /** 隔壁那个必须活下来的 */
    private String survivingCompanionId;
    private String conversationId;
    private String survivingConversationId;

    @BeforeEach
    void setUp() {
        companionId = newCompanion("小满");
        survivingCompanionId = newCompanion("阿澈");

        conversationId = chatWorld.ensureConversation(userId, companionId, "小满").getId();
        survivingConversationId = chatWorld.ensureConversation(userId, survivingCompanionId, "阿澈").getId();
        chatWorld.append(MessageAppendCommand.of(conversationId, "user", "在吗"));
        chatWorld.append(MessageAppendCommand.of(survivingConversationId, "user", "别删我"));

        PhoneNotification n = new PhoneNotification();
        n.setId(UUID.randomUUID().toString());
        n.setCompanionId(companionId);
        n.setMessageId(UUID.randomUUID().toString());
        n.setConversationId(conversationId);
        phoneNotifications.save(n);

        PendingMessageState p = new PendingMessageState();
        p.setId(UUID.randomUUID().toString());
        p.setMessageId(UUID.randomUUID().toString());
        p.setCompanionId(companionId);
        p.setConversationId(conversationId);
        p.setUserId(userId);
        pendingStates.save(p);

        SessionSummary s = new SessionSummary();
        s.setId(UUID.randomUUID().toString());
        s.setConversationId(conversationId);
        s.setSummaryText("他说过在吗");
        summaries.save(s);
    }

    private String newCompanion(String name) {
        Companion c = new Companion();
        c.setId(UUID.randomUUID().toString());
        c.setUserId(userId);
        c.setName(name);
        companions.save(c);
        return c.getId();
    }

    /** 完整的两步 —— 调用方(CompanionController)就是这么按顺序调的。 */
    private void retireFully(String id) {
        retirement.retire(userId, id);
        retirement.purgeChatWorld(id);
    }

    // ── 第一步: 本仓的账 ──────────────────────────────────────────────────────

    @Test
    void retireMarksTheAgentDeleted() {
        retirement.retire(userId, companionId);

        assertNotNull(companions.findById(companionId).orElseThrow().getDeletedAt(),
                "软删除是「它不再存在」的权威事实");
        assertFalse(companionService.list(userId).stream()
                        .anyMatch(c -> c.getId().equals(companionId)),
                "退役之后它不该再出现在任何人的通讯录里");
    }

    @Test
    void retireClearsTheTwoLiveQueues() {
        // 前提: 两张活队列表里各有一条 —— 否则下面的"清空了"是空断言
        assertEquals(1, phoneNotifications.findByCompanionIdOrderByCreatedAtDesc(companionId).size());
        assertEquals(1, pendingStates.findByCompanionIdAndStatus(
                companionId, PendingMessageState.STATUS_PENDING).size());

        retirement.retire(userId, companionId);

        // 这两张是**活队列**: 有定时任务在扫、有指针指向会话。留着就会持续产生指向空气的动作
        assertEquals(0, phoneNotifications.findByCompanionIdOrderByCreatedAtDesc(companionId).size(),
                "手机收件箱必须清空");
        assertEquals(0, pendingStates.findByCompanionIdAndStatus(
                companionId, PendingMessageState.STATUS_PENDING).size(),
                "待复审队列必须清空 —— 否则定时任务会一直去拉一个已经不存在的会话");
    }

    @Test
    void retireDoesNotTouchTheNeighbour() {
        retirement.retire(userId, companionId);

        assertNull(companions.findById(survivingCompanionId).orElseThrow().getDeletedAt(),
                "隔壁的 Agent 不能被殃及");
        assertTrue(companionService.list(userId).stream()
                .anyMatch(c -> c.getId().equals(survivingCompanionId)));
    }

    // ── 第二步: 跨服务销毁 ────────────────────────────────────────────────────

    @Test
    void purgeDestroysTheConversationAndItsMessages() {
        assertTrue(chatWorld.conversationsOf(companionId).stream()
                .anyMatch(c -> c.getId().equals(conversationId)), "前提: 聊天世界里本来有这段会话");

        retirement.purgeChatWorld(companionId);

        assertTrue(chatWorld.conversationsOf(companionId).isEmpty(), "会话必须没了");
        assertTrue(chatWorld.messages(conversationId).isEmpty(), "消息必须连根拔掉, 不是留个墓碑");
    }

    /**
     * ★ 这一条钉的是 {@code ChatWorldPort#purgePeer} 那个"必须返回 conversationId"的契约。
     *
     * <p>{@code session_summaries} 按 conversationId 存, 而那个 id 是聊天平台分配的 ——
     * 本仓调用 {@code ensureConversation} 时拿到过、但从来没留底, 算不出来。所以摘要能不能
     * 被清掉, 完全取决于聊天的响应里有没有把 id 还回来。返回类型从 {@code void} 改成
     * {@code List<String>} 的全部理由就在这一条断言里。
     */
    @Test
    void purgeAlsoDropsTheSessionSummaryKeyedByTheReturnedId() {
        assertTrue(summaries.findByConversationId(conversationId).isPresent(), "前提: 摘要已存在");

        retirement.purgeChatWorld(companionId);

        assertFalse(summaries.findByConversationId(conversationId).isPresent(),
                "会话没了摘要还留着 —— 下次 id 被复用时就会读到别人的摘要");
    }

    @Test
    void purgeLeavesTheNeighbouringConversationAlone() {
        retirement.purgeChatWorld(companionId);

        assertTrue(chatWorld.conversationsOf(survivingCompanionId).stream()
                .anyMatch(c -> c.getId().equals(survivingConversationId)), "隔壁会话必须还在");
        assertEquals(1, chatWorld.messages(survivingConversationId).size(), "隔壁的消息一条都不能少");
    }

    /** 幂等 —— 重放 DELETE 正是"上次删到一半"时用来收尾的那条路。 */
    @Test
    void purgingTwiceIsSafe() {
        retirement.purgeChatWorld(companionId);

        assertEquals(List.of(), chatWorld.purgePeer(companionId), "第二次应当是空清单, 而不是异常");
    }

    @Test
    void countOfAPeerWithNoConversationsIsZeroNotAnError() {
        assertEquals(0, chatWorld.purgePeer(UUID.randomUUID().toString()).size());
    }

    // ── 半成品状态 ────────────────────────────────────────────────────────────

    /**
     * 已经软删过、但收尾没做完的 Agent —— 这正是 {@code CompanionController#delete} 里
     * 那个 {@code ownsDeleted} 分支要处理的状态, 也是 {@code GhostChatSweeper} 面对的状态。
     */
    @Test
    void anAlreadyDeletedAgentIsStillOwnedSoTheRetryCanFinish() {
        retirement.retire(userId, companionId);

        // requireOwned 把"已删除"报成 404 —— 所以删除接口不能只靠它来判断能不能重试
        assertThrows(RuntimeException.class, () -> companionService.softDelete(userId, companionId));
        assertTrue(companionService.ownsDeleted(userId, companionId),
                "已删且是我的 —— 重放删除应当被判为「可以继续收尾」, 而不是 404 卡死");
        assertFalse(companionService.ownsDeleted(UUID.randomUUID().toString(), companionId),
                "别人的 Agent 不能靠重放 DELETE 触发销毁");
        assertFalse(companionService.ownsDeleted(userId, survivingCompanionId),
                "还活着的 Agent 不是「可以收尾」的状态");
    }

    /** 补做路径: 只清活队列、不碰软删除 —— 给一次性对账和半成品修复用。 */
    @Test
    void clearActiveQueuesWorksWithoutTouchingTheSoftDelete() {
        retirement.clearActiveQueues(companionId);

        assertEquals(0, phoneNotifications.findByCompanionIdOrderByCreatedAtDesc(companionId).size());
        assertNull(companions.findById(companionId).orElseThrow().getDeletedAt(),
                "补做只清队列, 不该顺手把人删了");
    }
}
