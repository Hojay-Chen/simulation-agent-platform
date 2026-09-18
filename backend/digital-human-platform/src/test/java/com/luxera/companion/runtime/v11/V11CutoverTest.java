package com.luxera.companion.runtime.v11;

import com.luxera.companion.behavior.BehaviorAction;
import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.behavior.BehaviorOutcome;
import com.luxera.companion.behavior.BehaviorTickJob;
import com.luxera.companion.mailbox.AgentInboxConsumer;
import com.luxera.companion.mailbox.AgentInboxEntry;
import com.luxera.companion.mailbox.AgentInboxRepository;
import com.luxera.companion.mailbox.AgentMailbox;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.wakeup.AgentWakeupJob;
import com.luxera.companion.wakeup.AgentWakeupService;
import com.luxera.companion.world.AgentEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 Phase 6 —— <b>切流演练</b>。四个开关全部打开, 用真的 Spring 上下文、真的库、
 * 真的信箱与真的调度任务走一遍。
 *
 * <h2>它为什么必须存在(以及它为什么不是单测的重复)</h2>
 * 前面五个阶段的用例全都在"开关关着"的默认态下跑的。于是有一条风险一直没有被覆盖,
 * 而它恰好是最难在事后发现的那一类: <b>切流那天是这些接线第一次被执行</b>。
 * 属性名写错一个字母、某个消费者没被注册进容器、某个 {@code @Transactional} 在真库上
 * 才暴露出来的问题 —— 单测一个都看不见, 因为单测里的开关是手工构造的。
 * 这里换的是<b>配置</b>, 不是对象, 所以它检查的是"配置文件到行为"这条线本身。
 *
 * <h2>为什么可以现在写, 而生产上还不切</h2>
 * 这是两件事。切流是<b>运维步骤</b>(§25.1: Adapter → Shadow → Dual Run → Cutover →
 * Cleanup), 前提是真实流量下的 shadow 分歧率可读; 而本类回答的是另一个问题:
 * <b>"如果明天就切, 会不会当场炸"</b>。前者需要时间, 后者今天就能回答,
 * 而且它正是让 Phase 6 的删除成为可能的那块前置 —— 一个从没被执行过的接线,
 * 没有资格谈"删掉老链"。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        // ── 四条链全部切流 ──
        "app.v11.runtime.enabled=true", "app.v11.runtime.shadow=false",
        "app.v11.turns.enabled=true", "app.v11.turns.shadow=false",
        "app.v11.cognition.enabled=true", "app.v11.cognition.shadow=false",
        "app.v11.proactive.enabled=true", "app.v11.proactive.shadow=false",
        // ── 后台调度一律钉死: 它们会真的写 agent_inbox, 而本类要对信箱做精确断言 ──
        "app.scheduler.agent-wakeup-cron=0 0 0 1 1 *",
        "app.scheduler.agent-wakeup-purge-cron=0 0 0 1 1 *",
        "app.scheduler.wakeup-rearm-cron=0 0 0 1 1 *",
        "app.scheduler.open-loop-due-cron=0 0 0 1 1 *",
        "app.scheduler.life-schedule-cron=0 0 0 1 1 *",
        "app.scheduler.behavior-tick-cron=0 0 0 1 1 *",
        "app.scheduler.inbox-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.turn-seal-cron=0 0 0 1 1 *"
})
class V11CutoverTest {

    @Autowired V11RuntimeSwitch runtimeSwitch;
    @Autowired V11TurnsSwitch turnsSwitch;
    @Autowired V11CognitionSwitch cognitionSwitch;
    @Autowired V11ProactiveSwitch proactiveSwitch;

    @Autowired AgentWakeupService wakeups;
    @Autowired AgentWakeupJob wakeupJob;
    @Autowired AgentMailbox mailbox;
    @Autowired AgentInboxRepository inbox;
    @Autowired CompanionRepository companions;
    @Autowired ProactiveActionRecorder recorder;
    @Autowired List<AgentInboxConsumer> consumers;
    @Autowired BehaviorTickJob oldTick;

    /** 真的那门引擎会在 enabled 档里发消息、写关系、调 LLM —— 换成假的才谈得上确定性。 */
    @MockBean BehaviorEngine behaviorEngine;

    private String agentId;

    private String seedAgent() {
        agentId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(agentId);
        c.setUserId("cutover-user");
        c.setName("小满");
        c.setGender("female");
        return companions.save(c).getId();
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            inbox.deleteByAgentId(agentId);
        }
    }

    // ─────────────────────────── 开关本身 ───────────────────────────

    @Nested
    @DisplayName("四条链真的都切过去了")
    class Switches {

        @Test
        void everyChainReportsCutOver() {
            // 这一条看着像废话, 但它挡的是一种无声的失败: 属性名写错一个字母时
            // @Value 的默认值会接手, 于是"切流"变成"四条链里切了三条",
            // 而另外三条各自的 shadow 还在跑 —— 没有任何报错, 只有一份奇怪的诊断数据。
            assertTrue(runtimeSwitch.isEffective(), "送达主链没切过去");
            assertTrue(turnsSwitch.isEffective(), "回合聚合没切过去");
            assertTrue(cognitionSwitch.isEffective(), "认知决策没切过去");
            assertTrue(proactiveSwitch.isEffective(), "主动行为没切过去");
        }

        @Test
        void theOldBehaviorTickGoesQuiet() {
            // 切流之后它必须停: 不停的话她的一次主动行为被两条链各执行一次,
            // 而"她一次说了两条"在外观上与"她心情很好"没有区别。
            oldTick.run();

            verify(behaviorEngine, never()).evaluateAll(any(LocalDateTime.class));
        }
    }

    // ─────────────────────────── 闭环 ───────────────────────────

    @Nested
    @DisplayName("一封闹钟的信真的走完了全程")
    class TheLoop {

        @Test
        void fromAnAlarmToAConsumedLetter() throws Exception {
            String id = seedAgent();
            when(behaviorEngine.evaluateById(eq(id), any(LocalDateTime.class), anyString()))
                    .thenReturn(new BehaviorOutcome(BehaviorAction.SEND_PROACTIVE_MESSAGE,
                            "想问问面试的事", 0.71, "V11_SCHEDULED_WAKEUP", LocalDateTime.now()));

            // 一行到点的闹钟 —— 这正是 WakeupRearmJob 在生产上会补的那一行
            assertTrue(wakeups.schedule(id, LocalDateTime.now().minusSeconds(1),
                    AgentEventType.SCHEDULED_WAKEUP,
                    AgentWakeupService.key(AgentWakeupService.SRC_LIFE, id), "生命节律"));

            wakeupJob.fireDue();

            // 1) 闹钟响了之后那行只剩历史
            assertNull(wakeups.nextWakeupOf(id), "响过的闹钟不该还挂在她名下");

            // 2) 信进了信箱, 而且被拆开了。拆信在 her actor 线程上, 是异步的 ——
            //    轮询而不是 sleep 一个魔法数字: 慢机器上 sleep 会偶发红,
            //    而偶发红的测试迟早会被人加一个 @Disabled。
            assertEquals(AgentInboxEntry.S_CONSUMED, awaitConsumed(id), "信没被拆开");

            // 3) 拆信真的问了她那门引擎, 而且理由串带着"被谁唤醒"
            verify(behaviorEngine).evaluateById(eq(id), any(LocalDateTime.class),
                    eq("V11_SCHEDULED_WAKEUP"));
            assertTrue(recorder.events() > 0, "拆开的信没有记账");
        }

        @Test
        void aLetterThatNobodyClaimsStaysUnopened() {
            // 阶梯事件今天在真的信箱上就是这个样子。Phase 2 把它记成"切流前置条件 ①",
            // 而这里让它在真库上显形一次: 信收得下、拆不开, 而且**重放也帮不上忙**
            // (重放遇到没有消费者的信会原样留在 PENDING)。切流那天必须有人回答
            // "阶梯到底走信箱还是走直连", 而在那之前这条用例就是那个问题的可见形态。
            //
            // 顺带说清"没有消费者"是怎么成立的: 生产侧实现 AgentInboxConsumer 的只有
            // ProactiveActionConsumer 一个, 它的 SUPPORTED 是闹钟/生活/悬案/未竟之事/关系
            // 五种 —— 没有一个属于阶梯。所以这不是"暂时没接线", 是"这个类型在设计上还没有人认领"。
            String id = seedAgent();
            String conversationId = "conv-" + id;
            var letter = com.luxera.companion.world.EventEnvelope.withDeterministicId(id,
                    AgentEventType.USER_MESSAGE_RECEIVED,
                    com.luxera.companion.world.EventSource.CHAT_PLATFORM,
                    conversationId + "-m1", java.util.Map.of("conversationId", conversationId));

            // accept 的 true 只说"信是新收下的", 与"有人拆"无关 —— 这正是要显形的那件事:
            // 信箱按设计收得下任何一封信, 拆不开不是拒收, 是留在那儿等一个听得懂的人。
            assertTrue(mailbox.accept(letter), "信应当收得下");
            assertEquals(1L, inbox.countByAgentIdAndStatus(id, AgentInboxEntry.S_PENDING),
                    "收下了却没人拆 —— 它就该留在 PENDING");

            mailbox.replayPending();

            assertEquals(1L, inbox.countByAgentIdAndStatus(id, AgentInboxEntry.S_PENDING),
                    "重放救不了它: 没有消费者不是'处理失败', 是'还没有人听得懂'");
        }
    }

    // ─────────────────────────── 仍然敞着的那道口子 ───────────────────────────

    @Nested
    @DisplayName("阶梯事件仍然没有消费者(切流时要做的那件事)")
    class TheLadderGap {

        @Test
        void noConsumerClaimsThePerceptionLadderEvenAfterCutover() {
            // Phase 2 把这条记为"切流前置条件 ①", 而它今天仍然敞着 —— 这里把它变成
            // 一个会红的断言, 是为了让它无法被忘记: 切流那天必须有人回答
            // "阶梯事件进信箱之后由谁拆"。
            //
            // 今天之所以**不能**顺手加一个: V11DeliveryPath 是阶梯的直接驱动者,
            // 再注册一个消费者会让同一条消息被处理两次, 而"她偶尔回你两条"
            // 不会被当成故障报上来。所以正确做法不是加一个空消费者就完事,
            // 而是先决定阶梯到底走信箱还是走直连。
            //
            // 这条断言第一次跑就红了, 但它红得有价值 —— 它在容器里逮到的不是生产代码,
            // 而是 AgentMailboxTest.RecordingConsumer 这个**测试脚手架**: 它 supports 一切,
            // 本来只该活在 AgentMailboxTest 自己的上下文里, 却出现在了每一个 @SpringBootTest 里。
            // 病根在 DigitalHumanTestApplication 手写 @ComponentScan 时压掉了
            // TypeExcludeFilter(修法与解释见那个类)。所以这条用例除了守住"阶梯没人拆",
            // 还顺手守住了一件事: **这个容器里只有生产的消费者**, 演练才算数。
            for (AgentInboxConsumer c : consumers) {
                for (AgentEventType t : AgentEventType.values()) {
                    if (t.isPerceptionLadder() && c.supports(t)) {
                        fail("容器里出现了认领阶梯事件的消费者: " + c.getClass().getSimpleName()
                                + " 支持 " + t + " —— 切流时这条必须先有结论");
                    }
                }
            }
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 等她那封信被拆开(异步, 在 her actor 线程上)。最多等 10 秒。 */
    private String awaitConsumed(String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            long consumed = inbox.countByAgentIdAndStatus(id, AgentInboxEntry.S_CONSUMED);
            if (consumed > 0) {
                return AgentInboxEntry.S_CONSUMED;
            }
            long failed = inbox.countByAgentIdAndStatus(id, AgentInboxEntry.S_FAILED);
            if (failed > 0) {
                List<AgentInboxEntry> rows = inbox.findTop200ByAgentIdAndStatusOrderByOccurredAtAsc(
                        id, AgentInboxEntry.S_FAILED);
                fail("信被拆坏了: " + (rows.isEmpty() ? "?" : rows.get(0).getLastError()));
            }
            Thread.sleep(50);
        }
        List<AgentInboxEntry> pending = inbox.findTop200ByAgentIdAndStatusOrderByOccurredAtAsc(
                id, AgentInboxEntry.S_PENDING);
        return pending.isEmpty() ? "没有信" : pending.get(0).getStatus();
    }
}
