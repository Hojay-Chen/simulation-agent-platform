package com.luxera.companion.attention;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * V11 §2.2.2 —— 把"送达那一刻成立的事实"收集成 {@link DeliverySalience.Signals}。
 *
 * <h2>这个类的唯一职责: 保证正文不过境</h2>
 * 它可以从 {@code ChatWorldPort} 上取到整个会话的每一条正文 —— 它<b>不取</b>。
 * 只取两类东西:
 *
 * <ol>
 *   <li>{@code conversation(conversationId)} → {@link ConversationView}: 元数据
 *       (messageCount / lastMessageAt)。<b>这个类型里根本没有正文字段</b>,
 *       所以"顺手读一下"在这里是类型层面做不到的。</li>
 *   <li>{@code relationshipService.find(...)} → 关系数值。</li>
 * </ol>
 *
 * <p>选择 {@code conversation()} 而不是 {@code messages()} / {@code recentMessages()}
 * 是有意的, 不是碰巧: 后两者返回 {@code List<MessageView>}, 而 {@code MessageView}
 * 带 {@code content}。只要那个列表进了本进程的栈, "她还没决定要看"就成了一句空话 ——
 * 内容在内存里躺着, 谁都可以顺手拿来算个分。真正的边界不是"我不读那个字段",
 * 而是<b>那个字段从来没被取回来过</b>。
 *
 * <h2>取不到时怎么办</h2>
 * 关系查不到、会话查不到 —— 都不能让送达这件事失败(消息已经在库里了, 那是既成事实)。
 * 一律退到中性值: 显著性回到基线的 0.3, 也就是"一条普通消息"。<b>宁可让她正常地
 * 注意到一条普通消息, 也不要因为一次查询失败而让她对什么都没反应</b> ——
 * 后者会表现成"她突然不理人了", 而且不报错。
 */
@Service
@Slf4j
public class DeliverySignals {

    private final ChatWorldPort chatWorld;
    private final RelationshipService relationships;

    public DeliverySignals(ChatWorldPort chatWorld, RelationshipService relationships) {
        this.chatWorld = chatWorld;
        this.relationships = relationships;
    }

    /**
     * @param burstSize 这次送达包含几条消息。这是唯一必须由调用方给的信号 ——
     *                  只有调用方知道"这一次"是哪一次
     */
    public DeliverySalience.Signals collect(String agentId, String userId, String conversationId,
                                            int burstSize) {
        int messageCount = 0;
        try {
            Optional<ConversationView> conv = chatWorld.conversation(conversationId);
            if (conv.isPresent()) {
                // 只读元数据。这里没有、也不该有 getContent()。
                messageCount = conv.get().getMessageCount();
            }
        } catch (Exception e) {
            log.debug("[Signals] 读会话元数据失败(按默认值继续) conv={}: {}", conversationId, e.getMessage());
        }

        double intimacy = 0.05;
        double affection = 0.2;
        try {
            Relationship rel = relationships.find(userId, agentId);
            if (rel != null) {
                intimacy = rel.getIntimacy();
                affection = rel.getAffection();
                // 关系里累计说了多少句 —— 比会话内的条数更能代表"认识多久了"
                messageCount = Math.max(messageCount, rel.getMessageCount());
            }
        } catch (Exception e) {
            log.debug("[Signals] 读关系失败(按默认值继续) {}x{}: {}", userId, agentId, e.getMessage());
        }

        return new DeliverySalience.Signals(
                Math.max(1, burstSize),
                // 她上一次回话是什么时候 —— 聊天平台的送达事件今天不带这个字段,
                // 所以恒为"很久以前", "她刚回过话"这个加成暂时是惰性的。
                // 见 DeliverySalience 的"两个今天恒为默认值的入参"。
                DeliverySalience.NEVER_REPLIED_MINUTES,
                messageCount,
                intimacy,
                affection,
                // 聊天平台今天不送紧急度。传 0 = "不知道", 而不是"猜一个"。
                0.0);
    }
}
