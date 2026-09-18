package com.luxera.companion.phone;

import com.luxera.companion.contracts.api.MessageView;

import java.util.List;

/**
 * V11 §7.2 —— <b>一次读取的结果, 连同"从哪条路读到的"</b>。
 *
 * <p>传输方式必须跟着结果一起回来, 而不是记在日志里。理由是运维上的: 平台里
 * 只有 1 台设备是 ACTIVE 的, 另外 51 个 agent 没有可用设备 —— 也就是说绝大多数读取
 * 走的是回退路径。若"用了哪条"只写在日志里, 那么"设备通道到底有没有在工作"这个问题
 * 就只能靠翻日志回答, 而它恰恰是 Phase 2 唯一需要盯着看的那个数字。
 *
 * @param messages  这次读到的消息, <b>按时间升序</b>(与 {@code ChatWorldPort.messages} 同序)
 * @param transport 实际用了哪条路
 * @param note      人话说明。取得 0 条时这里写清"是没消息"还是"路没通" —— 两者在
 *                  一个空列表上长得一模一样, 而它们要触发完全不同的处置
 */
public record MessageBatch(List<MessageView> messages, Transport transport, String note) {

    public enum Transport {
        /** 走设备通道({@code chat.readMessages})—— 只有设备在线时才会选它。 */
        SIMULATOR("simulator"),
        /** 走 {@code ChatWorldPort} —— 平台内部直读, 今天绝大多数 agent 走这条。 */
        CHAT_WORLD_PORT("chat-world-port"),
        /** 哪条都没走成。 */
        NONE("none");

        private final String wire;

        Transport(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public MessageBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int size() {
        return messages.size();
    }

    /** 什么都没读到, 且原因是"路不通"。 */
    public static MessageBatch unreachable(String note) {
        return new MessageBatch(List.of(), Transport.NONE, note);
    }
}
