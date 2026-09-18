package com.luxera.companion.human.life.plan;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §3.5.6 —— <b>重排器: 把"现在该怎么办"变成一个全新的计划版本</b>。
 *
 * <h2>这个接口是用户亲自要求的结果</h2>
 * 用户否定了三种做法, 并给出了他要的语义:
 * <blockquote>
 * 打断当前正在做的计划 event, <b>不是</b>简单把当前在做的计划 event 更新剩余时间
 * 然后立马执行一个计划 event, 再把被中断的计划 event 继续执行,
 * 他<b>是真的改变了计划表</b>, 让 agent <b>重新思考重排计划表</b>……
 * </blockquote>
 *
 * <h2>三种被否定的做法, 以及它们为什么错</h2>
 * <table border="1">
 *   <tr><th>做法</th><th>为什么错</th></tr>
 *   <tr>
 *     <td>❌ <b>暂停/恢复</b>: {@code pause()} 记下 {@code remainingDuration},
 *          处理完再 {@code resume()}</td>
 *     <td>这是<b>任务调度器</b>的思路。人的计划不是被暂停的进程 ——
 *         她可能<b>根本不想继续了</b>。而且 {@code remainingDuration} 一旦成为持久状态,
 *         就与计划表分叉成了<b>两个真相源</b>, 而两个真相源迟早会不一致</td>
 *   </tr>
 *   <tr>
 *     <td>❌ <b>原地改</b>: 把 {@code PlanItem} 的 {@code end_at} 往后推</td>
 *     <td>计划表从此看不出"发生过打断", 也无法回答"她今天改了几次主意"</td>
 *   </tr>
 *   <tr>
 *     <td>❌ <b>插队执行</b>: 把穿衣插到队首, 写完作业再继续</td>
 *     <td>这正是用户否定的那条 —— 它<b>假设"写作业必然会继续"</b>,
 *         而这个假设是错的</td>
 *   </tr>
 * </table>
 *
 * <h2>正确的做法: 产出<b>全新的 {@link PlanRevision}</b></h2>
 * 它可以做五件事的<b>任意组合</b>（见 {@link PlanMutation}）:
 * {@code INSERT}（插入穿衣）、{@code REMOVE}（删掉写作业）、
 * {@code MOVE}（把写作业的启动时间设为穿衣结束的时间）、
 * {@code RESIZE}（改时长）、{@code REPLACE}（写作业 → 运动）。
 * 外加 {@code KEEP_ACTIVE} 表达"被打扰了但她选择不改"。
 *
 * <h2>它与 {@link PlanValidator} 的分工</h2>
 * <pre>
 *   PlanReplanner  → 提出一个候选 Revision（"我觉得应该这样排"）
 *          ↓
 *   PlanValidator  → 校验（时间重叠? 依赖倒置? 约束满足?）
 *          ↓ 通过                    ↓ 不通过
 *   成为新 Revision              记 system.plan-validation-failed.v1 并丢弃
 * </pre>
 *
 * <p><b>重排器不许自己改 {@link PlanBoard}。</b>它只产出版本, 由外部
 * （{@code HumanActor}）在校验通过后交给计划表。这样"谁有权改写她的未来"
 * 就只有一处, 而审计能看清每一次改写的来路。
 *
 * <h2>两类实现</h2>
 * <table border="1">
 *   <tr><th>实现</th><th>怎么决定</th><th>什么时候用</th></tr>
 *   <tr>
 *     <td>{@link DefaultPlanReplanner}</td>
 *     <td><b>确定性规则</b> —— 由代码逻辑推出改动</td>
 *     <td>常见情形（刺激打断、到点推进、不可行项）;
 *         <b>也是 LLM 不可用时的降级路径</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code LlmPlanReplanner}</td>
 *     <td><b>LLM 提出候选</b>, 再由 {@link PlanValidator} 校验</td>
 *     <td>需要开放性判断的情形（"今天该不该改主意"）</td>
 *   </tr>
 * </table>
 *
 * <p><b>两者都必须走同一个校验关口。</b>一条只在 LLM 路径上做校验、而规则路径
 * 直接改计划表的设计, 会让"计划表里的时间重叠"变成一个只在规则路径出现的 bug ——
 * 而规则路径恰恰是更容易被信任、因而更少被检查的那一条。
 *
 * <h2>实现必须是<b>纯</b>的</h2>
 * 同样的 {@link ReplanningContext} 必须产出同样的 {@link PlanRevision}
 * （除了新生成的 {@link PlanItemId}, 那是刻意的例外）。理由见
 * {@link PlanningContext}: 行为分析要能回放"她 12:15 为什么这样排"。
 * 一个会读系统时间或随机数的重排器, 会让这个回放永远无法验证。
 */
public interface PlanReplanner {

    /**
     * 重排。
     *
     * @param context <b>全部</b>可用事实。见 {@link ReplanningContext} 关于
     *                "它刻意不含什么"的说明 —— 特别注意里面没有剩余时长之类
     *                会暗示"被中断的事必然继续"的字段
     * @return 一份<b>改动提案</b>。不要在这里改 {@link PlanBoard}
     */
    ReplanProposal replan(ReplanningContext context);

    /**
     * 重排器的名字 —— 会进 {@code plan.revision-created.v1} 的载荷。
     *
     * <p>为什么需要它: 当行为分析看到"她这一天重排了 40 次"时, 第一个要问的
     * 就是"是规则在抖, 还是 LLM 在反复改主意"。没有这个名字, 那个问题答不出来。
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 这个重排器在什么条件下愿意接手。
     *
     * <p>让它存在的场景: 装配了多个重排器时（规则 + LLM）, 需要一个分派依据。
     * 默认全部接手 —— 单一重排器的常见情形下不需要覆盖它。
     *
     * <p>注意判据是 {@link ReplanningContext#trigger()} 而不是刺激的具体类型 ——
     * 后者会让分派逻辑退化成"按事件类型硬编码"（设计文档 §3.5.6 明令禁止）。
     */
    default boolean accepts(ReplanningContext context) {
        return true;
    }

    /**
     * 在一次重排里最多允许改动几项。
     *
     * <p>存在的理由是一个真实的风险: 一个 LLM 重排器可能一次把整天的计划全推翻 ——
     * 而那在行为上不是"重排", 是"换了一个人"。真人被打断时会做<b>局部</b>调整:
     * 加一件事、把一件挪后, 而不是重写一整天。
     *
     * <p>默认 {@value #DEFAULT_MAX_MUTATIONS} 项。超出的重排会被
     * {@link PlanValidator} 之外的一道检查拦下（见 {@code HumanActor}）——
     * 因为这属于"重排的规模是否可信", 与"时间排不排得下"是两类不同的问题。
     */
    int DEFAULT_MAX_MUTATIONS = 8;

    default int maxMutationsPerReplan() {
        return DEFAULT_MAX_MUTATIONS;
    }

    // ─────────────────────────── 共用的小工具 ───────────────────────────

    /**
     * 在候选改动里找出"她决定继续做当前这件事"的那一条。
     *
     * <p>给需要判断"这次重排是不是真的改了计划"的调用方用 ——
     * 只有 {@link PlanMutation.KeepActive} 的重排不产生实质变化,
     * 但它<b>仍然要产生新版本</b>, 因为"她被打扰过"这件事必须留下痕迹。
     */
    static Optional<PlanMutation.KeepActive> keepActiveIn(List<PlanMutation> mutations) {
        return mutations.stream()
                .filter(m -> m instanceof PlanMutation.KeepActive)
                .map(m -> (PlanMutation.KeepActive) m)
                .findFirst();
    }

    /** 这次重排是不是"想过了但没改"。 */
    static boolean isNoop(List<PlanMutation> mutations) {
        return mutations.stream().allMatch(PlanMutation::isNoop);
    }
}
