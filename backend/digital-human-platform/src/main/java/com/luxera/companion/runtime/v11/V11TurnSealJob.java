package com.luxera.companion.runtime.v11;

import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.mind.ConversationTurnAggregator;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.runtime.AgentRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V11 §8.2 —— <b>每秒问一次"有谁的回合该封口了吗"</b>。
 *
 * <h2>为什么必须是一个定时器, 而不是在投递线程上 sleep</h2>
 * "等 4 秒看看还有没有新消息"这句话最自然的实现是 {@code Thread.sleep(4000)}, 而它在这里
 * 有两个不能接受的后果:
 * <ol>
 *   <li>投递跑在 <b>per-agent 的 mailbox 消费者线程</b>上。sleep 4 秒 = 这个 agent 的
 *       整个事件队列停 4 秒。而 "她安静地想一会儿" 与 "她卡住了" 在现象上一模一样。</li>
 *   <li>它会与 {@code AgentRuntime.process} 抢同一把 person 锁, 于是那段睡眠
 *       还额外挡住了别人(比如 WSS 上刚发来的一句话)。</li>
 * </ol>
 *
 * <h2>这个类最重要的一条纪律: 定时线程上不许跑认知</h2>
 * Spring 默认的 {@code ThreadPoolTaskScheduler} <b>池大小是 1</b>(本工程没有配
 * {@code spring.task.scheduling.pool.size}, 也没有自定义 TaskScheduler bean),
 * 全平台 ~19 个 {@code @Scheduled} 任务共用这<b>一根</b>线程 —— 其中包括 5 秒一次的
 * {@code outbox-relay}。认知链一次要跑几秒到几十秒的 LLM。所以本方法对每个到期回合
 * <b>只做两件轻事</b>: 问一句开关, 把活儿塞进那个 agent 自己的 mailbox, 然后立刻返回。
 * 认知在 agent 自己的消费线程上跑 —— 那也是它今天本来就在跑的线程。
 *
 * <p>这条纪律不是靠注释维持的: {@code V11TurnSealJobTest} 用一个"记录自己在哪条线程上
 * 被调用"的 sink 断言认知不在调度线程上跑。一个守不住这条的实现不会慢一点, 它会
 * 让全平台的 outbox 中继一起停摆。
 *
 * <h2>为什么不做"一轮最多处理 N 个"的限流</h2>
 * 因为封口是<b>破坏性</b>的: {@code sealDue} 把回合从 map 里摘掉并计入
 * {@code turnsSealed}。若在拿到列表之后再截断, 被截掉的那些回合既已消失、又没人处理 ——
 * 那不是限流, 是静默丢消息。真需要限流时, 它必须长在 {@code sealDue} 内部。
 * 今天不需要: 这里的每个回合只花一次主键查询(开关)+ 一次入队。
 */
@Component
@Slf4j
public class V11TurnSealJob {

    private final ConversationTurnAggregator turns;
    private final V11TurnsSwitch turnsSwitch;
    private final V11TurnPath turnPath;
    private final AgentRuntime runtime;
    private final PersonActorRegistry personActors;
    private final AgentSwitchService agentSwitch;

    public V11TurnSealJob(ConversationTurnAggregator turns, V11TurnsSwitch turnsSwitch,
                          V11TurnPath turnPath, AgentRuntime runtime,
                          PersonActorRegistry personActors, AgentSwitchService agentSwitch) {
        this.turns = turns;
        this.turnsSwitch = turnsSwitch;
        this.turnPath = turnPath;
        this.runtime = runtime;
        this.personActors = personActors;
        this.agentSwitch = agentSwitch;
    }

    // 默认值里不能带引号: YAML 里那个 '*/1 * * * * *' 的引号是给 YAML 的(* 开头会被当成
    // 别名), 而 ${key:default} 的 default 是<b>逐字</b>取的 —— 复制粘贴过来会得到一个
    // 以单引号开头的非法 cron, 表现为启动时 IllegalStateException: invalid @Scheduled method
    @Scheduled(cron = "${app.scheduler.turn-seal-cron:*/1 * * * * *}")
    public void tick() {
        if (!turnsSwitch.isActive()) {
            return;   // 两个开关都关着: 连问都不问(这是默认状态, 必须零成本)
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            if (turnsSwitch.isEnabled()) {
                sealAndHand(now);
            } else {
                // shadow: 状态机照跑、数字照记, 但到期回合到此为止 —— 不进认知。
                // 这不是"少做了一步", 是 shadow 的定义: 老链仍然是唯一在回话的那条路。
                turnPath.drainDue(now);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被 Spring 记一条然后<b>继续下一轮</b>, 所以这里吞掉不会
            // 造成任务永久停摆; 但吞掉之前必须留下痕迹 —— 否则"她不回话"会是一个
            // 没有任何日志可查的现象。回合仍在 map 里(sealDue 之前就抛), 下一秒会重试。
            log.error("[Turn] 封口轮次异常: {}", e.getMessage(), e);
        }
    }

    private void sealAndHand(LocalDateTime now) {
        List<ConversationTurnAggregator.Turn> due = turnPath.dueTurns(now);
        if (due.isEmpty()) {
            return;
        }
        log.debug("[Turn] {} 个回合到期, 逐个送入各自的邮箱", due.size());
        for (ConversationTurnAggregator.Turn turn : due) {
            hand(turn);
        }
    }

    /**
     * 把一个封好的回合交给它自己的 agent。
     *
     * <p>两次开关检查是刻意的, 不是冗余:
     * <ul>
     *   <li>这里 —— 现在就被暂停的 agent 连队都不用排(封口已经发生, 账目照记,
     *       但 {@code cognized=false})。</li>
     *   <li>任务体内 —— 排队期间可能有人按了暂停。那是**用户刚下的指令**,
     *       比一秒前的检查新。同时 {@code AgentRuntime.process} 自己也会再查一次,
     *       三道都对"暂停"给出同一个答复。</li>
     * </ul>
     */
    private void hand(ConversationTurnAggregator.Turn turn) {
        String agentId = turn.agentId();
        if (!agentSwitch.isRunnable(agentId)) {
            turnPath.abandon(turn, LocalDateTime.now(), "agent 已暂停");
            return;
        }
        personActors.tell(agentId, () -> {
            if (!agentSwitch.isRunnable(agentId)) {
                turnPath.abandon(turn, LocalDateTime.now(), "排队期间被暂停");
                return;
            }
            // 读正文与认知都发生在这条 per-agent 线程上 —— 与老链同一条线程,
            // 于是"回合合并"没有引入任何一种新的并发形态。
            turnPath.seal(turn, LocalDateTime.now(),
                    msgs -> runtime.process(turn.userId(), turn.agentId(), turn.conversationId(), msgs));
        });
    }
}
