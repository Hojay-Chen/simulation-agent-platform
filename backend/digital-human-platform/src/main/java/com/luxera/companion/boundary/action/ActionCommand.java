package com.luxera.companion.boundary.action;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V2.2 §5.5 —— <b>她要做一件事</b>。Human → World 方向上唯一的载荷。
 *
 * <h2>为什么是"命令"而不是"动作"</h2>
 * 这两个词在中文里几乎同义, 但在这个系统里必须分开:
 * <table border="1">
 *   <tr><th></th><th>动作 Action</th><th>命令 Command</th></tr>
 *   <tr>
 *     <td>是什么</td><td>一个<b>意图</b>: "我想让她看到这条消息"</td>
 *     <td>一次<b>请求</b>: "调用 chat.sendMessage, 参数是这些"</td>
 *   </tr>
 *   <tr>
 *     <td>谁说的</td><td>Mind 决定"要做什么"</td>
 *     <td>决策层把它翻译成具体能力调用</td>
 *   </tr>
 *   <tr>
 *     <td>例子</td><td>"回她一句"</td>
 *     <td>{@code chat.send-message{conversationId, text}}</td>
 *   </tr>
 * </table>
 *
 * <p>刻意<b>没有</b> {@code ActionType} 枚举 —— 理由同 {@link com.luxera.companion.boundary.event.EventTypeId}:
 * 加一个能力不该需要改宿主的编译单元。
 *
 * <h2>参数为什么是 JSON 形状的 Map, 不是强类型</h2>
 * 因为参数的类型<b>由能力自己定义</b>, 而能力可以是第三方实现的。宿主无法为一个
 * 它不认识的 {@code laboratory.centrifuge} 能力定义一个 Java 参数类 ——
 * 那正是"第三方自己实现接口"这句话的技术含义。
 *
 * <p>那类型安全怎么办? 由三层保证, 而它们都不在宿主的编译期:
 * <ol>
 *   <li>{@link CapabilityDescriptor#parameterSchema()} —— 能力自描述的参数模式,
 *       LLM 与前端都按它生成表单;</li>
 *   <li>{@link Capability#invoke} 的实现在拿到参数时校验 —— <b>它才知道什么是对的</b>;</li>
 *   <li>校验失败返回 {@link ActionResult#rejected}, 而不是抛异常。她"做不到"是
 *       生活的一部分, 不是系统故障。</li>
 * </ol>
 *
 * <h2>幂等键: 为什么它必须在命令上, 而不是在执行侧生成</h2>
 * 因为"重试"发生在<b>命令的发送方</b>。她决定回一条消息之后, 执行可能失败
 * (没信号), 而重试必须保证"她不会连发两条一样的"。若幂等键在执行侧生成,
 * 每次重试都是一个新键, 幂等就完全失效了 —— 而那正是它存在的唯一理由。
 */
public record ActionCommand(
        String commandId,
        String actorId,
        String capabilityKey,
        Map<String, Object> arguments,
        String idempotencyKey,
        Instant issuedAt) {

    public ActionCommand {
        commandId = commandId == null || commandId.isBlank()
                ? "cmd-" + UUID.randomUUID() : commandId;
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException(
                    "命令必须有发起人 —— 没有发起人的命令无法做能力授权, "
                            + "而'谁在动手'是这个系统里几乎每个决策都要问的问题");
        }
        if (capabilityKey == null || capabilityKey.isBlank()) {
            throw new IllegalArgumentException(
                    "命令必须指明要调用哪个能力。用 capabilityKey(namespace.name)而不是类名 —— "
                            + "因为能力可以由第三方注册, 宿主不该认识它的类");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (issuedAt == null) {
            throw new IllegalArgumentException("命令必须带发起时刻 —— 仿真时钟下不许读墙上时钟");
        }
        // 幂等键可以为空 —— 表示"这条命令重复执行也无害"(比如"看一眼手机")。
        // 但空与"给了一个键"必须能被区分开, 所以不在这里补默认值。
    }

    /** 新命令, 自动生成 commandId, 不带幂等键。 */
    public static ActionCommand of(String actorId, String capabilityKey, Instant issuedAt,
                                   Map<String, Object> arguments) {
        return new ActionCommand(null, actorId, capabilityKey, arguments, null, issuedAt);
    }

    /**
     * 新命令, 带幂等键 —— <b>任何会产生副作用的命令都该用这个</b>。
     *
     * <p>发消息、转账、下单这类操作重试一次就是一次真实的重复副作用。
     * {@code "看一眼手机"} 这类只读操作不需要。
     */
    public static ActionCommand idempotent(String actorId, String capabilityKey, Instant issuedAt,
                                           Map<String, Object> arguments, String idempotencyKey) {
        return new ActionCommand(null, actorId, capabilityKey, arguments, idempotencyKey, issuedAt);
    }

    // ─────────────────────────── 参数读取 ───────────────────────────

    /** 取一个参数。没有就抛 —— 用于<b>必需</b>参数。 */
    public Object require(String key) {
        Object v = arguments.get(key);
        if (v == null) {
            throw new IllegalArgumentException(
                    "命令 " + capabilityKey + " 缺少必需参数 " + key + ", 已有: " + arguments.keySet());
        }
        return v;
    }

    public Optional<String> str(String key) {
        Object v = arguments.get(key);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    public Optional<Integer> integer(String key) {
        Object v = arguments.get(key);
        if (v instanceof Number n) {
            return Optional.of(n.intValue());
        }
        return v == null ? Optional.empty() : parseOrEmpty(String.valueOf(v));
    }

    private static Optional<Integer> parseOrEmpty(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<Boolean> bool(String key) {
        Object v = arguments.get(key);
        if (v instanceof Boolean b) {
            return Optional.of(b);
        }
        return v == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(String.valueOf(v)));
    }

    /** 追加一个参数。返回新命令 —— 本类型不可变, 与事件同一纪律。 */
    public ActionCommand withArgument(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(arguments);
        merged.put(key, value);
        return new ActionCommand(commandId, actorId, capabilityKey, merged, idempotencyKey, issuedAt);
    }

    /** 是否要求"重复执行无害"。{@code false} 的命令执行侧应当拒绝重试。 */
    public boolean idempotent() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    /** 一行摘要。<b>不打参数值</b> —— 参数里可能有消息正文。 */
    public String describe() {
        return "ActionCommand[" + commandId + "] " + actorId + " → " + capabilityKey
                + " (" + arguments.size() + " 个参数)"
                + (idempotent() ? " idem=" + idempotencyKey : "");
    }
}
