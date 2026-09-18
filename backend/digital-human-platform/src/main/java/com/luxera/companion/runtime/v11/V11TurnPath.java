package com.luxera.companion.runtime.v11;

import com.luxera.companion.action.ReadMessagesAction;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.mind.ConversationTurnAggregator;
import com.luxera.companion.mind.MindStateService;
import com.luxera.companion.phone.MessageBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * V11 §8 —— <b>回合这条链上唯一知道"封口之后要发生什么"的地方</b>。
 *
 * <pre>
 *   送达 → ConversationTurnAggregator(纯状态机)   攒着
 *        → 本类                                   把"攒着"这件事写进她的心智
 *        → 封口 → ReadMessagesAction 读一次 → 认知链
 * </pre>
 *
 * <h2>为什么要单独一层, 而不是塞进 V11DeliveryPath</h2>
 * {@code V11DeliveryPath} 回答的是"这一条消息她注意到了吗"(Phase 2), 本类回答的是
 * "她注意到的那几条消息什么时候算一次"。两者的失败方式完全不同, 混在一起会让
 * "她为什么不回话"这个问题重新变成一个需要通读一个长方法才能回答的问题。
 * 更实际的理由: 封口要能被<b>定时器</b>触发({@code V11TurnSealJob}), 而那个触发点
 * 需要一个不依赖投递线程的入口 —— 就是这个类。
 *
 * <h2>三条边界</h2>
 * <ol>
 *   <li><b>读正文只发生一次</b> —— 三句话并成一个回合, 于是只读一次、只记一次 READ 台阶。
 *       老链的"一次送达一次读"在合并之后会变成三次读取同一批消息。</li>
 *   <li><b>心智只在 enabled 时写</b> —— shadow 下聚合是<b>假设</b>的, 把假设写进她的心智表,
 *       会让"她手上挂着什么"这条查询在切流前后含义不同。shadow 期要回答的问题只有一个:
 *       合并率是多少。那个数字在 {@code ConversationTurnAggregator.stats()} 里, 有读出口。</li>
 *   <li><b>认知失败不影响回合账目</b> —— 回合已经封口这件事是事实, 不因为一次
 *       {@code process} 抛异常而改变; 但 {@code last_cognitive_at} 只在认知真的跑过时才推。</li>
 * </ol>
 */
@Service
@Slf4j
public class V11TurnPath {

    private final ConversationTurnAggregator turns;
    private final V11TurnsSwitch turnsSwitch;
    private final MindStateService mindStates;
    private final ReadMessagesAction readMessages;

    public V11TurnPath(ConversationTurnAggregator turns, V11TurnsSwitch turnsSwitch,
                       MindStateService mindStates, ReadMessagesAction readMessages) {
        this.turns = turns;
        this.turnsSwitch = turnsSwitch;
        this.mindStates = mindStates;
        this.readMessages = readMessages;
    }

    /** 回合合并是不是在驱动认知。 */
    public boolean isAggregating() {
        return turnsSwitch.isEnabled();
    }

    /** 聚合状态机要不要跑(含 shadow)。 */
    public boolean isObserving() {
        return turnsSwitch.isActive();
    }

    public ConversationTurnAggregator aggregator() {
        return turns;
    }

    // ─────────────────────────── 收 ───────────────────────────

    /**
     * enabled: 收下一次送达。
     *
     * <p>注意它是 {@code void} —— 调用方<b>不需要</b>知道这次有没有当场封口。
     * 那个问题("正文读了吗")的答案只有一个形式: 认知链有没有被调用。让 {@code deliver}
     * 去返回一个"是否已经处理"的布尔值, 等于让 V11 送达主链关心一个它不该关心的细节。
     *
     * @param sink 读到正文之后的去处(与 {@code V11DeliveryPath.deliver} 同一个 sink)
     */
    public void accept(ConversationTurnAggregator.Delivery delivery, Consumer<List<MessageView>> sink) {
        ConversationTurnAggregator.Acceptance acc = turns.accept(delivery);
        if (acc.turnId() == null) {
            return;
        }
        mindStates.noteThreadHolding(delivery.agentId(), delivery.conversationId(), delivery.userId(),
                acc.turnSize(), acc.openedNewTurn(), delivery.at());
        if (acc.forced()) {
            // 攒满了就不再等静默窗口 —— 她不会因为对方还在刷屏而永远不回话
            log.info("[Turn] {} 回合 {} 攒满 {} 条, 当场处理", delivery.agentId(),
                    acc.turnId(), acc.turnSize());
            seal(acc.sealedNow(), delivery.at(), sink);
        }
    }

    /**
     * shadow: 跑一遍状态机, 只留数字。
     *
     * <p>它<b>不读正文、不写心智、不调认知</b> —— 但状态机与 enabled 时完全一致,
     * 所以它算出来的合并率是对"切流之后会怎样"的真实预测, 而不是一个估计。
     * 当场攒满的回合在这里同样被封掉并计数, 只是没有人去处理它。
     */
    public void observe(ConversationTurnAggregator.Delivery delivery) {
        ConversationTurnAggregator.Acceptance acc = turns.accept(delivery);
        if (acc.forced()) {
            log.info("[Turn][shadow] {} 的回合 {} 在这里就会封口(攒满 {} 条), 但 shadow 不接管认知",
                    delivery.agentId(), acc.turnId(), acc.turnSize());
        }
    }

    // ─────────────────────────── 封 ───────────────────────────

    /** 到期的回合。由 {@code V11TurnSealJob} 取走并逐个送进她自己的邮箱。 */
    public List<ConversationTurnAggregator.Turn> dueTurns(LocalDateTime now) {
        return turns.sealDue(now);
    }

    /** shadow: 只把到期的回合封掉(数字进计数器), 不进认知。 */
    public int drainDue(LocalDateTime now) {
        List<ConversationTurnAggregator.Turn> due = turns.sealDue(now);
        if (!due.isEmpty()) {
            log.info("[Turn][shadow] {} 个回合到期, 合并率 {}(没有接管认知)",
                    due.size(), String.format("%.2f", turns.stats().messagesPerTurn()));
        }
        return due.size();
    }

    /**
     * 一个回合真正进入认知: 读<b>一次</b>正文, 交给 sink。
     *
     * @return 正文读到了并且交给了认知(true) / 什么都没读到或认知抛了(false)
     */
    public boolean seal(ConversationTurnAggregator.Turn turn, LocalDateTime now,
                        Consumer<List<MessageView>> sink) {
        MessageBatch batch;
        try {
            batch = readMessages.readDelivered(turn.agentId(), turn.conversationId(),
                    turn.messageIds());
        } catch (Exception e) {
            // 读这一步也会炸(设备服务挂了/DB 抖了一下), 而它炸在这里的后果特别隐蔽:
            // 回合已经被聚合器摘掉, 异常却会一路逃到 PersonActor 的错误出口 ——
            // 于是这个回合既不在队列里、也没留下任何账目, 只在日志里有一行 error。
            // 所以在<b>这里</b>把它收成一次"读不到", 让下面那段如实记账。
            log.warn("[Turn] {} 回合 {} 读取正文时异常: {}", turn.agentId(), turn.turnId(), e.getMessage());
            batch = null;
        }
        if (batch == null || batch.messages().isEmpty()) {
            // 她注意到了, 但在封口这一刻什么都没读到(设备没配对/没信号/消息比扫描窗口更老)。
            // 这不是"没人找她", 是"她正在错过什么" —— 按 WARN, 并且不把 last_cognitive_at 往前推。
            log.warn("[Turn] {} 回合 {} 封口时没读到正文: {}", turn.agentId(), turn.turnId(),
                    batch == null ? "读取异常" : batch.note());
            mindStates.noteTurnSealed(turn.agentId(), turn.conversationId(), turn.turnId(),
                    turn.size(), false, now);
            return false;
        }
        boolean cognized = false;
        try {
            sink.accept(batch.messages());
            cognized = true;
        } catch (Exception e) {
            // 认知抛异常不该让调度线程看到, 也不该让这个回合的账目消失
            log.warn("[Turn] {} 回合 {} 进入认知时异常: {}", turn.agentId(), turn.turnId(), e.getMessage());
        }
        if (cognized) {
            turns.noteCognized();
        }
        mindStates.noteTurnSealed(turn.agentId(), turn.conversationId(), turn.turnId(),
                turn.size(), cognized, now);
        return cognized;
    }

    /**
     * 一个回合被丢掉(agent 被暂停、或者已经不需要它了)。
     *
     * <p>账目照样要记 —— 回合在时序上确实结束了 —— 但 {@code cognized=false},
     * 于是一个被暂停丢掉的回合不会把"她上一次真正想过事"往前推。
     */
    public void abandon(ConversationTurnAggregator.Turn turn, LocalDateTime now, String reason) {
        log.info("[Turn] {} 回合 {} 被丢弃({}): {} 条消息未处理",
                turn.agentId(), turn.turnId(), reason, turn.size());
        mindStates.noteTurnSealed(turn.agentId(), turn.conversationId(), turn.turnId(),
                turn.size(), false, now);
    }
}
