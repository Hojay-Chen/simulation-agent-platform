package com.luxera.companion.action;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.phone.AwarenessLadder;
import com.luxera.companion.phone.MessageBatch;
import com.luxera.companion.phone.PhoneCapability;
import com.luxera.companion.phone.ReadPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * V11 §7.2 / §24.3 —— <b>「她看了一眼」这个动作</b>。正文进入认知的唯一入口。
 *
 * <h2>为什么必须是一个 Action, 而不是 AgentRuntime 里的几行</h2>
 * 因为"读"在 V11 里不是一个取数操作, 而是一个<b>决定</b>。今天它是前者:
 * {@code AgentRuntime.onChatMessageDelivered} 在问"她注意到了吗"之前就把整个会话的
 * 正文拉了下来。一旦正文在手, 后面所有的"她在忙 / 没注意到 / 已读不回"都只是已经
 * 知道内容之后找的借口 —— 那些判断不再是关于她的, 而是关于文本的。
 *
 * <p>把它做成一个显式的动作之后, 有三件事同时成立:
 * <ol>
 *   <li><b>可被拒绝。</b>决策层可以说"不看"(她正忙着), 而这个"不看"是真实的:
 *       正文确实没有进入进程。这是一个可以被断言的事实, 不是一个说法。</li>
 *   <li><b>可被计数。</b>"她今天读了多少次消息"变成一个数字。占用注意力是要有代价的,
 *       而一个没有次数的动作没有代价。</li>
 *   <li><b>可被审计。</b>每次读都留下一条 READ 台阶事件, 带上是哪条传输把正文
 *       送到设备上的。见 {@link AwarenessLadder#read}。</li>
 * </ol>
 *
 * <h2>与 {@code InspectDeviceDecision} 的关系</h2>
 * V10 已经定义了"查看设备: 值得看一眼(打开会话读取消息)"这个决策, 但它今天是一个
 * <b>没有执行者的输出</b> —— 决策产生了, 然后被丢掉。本类就是它的执行者:
 * 决策说"看一眼", 这里就真的去看一眼。
 *
 * <h2>顺序: 先读, 后记</h2>
 * 记 READ 台阶这件事放在读取<b>之后</b>, 因为顺序反过来会制造一个谎:
 * 若先记阶梯再读, 那么读取失败时数据库里会留下一条"她读了", 而她其实什么都没看到。
 * 阶梯是事实的记录, 不是意图的记录。
 */
@Service
@Slf4j
public class ReadMessagesAction {

    private final PhoneCapability phone;
    private final AwarenessLadder ladder;

    public ReadMessagesAction(PhoneCapability phone, AwarenessLadder ladder) {
        this.phone = phone;
        this.ladder = ladder;
    }

    /**
     * 执行一次读取。
     *
     * @return 读到的批次。<b>读不到时返回空批次, 不抛异常</b> —— 手机没电、没信号
     *         都是她生活的一部分, 不是系统的异常。调用方靠 {@link MessageBatch#note()}
     *         区分"没人找她"与"她错过了什么"
     */
    public MessageBatch execute(String agentId, String conversationId, ReadPolicy policy) {
        MessageBatch batch = phone.readMessages(agentId, conversationId, policy);
        if (batch == null) {
            return MessageBatch.unreachable("能力实现返回了 null");
        }

        List<String> ids = AwarenessLadder.idsOf(batch);
        if (!ids.isEmpty()) {
            ladder.read(agentId, conversationId, ids, batch.transport());
            log.debug("[ReadMessages] {} 读了 {} 条(经 {}), note={}",
                    agentId, ids.size(), batch.transport().wire(), batch.note());
        } else {
            // 想读但没读到 —— 这不是"没有消息", 恰恰相反, 它意味着她可能正在错过什么。
            // 所以按 WARN 记, 而不是按 DEBUG 混在正常流程里。
            log.info("[ReadMessages] {} 没读到期盼的消息: {}", agentId, batch.note());
        }
        return batch;
    }

    /**
     * 便捷入口: 读送达事件点名的那几条。
     *
     * <p>传的是 id 而不是"整个会话", 这是刻意的 —— 见 {@link ReadPolicy}。
     */
    public MessageBatch readDelivered(String agentId, String conversationId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return MessageBatch.unreachable("没有点名要读的消息");
        }
        return execute(agentId, conversationId, ReadPolicy.ids(messageIds));
    }

    /** 她自己去翻聊天记录(不由送达驱动)。 */
    public MessageBatch browse(String agentId, String conversationId, int limit) {
        return execute(agentId, conversationId, ReadPolicy.recent(limit));
    }

    /** 读到的正文。调用方只有在真的需要文本时才调用它 —— 拿不到批次就什么都拿不到。 */
    public static List<String> contentsOf(MessageBatch batch) {
        if (batch == null) {
            return List.of();
        }
        return batch.messages().stream()
                .map(MessageView::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList();
    }
}
