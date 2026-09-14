package com.luxera.companion.digitalhuman.event;

/**
 * V10 §9.2 AgentEventHandler: 事件处理链节点(Chain of Responsibility)。
 *
 * 每个节点决定自己是否处理该事件; 链按注册顺序执行:
 * Validation → Deduplication → Router → (Perception / Life / Application ...)
 *
 * 节点职责单一: supports() 判断是否处理, handle() 处理并返回结果。
 */
public interface AgentEventHandler {

    /** 本节点是否处理该事件(按事件类型/阶段标记判断) */
    boolean supports(ExternalEvent event);

    /**
     * 处理事件。
     * @return CONTINUE 继续沿链传递; TERMINATE 终止链(事件已被消费/短路)。
     */
    HandlingResult handle(ExternalEvent event);

    /** 处理结果: 是否终止链 + 说明 */
    record HandlingResult(boolean terminate, String note) {

        public static HandlingResult continueChain(String note) {
            return new HandlingResult(false, note);
        }

        public static HandlingResult terminate(String note) {
            return new HandlingResult(true, note);
        }
    }
}
