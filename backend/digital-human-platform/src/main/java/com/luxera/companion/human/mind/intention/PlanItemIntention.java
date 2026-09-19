package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanningContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §8.5.13 —— <b>规则桥: 把一件已经排在计划表上的事, 当成一条候选念头</b>。
 *
 * <h2>它存在的理由: 候选不能一直空着</h2>
 * {@code MindDecisionPlanner} 的类注释写明它"<b>不生成候选</b>, 候选从
 * {@code ReasoningContext.candidates()} 来"。而在 §8.5.13 之前, 那个参数<b>没有生产者</b> ——
 * {@code HumanActor.dispatchDecisions} 递进去的是 {@code List.of()}, 于是:
 *
 * <pre>
 *   候选恒空 → result.hasCandidates() 恒假 → mind.decide 从不被调用
 *            → commandsSent 恒为 0 → <b>她只感知、不行动</b>
 * </pre>
 *
 * <p>设计文档给的第一个生产者就是本类, 而且刻意选了最不需要外部依赖的那一个:
 * <b>她已经排在日程上的那一项, 本身就是最自然的一条候选</b>。它不用 LLM
 * (§8.5.0 禁止在心跳里调模型 —— 一次外呼会把整条心跳拖住, 那是<b>所有人</b>的心跳),
 * 可测、确定性, 于是"打断 = 真的去改计划表"这条链当场就能跑起来。
 * 将来的 LLM 提议器是它的<b>替代实现</b>, 不是它的前置条件。
 *
 * <h2>它是 {@code IntentionPlanIntent} 的镜像, 而两边的方向不能混</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code IntentionPlanIntent}</th><th>本类</th></tr>
 *   <tr><td>包在谁身上</td><td>一个 {@link Intention}</td><td>一个 {@link PlanItem}</td></tr>
 *   <tr><td>对外的身份</td><td>{@code PlanIntent}(计划侧)</td><td>{@code Intention}(认知侧)</td></tr>
 *   <tr><td>拿着哪份处境</td><td>收到 {@code PlanningContext}, 转成
 *       {@code IntentionContext} 再转发</td>
 *       <td>收到 {@code IntentionContext}, <b>但用自己握着的那份
 *       {@code PlanningContext}</b></td></tr>
 * </table>
 *
 * <h2>为什么它握着 {@code PlanningContext}, 而不是走一次反向转换</h2>
 * 因为<b>那个方向的反向转换不存在, 而且是有损的</b>:
 *
 * <pre>
 *   PlanningContext { now, human: HumanSnapshot, constraints, availableCapabilities, locations, attributes }
 *   IntentionContext { now,            humanSummary: String,          availableCapabilities, locationLabel, ... }
 * </pre>
 *
 * <p>{@code IntentionContext} 里只有一句<b>关于她的摘要字符串</b>, 而计划侧要的是一个
 * {@link PlanningContext.HumanSnapshot} <b>对象</b>。从一个字符串造不回一个快照 ——
 * 硬造出来的那个会把"她今天有什么约束"、"她在哪几个地点之间"一起抹掉,
 * 于是这条候选的可行性判定会与计划侧对同一件事的判定<b>不一致</b>,
 * 而且不一致得没有痕迹。
 *
 * <p>所以本类选择握着真实的那一份。这不是省事, 是"两处判定必须同源"的唯一实现方式:
 * 计划项的可执行性只有一个答案来源, 就是当初排定它时用的那份处境。
 *
 * <h2>于是它<b>是一张快照</b> —— 这是本类最需要被记住的性质</h2>
 * 它握着的是<b>构造那一刻</b>的处境与 <b>那一刻</b>的那个 {@link PlanItem}。
 * 计划表后来重排了、这一项被 {@code SUPERSEDED} 了、时间窗被挪了 ——
 * 本对象<b>都不会知道</b>, 而它会继续按老样子回答。
 *
 * <p>这个失效方式很安静, 所以有两道防线, 都不靠"记得别那么用":
 * <ol>
 *   <li><b>它每次都由当拍现造</b>。生产者
 *       ({@code HumanActor.candidatesAt}) 每一个心跳都从
 *       {@code plan().activeAt(now)} 重新读一遍、重新包一遍,
 *       <b>不缓存</b>。这与 {@code PlanScheduler} 选择"轮询而不是定时器"是同一条理由:
 *       任何需要被同步的状态, 都会在重排时出现"旧的已失效、新的还没生效"的那一缝;</li>
 *   <li><b>处境对不上时它会说出来</b>。见 {@link #describeMismatch} —— 传进来的
 *       {@code IntentionContext} 若不描述同一时刻, 本类不会拿老处境硬答,
 *       而是给出一条指名道姓的不可行。一个安静的错误答案比一次响亮的拒绝坏得多。</li>
 * </ol>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不判断这一项该不该做</b>。"现在要不要做它"是
 *       {@code DecisionEngine} 的判断 —— 本类只回答计划侧本来就会回答的那两件事
 *       ("做得了吗"、"分成哪几步"), 原封不动地转述。在这里补一层"她已经在做了,
 *       所以别再选它"的聪明, 会让同一件事有两个判定处, 而它们会漂移;</li>
 *   <li><b>不把 label 归一化</b>。优先级照抄计划侧的
 *       ({@link IntentionPriority#of(int, String)}), 即使措辞与
 *       {@code IntentionPriority} 的具名常量不同 —— {@code PlanPriority.ROUTINE}
 *       叫"日常安排"而 {@code IntentionPriority.ROUTINE} 叫"想做的事"。
 *       归一化会把"这个 label 是从计划表来的"这件事抹掉, 而它恰恰是诊断时
 *       要能看出来的。等级(level)是两边共同的量纲, 而唯一读它的地方
 *       ({@code PlanPriority#significantlyOutranks}) 只比等级, 所以往返无损。</li>
 * </ul>
 *
 * @see IntentionPlanIntent 反方向的那条边
 */
public final class PlanItemIntention implements Intention {

    private final PlanItem item;
    private final PlanningContext planning;

    /**
     * @param item     被当成候选的那一项。<b>必须是当拍从计划表读出来的</b> ——
     *                 存着一个跨心跳复用, 就是上面那张"快照"警告说的事
     * @param planning 排定它时用的那份处境。见 {@code PlanningContext#minimal}
     *                 与 {@code HumanActor#planningAt}
     */
    public PlanItemIntention(PlanItem item, PlanningContext planning) {
        this.item = Objects.requireNonNull(item,
                "要桥接的计划项不能为空 —— 见 PlanItemIntention 的类注释");
        this.planning = Objects.requireNonNull(planning,
                "桥必须带着排定这一项时的那份处境 —— 没有它, 可行性判断就没有依据, "
                        + "而 IntentionContext 又造不回 PlanningContext(见类注释)");
    }

    /** 它桥的是哪一项 —— 诊断与测试用。 */
    public PlanItem item() {
        return item;
    }

    /** 它握着的那份处境 —— 诊断与测试用。 */
    public PlanningContext planning() {
        return planning;
    }

    // ─────────────────────────── Intention 的实现 ───────────────────────────

    /**
     * 身份取自<b>计划项</b>, 不是取自它包着的那个意图。
     *
     * <p>这一项的身份是 {@link com.luxera.companion.human.life.plan.PlanItemId} ——
     * 它才是"这件事"在计划表上的名字(见 {@code PlanItem} 类注释: 任何改动
     * 都产生新的 id, 于是"移动"这件事在数据上不可否认地发生过)。
     * 拿 {@code item.intent().id()} 当身份, 会让重排后的新版本与老版本
     * 在候选清单里长得一模一样 —— 而它们说的不是同一件事了。
     */
    @Override
    public IntentionId id() {
        return IntentionId.of(item.id().value());
    }

    @Override
    public String description() {
        return item.intent().description();
    }

    /**
     * 重要性照抄计划侧 —— 等级是量纲, label 是措辞。见类注释最后一条。
     */
    @Override
    public IntentionPriority priority() {
        return IntentionPriority.of(item.priority().level(), item.priority().label());
    }

    /**
     * "这件事此刻做得了吗" —— 转发给计划侧的意图, 用<b>本类握着的那份处境</b>。
     *
     * <p>注意参数 {@code context} <b>不被用来判断</b>, 只被用来对账
     * (见 {@link #describeMismatch})。这不是疏忽: 计划侧的
     * {@code evaluate} 收的是 {@code PlanningContext}, 而那个方向的反向转换
     * 有损(见类注释), 所以能用来判断的只有手里这一份。
     */
    @Override
    public Feasibility evaluate(IntentionContext context) {
        String mismatch = describeMismatch(context);
        if (mismatch != null) {
            return Feasibility.no(mismatch);
        }
        return Feasibility.fromPlan(item.intent().evaluate(planning));
    }

    @Override
    public Set<String> requiredCapabilities() {
        return item.intent().requiredCapabilities();
    }

    /**
     * 真要去做的时候分成哪几步 —— 转发给计划侧的拆解。
     *
     * <p>这一条是 {@code commandsSent} 能不能动的关键: {@code DecisionEngine}
     * 拿它的返回值去造 {@code ActionIntent}, <b>空列表就意味着"她这一步没有可执行的动作"</b>
     * (那时引擎会把这一项排进日程而不是假装做了)。所以一个
     * {@code decompose} 恒为空的意图, 在数据上表现为"她一直在安排, 从来没做过"。
     *
     * <p>参数 {@code context} 与 {@link #evaluate} 同样只用于对账。
     */
    @Override
    public List<PlanIntent.ActionIntent> actions(IntentionContext context) {
        String mismatch = describeMismatch(context);
        if (mismatch != null) {
            // 与 evaluate 不同, 这里对不上就<b>抛</b>而不是返回空列表。
            // 理由: 返回空列表的语义是"这是一个原子意图"(见 Intention#actions 的说明),
            // 是一个合法的业务结论; 拿它去表达一个"处境对不上"的编程错误,
            // 会让这个错误看起来像她决定"不拆步骤" —— 一个不会有人去查的假象。
            throw new IllegalArgumentException(mismatch + " —— 动作拆分需要一个可信的处境, "
                    + "而返回空列表在这里会被读成'这是一个原子意图', 也就是把一个编程错误"
                    + "伪装成一个正常的业务结论");
        }
        return item.intent().decompose(planning);
    }

    @Override
    public String activityType() {
        return item.intent().activityType();
    }

    /** 能不能被重排器挪时间 —— 取自计划侧的意图, 与 {@code PlanItem#movable()} 同一个来源。 */
    @Override
    public boolean movableInTime() {
        return item.intent().movableInTime();
    }

    /**
     * 预计需要多久 —— 取<b>计划项</b>上的那个值, 不是意图上的。
     *
     * <p>两者会不同, 而且差别是有意义的: {@code PlanItem} 的类注释里那张表写明,
     * {@code window} 回答"这段时间她归这一项", {@code expectedDuration} 回答
     * "她觉得要做多久"。排定时可以把窗口留得比预计时长长(留缓冲),
     * 而缓冲是<b>排定的结果</b>, 不是意图的一部分 —— 所以这里读计划项。
     */
    @Override
    public java.time.Duration expectedDuration() {
        return item.expectedDuration();
    }

    /**
     * 回到计划侧 —— <b>直接返回它本来就是的那一个</b>。
     *
     * <p>这是本类唯一一处覆盖 {@link Intention#toPlan()} 的地方, 而覆盖的理由很直白:
     * 默认实现会返回 {@code new IntentionPlanIntent(this)}, 那意味着
     * 计划项 → 意图 → 计划项 绕一圈回到一个<b>适配器</b>上, 而不是回到
     * 她原本那一项。两个后果都不好:
     *
     * <ul>
     *   <li>身份会漂。{@code IntentionPlanIntent.id()} 取的是
     *       {@code Intention#id()} —— 也就是本类给出的
     *       {@code PlanItemId}, 而原意图有自己的 {@code IntentId}。
     *       于是"这一项还是不是原来那一件"取决于它走了哪条路;</li>
     *   <li>{@code DecisionEngine} 在拆不出动作时会用 {@code chosen.toPlan()}
     *       造一条 {@code PlanMutation.Insert}。绕一圈的写法会让插进计划表的
     *       是一个包着桥的适配器, 而不是她原本的计划项类型。</li>
     * </ul>
     *
     * <p>一句话: 一个计划项变不回"更计划侧的东西", 它本来就是计划侧的东西。
     */
    @Override
    public PlanIntent toPlan() {
        return item.intent();
    }

    @Override
    public String describe() {
        return "计划桥 " + item.describe() + " (处境取自 " + planning.now() + ")";
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 对账 ───────────────────────────

    /**
     * 传进来的处境与本类握着的那份对得上吗。对得上返回 {@code null}。
     *
     * <h2>为什么值得一次比较</h2>
     * 因为"拿老处境回答新问题"是本类唯一会安静出错的方式, 而它的结论会直接
     * 影响她这一刻做不做这件事。一次 {@code Instant} 比较换掉一整类无声错误,
     * 这个买卖在任何一天都划算。
     *
     * <p>比的是<b>时刻</b>, 不是整份处境: 时刻不同就一定不是同一份处境,
     * 而时刻相同也可能是两份不同的 {@code PlanningContext}(这是允许的 ——
     * 它们描述同一个瞬间, 谁赢都不影响"此刻做得了吗"这个答案的时间基准)。
     */
    private String describeMismatch(IntentionContext context) {
        if (context == null) {
            return "候选意图拿到了一份空的处境 —— 没有处境就没有'此刻做得了吗'的答案, "
                    + "而按老处境回答会是一个不会被任何人发现的错误";
        }
        Instant mine = planning.now();
        Instant theirs = context.now();
        if (theirs == null || !theirs.equals(mine)) {
            return "这条候选带着 " + mine + " 的处境, 却被拿去在 " + theirs + " 这一刻判断 —— "
                    + "两者不是同一瞬间。计划项桥是<b>一张快照</b>, 它只会照老样子回答; "
                    + "正确的做法是每一个心跳都从 plan().activeAt(now) 重新包一次 "
                    + "(见 PlanItemIntention 类注释第二节)";
        }
        return null;
    }
}
