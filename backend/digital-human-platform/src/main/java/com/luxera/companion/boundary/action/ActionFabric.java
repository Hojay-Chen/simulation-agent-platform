package com.luxera.companion.boundary.action;

import com.luxera.companion.registry.CapabilityRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §5.5 —— <b>Human → World 的唯一通道</b>。
 *
 * <h2>它挡在中间, 是为了让"她做了什么"可查</h2>
 * 技术上, {@code Mind} 可以直接持有 {@code Phone} 然后调它的方法。那样代码更短,
 * 而本设计禁止它, 理由有三条, 每一条都独立成立:
 *
 * <ol>
 *   <li><b>授权。</b>她能不能做这件事, 与"这个能力存不存在"是两个问题。
 *       直接调方法时, 授权只能写在每个方法里 —— 那就是 N 份各自漂移的逻辑。</li>
 *   <li><b>审计。</b>"她 12:03 看了一眼手机"必须是一条记录。直接调方法时,
 *       想让这件事被记下来, 只能靠每个实现自觉。</li>
 *   <li><b>第三方。</b>直接调方法意味着 {@code Mind} 需要 {@code import}
 *       那个类。而第三方能力<b>在编译宿主时还不存在</b> —— 这条是最硬的。</li>
 * </ol>
 *
 * <h2>它<b>不</b>做什么</h2>
 * <ul>
 *   <li><b>不决定做什么。</b>它不挑能力, 只执行被指定的那个。选哪个能力是
 *       {@code Mind} 的决策层的事。</li>
 *   <li><b>不重试。</b>重试策略属于调用方 —— 因为"失败了该怎么办"取决于那件事
 *       对她意味着什么, 而 fabric 不知道。</li>
 *   <li><b>不合成结果。</b>它原样返回 {@link ActionResult}。</li>
 * </ul>
 *
 * <h2>与 {@code EventFabric} 的对称性</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code EventFabric}</th><th>{@code ActionFabric}</th></tr>
 *   <tr><td>方向</td><td>World → Human</td><td>Human → World</td></tr>
 *   <tr><td>载荷</td><td>{@code WorldEvent}</td><td>{@code ActionCommand}</td></tr>
 *   <tr><td>结果</td><td>无(投递即完成)</td><td>{@code ActionResult}</td></tr>
 *   <tr><td>为什么不对称</td>
 *     <td>世界发生的事不需要她"同意"</td>
 *     <td>她要做的事需要世界"答应"</td></tr>
 * </table>
 *
 * <p>这个不对称是本质的, 不是疏忽: 感知是被动的, 行动是协商的。
 */
public interface ActionFabric {

    /**
     * 执行一条命令 —— <b>她改变世界的唯一入口</b>。
     *
     * <h3>找不到能力怎么办</h3>
     * 返回 {@link ActionResult#rejected}, <b>不抛异常</b>。理由: 决策层可能
     * 提议一个当前不可用的能力(那个应用没装、那台设备没连), 而"她现在做不了这件事"
     * 是她的真实处境, 不是系统故障。抛异常会让那个处境变成一个 500。
     *
     * <h3>返回值永远不为 null</h3>
     * 一个返回 null 的 fabric 会逼着每个调用方判空, 而漏判的地方会变成一个
     * 在 {@code Mind} 深处才炸掉的 NPE —— 离根因很远, 且只在能力缺失时出现。
     */
    ActionResult execute(ActionCommand command);

    /**
     * 她现在能用哪些能力。
     *
     * <p>这是给决策层与 LLM 的<b>工具清单</b>。刻意返回值而不是描述符:
     * 一个能力可能"注册了但当前不可用"(手机没连上), 而把不可用的也列给 LLM
     * 会让它反复尝试一个做不到的动作。
     *
     * <p>但"不可用"的能力<b>仍然在注册表里</b>(见 {@link CapabilityRegistry}) ——
     * 这两件事的区别很重要: 注册表回答"世界上存在什么", 本方法回答"她此刻能做什么"。
     */
    List<Capability> availableCapabilities(Instant now);

    /**
     * 查一个能力, 不管它当前可不可用。
     *
     * <p>给"她想知道自己会不会做某件事"用 —— 一个真人知道自己有手机,
     * 即使此刻手机没电。
     */
    Optional<Capability> find(String capabilityKey);

    /**
     * 这个能力注册表 —— 供装配与诊断读取。
     *
     * <p>暴露出来是刻意的: {@code CapabilityRegistry} 是<b>世界侧</b>的状态
     * (哪些设备在、装了哪些应用), 它不属于 fabric。fabric 只是一个执行入口,
     * 不该拥有注册表。
     */
    CapabilityRegistry registry();

    /**
     * 最近执行过的命令, 按时间倒序。
     *
     * <p>给行为分析与诊断用。这是"她做了什么"的答案 —— 而它与
     * {@code EventFabric.recentEvents} 是<b>两个不同的问题</b>:
     * 前者是"她做了什么", 后者是"她经历了什么"。两个都要有才能回答
     * "她为什么这么做"。
     */
    List<ActionRecord> recentActions(int limit);

    /**
     * 一次执行记录。
     *
     * @param command 发起的命令
     * @param result  结果。<b>不可能是 null</b> —— 见 {@link #execute}
     */
    record ActionRecord(ActionCommand command, ActionResult result) {

        public ActionRecord {
            Objects.requireNonNull(command, "执行记录必须有命令");
            Objects.requireNonNull(result, "执行记录必须有结果 —— 没有结果是'她不知道发生了什么'");
        }

        public boolean succeeded() {
            return result.ok();
        }

        public String describe() {
            return command.describe() + " ⇒ " + result.describe();
        }
    }

    /** 一行摘要, 给诊断面板用。 */
    default String describe() {
        return "ActionFabric[" + registry().size() + " 个能力]";
    }
}
