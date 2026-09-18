package com.luxera.companion.phone;

import java.util.Collection;
import java.util.List;

/**
 * V11 §7.2 —— <b>「这次要读什么」</b>。
 *
 * <p>为什么读消息要带上一个策略对象, 而不是直接传个会话 id: 因为"读"这个动作在 V11 里
 * 是有代价的 —— 它把消息正文放进她的认知。一个不带范围的读接口会诱使调用方写成
 * "把整个会话拉下来我自己筛", 而那正是本次要消灭的形状
 * ({@code AgentRuntime.onChatMessageDelivered} 今天就长这样: 在"她注意到了吗"这个问题
 * 被问出来之前, 整个会话的正文已经在她手里了)。
 *
 * <p>把范围写进类型之后, "她只读了这一条"是一个可以被断言的事实, 而不是调用方的自觉。
 *
 * <h2>两个策略, 对应两种真实情形</h2>
 * <ul>
 *   <li>{@link ByIds} —— 手机响了, 通知上写着"3 条新消息", 她划开看的就是这 3 条。
 *       这是最常见的一种。</li>
 *   <li>{@link Recent} —— 她<b>自己</b>决定去翻聊天记录("上次他说什么来着")。
 *       这一种由她自己发起, 不由送达驱动。</li>
 * </ul>
 *
 * <p>刻意没有"读整个会话"这个策略: 它等价于 {@code Recent(MAX)}, 而把那个上限写成常量
 * 比让它隐式地等于"全部"要好 —— 想读全部就得明写一个数字, 那个数字会在 code review 里
 * 被看见。
 */
public sealed interface ReadPolicy permits ReadPolicy.ByIds, ReadPolicy.Recent {

    /** 这一次最多会读到几条 —— 调用方用它来判断"读取量是否合理"。 */
    int upperBound();

    /** 这次读是否由"送达事件"驱动(而不是她自己去翻)。 */
    boolean deliveryDriven();

    /**
     * 读指定的这几条。
     *
     * <p>去重并保序: 送达事件里的 messageIds 来自聊天平台, 重复投递会让同一个 id 出现两次,
     * 而"她读了同一条消息两遍"在认知上是不存在的。
     */
    static ReadPolicy ids(Collection<String> messageIds) {
        if (messageIds == null) {
            return new ByIds(List.of());
        }
        return new ByIds(messageIds.stream().filter(java.util.Objects::nonNull).distinct().toList());
    }

    /** 读最近 {@code limit} 条。她自己去翻聊天记录时用它。 */
    static ReadPolicy recent(int limit) {
        return new Recent(Math.max(1, Math.min(limit, 200)));
    }

    /**
     * @param messageIds 非空且已去重 —— 这个不变量由 {@link #ids} 保证, 构造函数再挡一次
     */
    record ByIds(List<String> messageIds) implements ReadPolicy {

        public ByIds {
            if (messageIds == null || messageIds.isEmpty()) {
                throw new IllegalArgumentException("ByIds 至少要有一条消息 —— 空读不该发生, 它只会白跑一趟设备");
            }
            messageIds = List.copyOf(messageIds);
        }

        @Override
        public int upperBound() {
            return messageIds.size();
        }

        @Override
        public boolean deliveryDriven() {
            return true;
        }
    }

    record Recent(int limit) implements ReadPolicy {

        public Recent {
            if (limit <= 0) {
                throw new IllegalArgumentException("Recent 的 limit 必须为正: " + limit);
            }
        }

        @Override
        public int upperBound() {
            return limit;
        }

        @Override
        public boolean deliveryDriven() {
            return false;
        }
    }
}
