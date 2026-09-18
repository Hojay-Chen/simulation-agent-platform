package com.luxera.companion.boundary.action;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §5.5 —— 一次能力调用的结果。
 *
 * <h2>四种结局, 而不是"成功/失败"两种</h2>
 * <table border="1">
 *   <tr><th>{@link Status}</th><th>含义</th><th>她该怎么理解</th></tr>
 *   <tr>
 *     <td>{@code SUCCEEDED}</td><td>做到了</td><td>"发出去了"</td>
 *   </tr>
 *   <tr>
 *     <td>{@code REJECTED}</td>
 *     <td>能力<b>拒绝</b>执行 —— 参数不对、没有权限、状态不允许</td>
 *     <td>"这样做不行" —— <b>这是一条有用的信息, 不是故障</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code UNAVAILABLE}</td>
 *     <td>能力<b>暂时</b>够不着 —— 没信号、手机没电、设备没配对</td>
 *     <td>"现在做不到, 也许待会儿行" —— 值得重试</td>
 *   </tr>
 *   <tr>
 *     <td>{@code FAILED}</td>
 *     <td>执行过程中<b>出错了</b> —— 依赖的服务挂了、数据不一致</td>
 *     <td>"出问题了" —— 需要被看见和修, 通常不该自动重试</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 {@code REJECTED} 与 {@code UNAVAILABLE} 必须分开</h2>
 * 这是 V11 那条 {@code "没消息"和"没读到"必须能分开} 的推广。假设她把这两个混成
 * "失败":
 * <pre>
 *   她点了发送 → 没信号 → FAILED
 *   她重试     → 没信号 → FAILED
 *   她重试     → ...
 *   结论: 她卡在一个循环里, 而"她为什么一直在发同一条消息"这个问题没有答案
 * </pre>
 * 分开之后: {@code UNAVAILABLE} 是"待会儿再试", {@code REJECTED} 是"换个做法",
 * {@code FAILED} 是"记下来等修"。三种反应完全不同, 而把它们压成一个就是让
 * 决策层没有可用的信息。
 *
 * <h2>为什么没有异常</h2>
 * 与 {@code PhoneCapability.readMessages} 同一理由: 手机没电不是异常, 是她生活的
 * 一部分。一个会抛异常的能力接口会逼着每个调用方写 try/catch, 而写出来的
 * catch 块几乎总是 {@code log.error(...)} 然后吞掉 —— 那等于把"她错过了什么"
 * 变成不可见的。
 */
public record ActionResult(
        Status status,
        String capabilityKey,
        Map<String, Object> data,
        String message,
        Instant completedAt) {

    public ActionResult {
        data = data == null ? Map.of() : Map.copyOf(data);
        // completedAt 不许为空 —— 这里曾经写的是 `completedAt == null ? Instant.now() : completedAt`,
        // 那是一个**看起来无害的便利**, 它做了两件坏事:
        //   ① 动作的完成时刻变成了墙钟时间, 而仿真时间可能与它差好几天
        //      （回放上周三的数据时, 一条"12:30 发的消息"会被记成"今天 04:12 发的"）;
        //   ② 它让"时刻从外面传进来"这条纪律在这里破了一个口, 而口子只会变大。
        // 这个便利的诱惑在于四个静态工厂都已经在传 at —— 于是空值分支永远不会被走到,
        // 直到某天有人直接 new 一个。与其留一个永远不触发却足以毁掉回放的分支,
        // 不如让它当场抛异常。
        Objects.requireNonNull(completedAt,
                "动作的完成时刻不能为空 —— 仿真时刻必须由调用方传入(见 ActionResult 的四个静态工厂), "
                        + "不许在这里读系统时钟: 那会让回放出来的时间线与真实仿真时间对不上");
        Objects.requireNonNull(status, "状态不能为空");
        Objects.requireNonNull(capabilityKey, "能力 key 不能为空 —— 没有它就无法回答'她刚才做了什么'");
    }

    public enum Status {
        SUCCEEDED, REJECTED, UNAVAILABLE, FAILED
    }

    // ─────────────────────────── 构造 ───────────────────────────

    public static ActionResult succeeded(String capabilityKey, Instant at, Map<String, Object> data) {
        return new ActionResult(Status.SUCCEEDED, capabilityKey, data, null, at);
    }

    public static ActionResult succeeded(String capabilityKey, Instant at) {
        return new ActionResult(Status.SUCCEEDED, capabilityKey, Map.of(), null, at);
    }

    /** 能力拒绝了: 参数不对、没权限、状态不允许。<b>带一句能给她看的原因</b>。 */
    public static ActionResult rejected(String capabilityKey, Instant at, String why) {
        return new ActionResult(Status.REJECTED, capabilityKey, Map.of(), why, at);
    }

    /** 暂时够不着 —— 值得重试。 */
    public static ActionResult unavailable(String capabilityKey, Instant at, String why) {
        return new ActionResult(Status.UNAVAILABLE, capabilityKey, Map.of(), why, at);
    }

    /** 出错了 —— 通常不该自动重试, 该被看见。 */
    public static ActionResult failed(String capabilityKey, Instant at, String why) {
        return new ActionResult(Status.FAILED, capabilityKey, Map.of(), why, at);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    public boolean ok() {
        return status == Status.SUCCEEDED;
    }

    /**
     * 失败了但值得重试。
     *
     * <p>决策层用这个方法决定"是换个做法还是待会儿再来"。
     */
    public boolean retryable() {
        return status == Status.UNAVAILABLE;
    }

    /**
     * 这次调用是否改变了她与世界的<b>约定</b> —— 即世界是否收到了她的意志。
     *
     * <p>{@code REJECTED} 也不算: 能力拒绝了就等于什么都没发生。
     * 这个方法存在的意义是让"她以为她发出去了"与"真的发出去了"能被区分 ——
     * 一个只报告 {@code ok()} 的调用点会让这两件事在她的记忆里长得一模一样。
     */
    public boolean tookEffect() {
        return status == Status.SUCCEEDED;
    }

    public Optional<String> reason() {
        return Optional.ofNullable(message);
    }

    /** 取一个返回值。 */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    /**
     * 这次结果是不是"她没做到"这件事本身值得她知道。
     *
     * <p>三个失败状态都返回 {@code true}, <b>除了</b>一种情况: {@code REJECTED}
     * 且原因已知。理由: "参数写错了"这类拒绝是决策层的 bug, 不该变成她的心理活动;
     * 而"没信号"和"服务挂了"是她真实经历的外部世界。
     *
     * <p>这个区分看起来细, 但它决定了事件日志里会不会充满"她意识到自己刚才参数
     * 传错了"这种荒谬的记录。
     */
    public boolean worthNoticing() {
        return status == Status.UNAVAILABLE || status == Status.FAILED;
    }

    /** 一行摘要, 给日志与诊断用。<b>不打 data</b> —— 返回值里可能有消息正文。 */
    public String describe() {
        return capabilityKey + " → " + status
                + (message == null ? "" : " (" + message + ")")
                + (data.isEmpty() ? "" : " {" + data.size() + " 个返回值}");
    }
}
