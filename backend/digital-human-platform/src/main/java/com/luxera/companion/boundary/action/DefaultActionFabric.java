package com.luxera.companion.boundary.action;

import com.luxera.companion.registry.CapabilityRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §5.5 —— {@link ActionFabric} 的默认实现。
 *
 * <h2>执行一条命令的五步</h2>
 * <pre>
 *   ① 解析     capabilityKey → Capability (找不到 → REJECTED)
 *   ② 授权     capability.availableTo(actorId) (不允许 → REJECTED)
 *   ③ 可用性   capability.currentlyAvailable(ctx) (不可用 → UNAVAILABLE)
 *   ④ 幂等     同一个 idempotencyKey 已经成功过 → 直接返回上次的结果
 *   ⑤ 执行     真的调用, 并记进历史
 * </pre>
 *
 * <h3>② 与 ③ 为什么必须分开</h3>
 * <table border="1">
 *   <tr><th></th><th>② 授权</th><th>③ 可用性</th></tr>
 *   <tr><td>问题</td><td>"她<b>配不配</b>做这件事"</td><td>"这件事<b>现在做得成</b>吗"</td></tr>
 *   <tr><td>会变吗</td><td>基本不变(除非权限被改)</td><td>一直变(设备上线下线)</td></tr>
 *   <tr><td>结果</td><td>{@code REJECTED} —— 换个做法</td><td>{@code UNAVAILABLE} —— 待会儿再试</td></tr>
 *   <tr><td>例子</td><td>这个 agent 没有发消息的权限</td><td>手机没电了</td></tr>
 * </table>
 *
 * <p>合成一步的后果是: "手机没电"会被报成"你没权限", 于是她再也不会重试 ——
 * 而正确的反应恰恰是待会儿再试。<b>把两种失败压成一种, 就是让决策层失去信息。</b>
 *
 * <h2>幂等的实现边界</h2>
 * 本类的幂等是<b>进程内</b>的: 同一个 {@code idempotencyKey} 在同一个
 * {@link DefaultActionFabric} 实例上重复执行会被短路。跨进程重启的幂等需要
 * 持久化(见 {@code action_command.idempotency_key} 唯一索引), <b>本类不负责</b>。
 *
 * <p>说清这个边界很重要: 一个号称"支持幂等"但实际只在内存里记的实现在重启后
 * 会静默失效, 而依赖它的重试逻辑会真的发出第二条消息。所以本类在
 * {@link #execute} 里对需要跨进程幂等的命令只做<b>尽力而为</b>, 并且注释写明 ——
 * 而不是让人以为它是完整的。
 */
@Slf4j
public class DefaultActionFabric implements ActionFabric {

    /** 执行历史保留条数。与 {@code EventFabric} 的历史同量级、同理由。 */
    private static final int HISTORY_LIMIT = 256;

    private final CapabilityRegistry registry;

    /** 幂等缓存: idempotencyKey → 上次成功的结果。 */
    private final Map<String, ActionResult> idempotencyCache = new LinkedHashMap<>();

    /** 幂等缓存上限 —— 免得一个长跑的 agent 把内存吃光。 */
    private static final int IDEMPOTENCY_LIMIT = 512;

    /** 执行历史, 新的在前。 */
    private final LinkedList<ActionRecord> history = new LinkedList<>();

    public DefaultActionFabric(CapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "能力注册表不能为空");
    }

    @Override
    public ActionResult execute(ActionCommand command) {
        Objects.requireNonNull(command, "要执行的命令不能为空");
        Instant now = command.issuedAt();

        // ① 解析
        Optional<Capability> found = registry.find(command.capabilityKey());
        if (found.isEmpty()) {
            // 不抛异常 —— 见 ActionFabric.execute 的说明: "她现在做不了这件事"是处境, 不是故障
            ActionResult result = ActionResult.rejected(command.capabilityKey(), now,
                    "没有名为 " + command.capabilityKey() + " 的能力 —— 那个应用可能没装");
            record(command, result);
            return result;
        }
        Capability capability = found.get();

        // ② 授权
        if (!capability.availableTo(command.actorId())) {
            ActionResult result = ActionResult.rejected(command.capabilityKey(), now,
                    "这个能力不对 " + command.actorId() + " 开放");
            record(command, result);
            return result;
        }

        // ④ 幂等短路 —— 放在可用性检查<b>之前</b>?
        //    不。放在之后: 一次已经成功过的发送, 即使现在设备离线, 也应该返回
        //    "已经发过了"而不是"设备不可用"。重试的正确语义是"确认它成功了",
        //    不是"再试一次能不能成功"。
        if (command.idempotent()) {
            ActionResult cached = idempotencyCache.get(command.idempotencyKey());
            if (cached != null && cached.ok()) {
                log.debug("[ActionFabric] {} 已执行过(幂等键 {}), 直接返回上次结果",
                        command.capabilityKey(), command.idempotencyKey());
                record(command, cached);
                return cached;
            }
        }

        // ③ 可用性
        Capability.CapabilityContext context = Capability.CapabilityContext.of(
                command.actorId(), now);
        if (!capability.currentlyAvailable(context)) {
            ActionResult result = ActionResult.unavailable(command.capabilityKey(), now,
                    "这个能力现在用不了 —— 待会儿再试试");
            record(command, result);
            return result;
        }

        // ⑤ 执行
        ActionResult result;
        try {
            result = capability.invoke(command, context);
            if (result == null) {
                // 一个返回 null 的能力实现是最容易犯也最危险的错误:
                // 它会让调用方在很远的地方炸掉, 且只在某条路径上出现
                log.error("[ActionFabric] 能力 {} 的 invoke 返回了 null —— 按 FAILED 处理",
                        command.capabilityKey());
                result = ActionResult.failed(command.capabilityKey(), now,
                        "能力实现返回了 null");
            }
        } catch (RuntimeException e) {
            // 第三方能力实现可能抛任何东西。吞掉它并转成 FAILED, 而不是让她的事务回滚 ——
            // 一个坏插件不该让她的整个世界停摆
            log.error("[ActionFabric] 能力 {} 执行时抛异常", command.capabilityKey(), e);
            result = ActionResult.failed(command.capabilityKey(), now,
                    "执行出错: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        if (command.idempotent() && result.ok()) {
            rememberIdempotent(command.idempotencyKey(), result);
        }

        record(command, result);
        return result;
    }

    private void rememberIdempotent(String key, ActionResult result) {
        if (idempotencyCache.size() >= IDEMPOTENCY_LIMIT) {
            // 简单地丢掉最早的一条。用 LRU 会更精确, 但这里的访问模式是
            // "同一批重试集中在几秒内", 先进先出已经足够 —— 而 LRU 需要额外的
            // 访问序维护, 在一个每次调用都要走的热路径上不值得
            var it = idempotencyCache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        idempotencyCache.put(key, result);
    }

    private void record(ActionCommand command, ActionResult result) {
        history.addFirst(new ActionRecord(command, result));
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
        // DEBUG 而不是 INFO: 一个活跃的 agent 每秒可能做几十次能力调用,
        // 打到 INFO 会把真正重要的日志淹掉。失败才升到 INFO
        if (result.ok()) {
            log.debug("[ActionFabric] {}", command.describe());
        } else {
            log.info("[ActionFabric] {} ⇒ {}", command.describe(), result.describe());
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Override
    public List<Capability> availableCapabilities(Instant now) {
        Objects.requireNonNull(now, "查询可用能力必须带仿真时刻 —— 可用性是随时间变的");
        List<Capability> out = new ArrayList<>();
        for (Capability c : registry.all()) {
            // actorId 在这里未知: 这是"世界上有哪些能力"的视图, 不是"她能做什么"。
            // 授权过滤发生在决策层拿到清单之后 —— 因为同一份清单会被多个 actor 用
            Capability.CapabilityContext ctx = Capability.CapabilityContext.of("_probe", now);
            if (c.currentlyAvailable(ctx)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<Capability> find(String capabilityKey) {
        return registry.find(capabilityKey);
    }

    @Override
    public CapabilityRegistry registry() {
        return registry;
    }

    @Override
    public List<ActionRecord> recentActions(int limit) {
        return history.stream().limit(Math.max(0, limit)).toList();
    }

    /** 幂等缓存现在有多大。诊断与测试用。 */
    public int idempotencyCacheSize() {
        return idempotencyCache.size();
    }

    /** 清空幂等缓存与历史 —— 只给测试用。 */
    public void clear() {
        idempotencyCache.clear();
        history.clear();
    }

    @Override
    public String describe() {
        return "ActionFabric[" + registry.size() + " 项能力, 近 " + history.size() + " 次调用]";
    }
}
