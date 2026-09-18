package com.luxera.companion.human.life.plan;

import java.util.List;

/**
 * V2.2 §3.5.6 —— <b>一条"遇到这种情况, 这样调整计划"的规则</b>。
 *
 * <h2>它存在的理由: 既要确定性, 又不许按类型硬编码</h2>
 * 设计文档 §3.5.6 给了一条明确的禁令:
 * <pre>{@code
 * // ❌ 禁止：根据事件类型硬编码反应
 * if (event.typeId().equals("environment.temperature-changed.v1")) {
 *     insertWearClothesPlan();
 * }
 *
 * // ✅ 正确：重排器问"现在该怎么办"，LLM 只负责提出候选
 * PlanRevision replan(ReplanningContext context);
 * }</pre>
 *
 * <p>但这句话留下了一个真实的张力: <b>如果连 {@code DefaultPlanReplanner} 都不许知道
 * "冷了要穿衣服", 那确定性路径岂不是什么都做不了?</b>
 *
 * <h2>禁令禁的到底是什么</h2>
 * 禁的<b>不是"知道冷了要穿衣服"</b> —— 那是领域知识, 必须有人知道。
 * 禁的是<b>把这份知识写在唯一的重排器里, 并且用事件类型作为分派依据</b>。区别在于:
 *
 * <table border="1">
 *   <tr><th></th><th>被禁的写法</th><th>本接口的写法</th></tr>
 *   <tr>
 *     <td>知识住在哪</td>
 *     <td>重排器的 {@code if} 里</td>
 *     <td>规则对象自己身上</td>
 *   </tr>
 *   <tr>
 *     <td>分派依据</td>
 *     <td><b>事件类型字符串</b></td>
 *     <td><b>处境</b>（她能做什么、现在什么状态、有什么约束）</td>
 *   </tr>
 *   <tr>
 *     <td>加一种反应</td>
 *     <td>改重排器源码</td>
 *     <td>注册一条新规则 —— <b>第三方也能加</b></td>
 *   </tr>
 *   <tr>
 *     <td>两条规则冲突</td>
 *     <td>取决于 {@code if} 的书写顺序, 散落在几百行里</td>
 *     <td>由 {@link DefaultPlanReplanner} 的优先级与拒绝条件显式决定</td>
 *   </tr>
 * </table>
 *
 * <p>关键差别在第 2 行。看处境而不是看类型, 意味着:
 * <b>一个"房间里变冷了"的事件和一个"她走进了冷库"的事件会触发同一条规则</b> ——
 * 因为处境相同（她很冷, 而没有穿够衣服）。而按类型分派的写法需要为这两种情形
 * 各写一个 {@code if}, 且第三个来源（"她洗了个冷水澡"）出现时又会漏掉。
 *
 * <h2>规则必须能说"这不关我的事"</h2>
 * 返回空列表就是那个意思。<b>不要返回一条"什么都不改"的改动来占位</b> ——
 * 那会让"没有规则认领这个处境"和"有规则认领了但它建议不改"变成同一件事,
 * 而这两种情形需要不同的日志和不同的告警。
 *
 * <h2>规则必须说清"我为什么建议这个"</h2>
 * 每条 {@link PlanMutation} 都带 {@code reason}, 而它最终会成为
 * {@code plan.revision-created.v1} 的载荷、进入她的 LLM context 与行为分析报告。
 * 所以理由要写成<b>第一人称、面向人</b>的句子:
 * "有点冷, 先加件衣服" 而不是 {@code "RULE_COLD_TRIGGERED"}。
 */
public interface PlanAdjustmentRule {

    /** 规则名 —— 进日志与重排版本的理由, 用于回答"这次重排是哪条规则干的"。 */
    String name();

    /**
     * 看一眼处境, 提出改动。
     *
     * @return 要做的改动。<b>空列表 = 我不认领这个处境</b>（见接口注释）
     */
    List<PlanMutation> propose(ReplanningContext context);

    /**
     * 优先级。数值小的先被问。
     *
     * <p>为什么需要: 有些规则是"紧急制动"（身体撑不住了必须立刻休息）,
     * 它们必须在"优化安排"类规则之前被考虑。默认 {@value #DEFAULT_PRIORITY}。
     */
    int DEFAULT_PRIORITY = 100;

    default int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * 这条规则在什么情况下<b>不允许</b>生效。
     *
     * <p>存在的场景很具体: 一条"觉得冷就加衣服"的规则, 在<b>她已经躺在被窝里</b>时
     * 不该再建议加衣服 —— 那时正确的反应是别的（调高空调、或者干脆忍着）。
     * 用 {@link #propose} 里的一句 {@code if} 也能实现, 但把它显式成一个方法
     * 会让"这条规则被什么挡住了"变得可查询 —— 而那正是调试重排时的第一个问题。
     */
    default boolean vetoedBy(ReplanningContext context) {
        return false;
    }

    /**
     * 这条规则最多能改动几项。
     *
     * <p>大多数规则只改一两项（"加一件事"、"把一件挪后"）。给上限是为了让一条
     * 写错的规则（比如循环里把整天都重排了）在造成大规模改动之前被截住。
     */
    default int maxMutations() {
        return 4;
    }

    /** 一行说明这条规则管什么 —— 诊断面板用。 */
    default String describe() {
        return name();
    }
}
