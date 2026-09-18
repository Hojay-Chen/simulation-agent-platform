package com.luxera.companion.runtime.v11;

import com.luxera.companion.action.ReadMessagesAction;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.attention.DeliverySalience;
import com.luxera.companion.attention.DeliverySignals;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.mind.ConversationTurnAggregator;
import com.luxera.companion.phone.AwarenessLadder;
import com.luxera.companion.phone.MessageBatch;
import com.luxera.companion.phone.PhoneState;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * V11 §2.2.2 / §7.2 —— <b>消息送达的 V11 主链</b>。
 *
 * <h2>它把一条消息的到达拆成了什么</h2>
 * <pre>
 *   消息到达(世界的事实)
 *     → RECEIVED          她还没被通知
 *     → NOTIFIED          手机响了
 *     → 算显著性(不读正文) → 注意 → NOTICED / 没注意到
 *     → 决定了要看 → READ  ← 正文到这一刻才允许进入进程
 *     → (Phase 3 打开时) 攒成一个回合, 由 {@link V11TurnPath} 在封口时读一次
 * </pre>
 * 老链是"到达 = 读取 = 处理"三步并作一步。本类存在的全部意义, 是让中间那两步
 * <b>可以被拒绝、可以被计数、可以被断言</b>。
 *
 * <h2>两个方法, 一条硬边界</h2>
 * <ul>
 *   <li>{@link #assess} —— <b>无副作用</b>。只算, 不写库、不发事件、不读正文。
 *       这是 shadow 模式下唯一被调用的东西, 所以 shadow 真的是观察。</li>
 *   <li>{@link #deliver} —— 有副作用: 走阶梯、建通知、读正文。
 *       只在 {@code enabled=true} 时调用。</li>
 * </ul>
 * 把这条边界画在两个方法之间(而不是靠一个 if)是为了让"shadow 不许写库"
 * 变成调用方一眼能看见的事实, 而不是一个需要读完整个方法体才能确认的性质。
 *
 * <h2>为什么是"她自己决定要不要看", 而不是"消息驱动她去看"</h2>
 * 因为老链里"她注意到了吗"是在正文被读完、并被 {@code EmotionAgent} 做了情绪评估
 * <b>之后</b>才算出来的(见 {@code MessagePipeline} 第 2 步: salience 来自 emotion.delta())。
 * 那个顺序让"没注意到"永远不可能是真的 —— 内容已经在她手里了。
 * 本类把显著性换成 {@link DeliverySalience}: 一个只看连发条数、关系数值、
 * 时间戳的纯函数, <b>结构上拿不到正文</b>。
 *
 * <h2>今天它是 shadow</h2>
 * {@code app.v11.runtime.enabled} 默认 false。默认只跑 {@link #assess} 并把
 * 新门与老链的分歧记进 {@link V11DeliveryShadow} —— 因为这是本轮唯一一处
 * 会让 53 个 agent 行为真的发生变化的地方, 它必须先在真实流量下被对比过。
 */
@Service
@Slf4j
public class V11DeliveryPath {

    /** 与 {@code MessagePipeline.NOTICE_THRESHOLD} 取同一个值 —— 切流时阈值不该同时跳档。 */
    private static final double NOTICE_THRESHOLD = 0.3;

    /**
     * 早期关系的保底注意, 与 {@code MessagePipeline} 里 {@code noticeProb = max(noticeProb, 0.55)}
     * 同源同值。真人刚认识一个人时会认真看消息, 这一条不该在换实现时丢掉。
     *
     * <p>近似之处: 老链用的是 {@code rel.getMessageCount()}, 这里用的是信号里的
     * {@code max(会话条数, 关系条数)}。差别只在"会话比关系还长"的少数情况,
     * 且方向是<b>更不容易触发保底</b>。写在这里是因为它会让 shadow 的分歧率
     * 略微偏向"新门更严厉", 而知道偏差朝哪边偏, 比假装没有偏差重要。
     */
    private static final double EARLY_RELATION_FLOOR = 0.55;

    private final DeliverySignals signals;
    private final AttentionService attentionService;
    private final PhoneStateService phoneStates;
    private final AgentStateService agentStates;
    private final CompanionSchedule schedule;
    private final AwarenessLadder ladder;
    private final ReadMessagesAction readMessages;
    private final V11TurnPath turnPath;

    public V11DeliveryPath(DeliverySignals signals, AttentionService attentionService,
                           PhoneStateService phoneStates, AgentStateService agentStates,
                           CompanionSchedule schedule, AwarenessLadder ladder,
                           ReadMessagesAction readMessages, V11TurnPath turnPath) {
        this.signals = signals;
        this.attentionService = attentionService;
        this.phoneStates = phoneStates;
        this.agentStates = agentStates;
        this.schedule = schedule;
        this.ladder = ladder;
        this.readMessages = readMessages;
        this.turnPath = turnPath;
    }

    /**
     * 一次送达的判定结果。<b>这是一个关于她的结论, 不是关于文本的结论。</b>
     *
     * @param salience          这条消息本身有多显眼(不读正文算出来的)
     * @param noticeProbability 连同她此刻的作息/精力/手机一起算出的"会不会注意到"
     * @param noticed           判定: 她注意到了没有
     * @param reason            给日志与 shadow 用的一句话
     */
    public record Assessment(double salience, double noticeProbability,
                             boolean noticed, String reason) {}

    // ─────────────────────────── 无副作用: 判定 ───────────────────────────

    /**
     * <b>只算, 不动任何东西。</b>
     *
     * <p>不写阶梯事件、不发通知、不读正文 —— 所以可以在真实流量上一直跑着。
     * 任何一条"顺手记一下"加进来, shadow 就不再是 shadow。
     */
    public Assessment assess(String userId, String agentId, String conversationId,
                             int burstSize, LocalDateTime now) {
        DeliverySalience.Signals s = signals.collect(agentId, userId, conversationId, burstSize);
        double salience = DeliverySalience.of(s);

        AttentionService.Attention attention;
        try {
            PhoneState phone = phoneStates.current(agentId, now);
            // get 而不是 getOrCreate: 后者在行不存在时会 repo.save() 一条 ——
            // 那正是"shadow 只许计算"最容易被破坏的地方, 而且从签名上看不出来。
            // 状态还没有行时 compute 会退到中性默认值(energy 0.6 / stress 0.3),
            // 对一个"顺路看一眼"的判定来说完全够用。
            AgentState state = agentStates.get(agentId);
            attention = attentionService.compute(schedule.activityFor(agentId, now), state, phone, salience);
        } catch (Exception e) {
            // 算不出来时按"她会注意到"处理。宁可让她多看一眼, 也不要因为一次查询失败
            // 让她从此对消息无反应 —— 后者不报错, 只表现为"她突然不理人了"。
            log.warn("[V11] {} 注意力计算失败, 按会注意到处理: {}", agentId, e.getMessage());
            return new Assessment(salience, 1.0, true, "注意力计算失败, 保守判为注意到");
        }

        double noticeProb = attention.noticeProbability();
        if (s.messageCount() < 8) {
            noticeProb = Math.max(noticeProb, EARLY_RELATION_FLOOR);
        }
        boolean noticed = noticeProb >= NOTICE_THRESHOLD;
        String reason = noticed
                ? String.format("注意到了(sal=%.2f noticeP=%.2f)", salience, noticeProb)
                : String.format("没注意到(sal=%.2f noticeP=%.2f, 阈值%.2f)", salience, noticeProb, NOTICE_THRESHOLD);
        return new Assessment(salience, noticeProb, noticed, reason);
    }

    // ─────────────────────────── 有副作用: 执行 ───────────────────────────

    /**
     * enabled 模式下真正走一遍送达。
     *
     * <p>四步的顺序是有意义的, 每一步都对应阶梯上的一级:
     * <ol>
     *   <li><b>RECEIVED</b> —— 消息存在于世界(对方的手机上显示"已送达")。</li>
     *   <li><b>NOTIFIED</b> —— 手机响了。到此为止她仍然什么都不知道。</li>
     *   <li><b>判定</b> —— 没注意到就<b>到此结束</b>, 消息保持未读。
     *       这是老链做不到的那件事: 一个真实的、可以被查询的"她没看见"。</li>
     *   <li><b>NOTICED → READ</b> —— 只有判定为注意到了, 才去读正文。
     *       正文在这一行之前没有进入过本进程。</li>
     * </ol>
     *
     * @param sink 读到正文之后的去处。用 {@link Consumer} 而不是直接调
     *             {@code AgentRuntime}, 是为了让本类不反向依赖调用方(那会成环),
     *             也让测试能塞一个记账的 sink, 不必启动整条认知链
     * @return true 表示 V11 已接管这次送达, 调用方<b>不得</b>再走老链。
     *         今天四种结局都返回 true(包括"没注意到"和"没读到") —— 它们都是
     *         V11 给出的<b>结论</b>, 而不是"V11 放弃了, 让老链再来一遍"
     */
    public boolean deliver(String userId, String agentId, String conversationId,
                           List<String> messageIds, Assessment a, LocalDateTime now,
                           Consumer<List<MessageView>> sink) {
        // 1+2. 世界的事实与手机的响动。这两级无论她注意与否都发生过。
        ladder.received(agentId, conversationId, messageIds);
        ladder.notified(agentId, conversationId, messageIds);

        // 3. 判定。没注意到 → 结束。消息留在未读, 而"她没看见"是一个可查询的事实。
        if (!a.noticed()) {
            log.info("[V11] {} 送达但{}", agentId, a.reason());
            return true;
        }
        ladder.noticed(agentId, conversationId, messageIds);

        // 4. 她决定看一眼。
        //
        // 4a. Phase 3 接管时, "看"这件事被推迟到回合封口 —— 因为对方可能还在说。
        //     这里只把消息<b>记进回合</b>: 不读正文, 于是三句话到封口时只读一次、
        //     只记一次 READ 台阶。正文在这一行之后仍然没有进入本进程。
        if (turnPath.isAggregating()) {
            turnPath.accept(new ConversationTurnAggregator.Delivery(
                    agentId, userId, conversationId, messageIds, now), sink);
            return true;
        }

        // 4b. 没有回合合并: 老行为 —— 注意到了就看一眼
        readAndHand(agentId, conversationId, messageIds, sink);
        return true;
    }

    /**
     * 她看了一眼, 并把看到的交给认知。<b>合并与不合并共用这一段。</b>
     *
     * <p>抽出来是为了让"有没有回合合并"这个差异只体现在<b>什么时候读</b>上,
     * 而不是连"读到之后怎么交出去"都各写一遍 —— 后者迟早会漂移成两种行为。
     */
    void readAndHand(String agentId, String conversationId, List<String> messageIds,
                     Consumer<List<MessageView>> sink) {
        MessageBatch batch = readMessages.readDelivered(agentId, conversationId, messageIds);
        List<MessageView> messages = batch.messages();
        if (messages.isEmpty()) {
            // 她看了一眼, 却什么都没读到(设备没配对/没信号/消息比扫描窗口更老)。
            // 这不是"没人找她", 是"她正在错过什么" —— 所以按 WARN, 不混进正常流程。
            log.warn("[V11] {} 注意到了但没读到正文: {}", agentId, batch.note());
            return;
        }
        log.info("[V11] {} 读到了 {} 条(经 {}), 进入认知链",
                agentId, messages.size(), batch.transport().wire());
        sink.accept(messages);
    }

    // ─────────────────────────── shadow 记录 ───────────────────────────

    /**
     * 把一次对比交给记录器。<b>shadow 模式下唯一被允许产生的效果就是这一行。</b>
     *
     * @param readCount 见 {@link V11DeliveryShadow#record} —— shadow 传负数表示"没尝试读"
     */
    public void recordShadow(V11DeliveryShadow shadow, String agentId, int burstSize,
                             Assessment a, int readCount, LocalDateTime now) {
        shadow.record(agentId, burstSize, a.salience(), a.noticeProbability(),
                a.noticed(), true, readCount, now);
    }

    /**
     * shadow 下的回合观测: 让聚合状态机跑一遍, 只留数字。
     *
     * <p>为什么喂进去的是<b>判定为注意到</b>的那些: 合并率要回答的问题是"切流之后
     * 几句话算一次认知", 而切流之后只有被注意到的消息才进回合。把没注意到的也算进去,
     * 会得到一个好看的、但属于另一个世界的数字。
     *
     * <p>shadow 模式下老链仍然处理<b>全部</b>消息(它不知道"没注意到"这个概念),
     * 所以两个数字之间的落差是预期内的, 不是 bug。
     */
    public void observeTurn(String userId, String agentId, String conversationId,
                            List<String> messageIds, Assessment a, LocalDateTime now) {
        if (!turnPath.isObserving() || !a.noticed()) {
            return;
        }
        turnPath.observe(new ConversationTurnAggregator.Delivery(
                agentId, userId, conversationId, messageIds, now));
    }
}
