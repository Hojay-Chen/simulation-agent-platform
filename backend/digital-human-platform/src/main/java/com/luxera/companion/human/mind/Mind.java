package com.luxera.companion.human.mind;

import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.human.body.senses.SensoryStimulus;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.attention.AttentionContext;
import com.luxera.companion.human.mind.attention.AttentionService;
import com.luxera.companion.human.mind.cognition.IntrusiveThought;
import com.luxera.companion.human.mind.cognition.ReasoningContext;
import com.luxera.companion.human.mind.cognition.ReasoningEngine;
import com.luxera.companion.human.mind.cognition.ReasoningResult;
import com.luxera.companion.human.mind.decision.Decision;
import com.luxera.companion.human.mind.decision.DecisionEngine;
import com.luxera.companion.human.mind.decision.DecisionMade;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.memory.InMemoryMemoryStore;
import com.luxera.companion.human.mind.memory.MemoryRecord;
import com.luxera.companion.human.mind.memory.MemoryStore;
import com.luxera.companion.human.mind.memory.WorkingMemory;
import com.luxera.companion.human.mind.percept.Percept;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.percept.Perception;
import com.luxera.companion.human.mind.relationship.BindReason;
import com.luxera.companion.human.mind.relationship.ChatAccountId;
import com.luxera.companion.human.mind.relationship.PersonId;
import com.luxera.companion.human.mind.relationship.RelationshipBound;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.4.1 —— <b>Mind</b>: 把感知、注意、认知、记忆、关系、意图、决定装在一起的那个东西。
 *
 * <pre>
 *   Mind
 *   ├── Perception      把感官刺激解释成"我感知到了什么"
 *   ├── Attention       决定哪些感知值得进入工作认知
 *   ├── Cognition       理解 / 推理 / 计划 / 决策(候选)
 *   ├── Memory          情景 / 语义 / 共享 / 关系 / 自我 + 工作记忆
 *   ├── Relationship    她认识谁、和谁什么关系、对应哪个账号
 *   ├── Intention       她想要什么(开放对象)
 *   └── Decision        她决定做什么(结果对象, 不是枚举)
 * </pre>
 *
 * <h2>它不直接访问 World —— 这一条是本类存在的理由</h2>
 * §3.4.2 列了三种<b>禁止</b>的写法, 本类里一个都没有, 而且<b>不是靠自觉</b>:
 *
 * <pre>{@code
 * // ❌ 这三种写法在本类里连编译都过不了:
 * mind.phone().readMessages();                 // 没有 phone() 这个方法
 * mind.chatApplication().getConversations();   // 没有 chatApplication() 这个方法
 * mind.environment().getTemperature();         // 没有 environment() 这个方法
 * }</pre>
 *
 * <p>本类<b>认识的所有类型</b>里没有一个是 {@code world/} 的: 它拿得到的是
 * {@code boundary/} 的事件与动作(那是两个世界共用的词汇), {@code human/} 自己的部件,
 * 以及她自己攒下来的东西。于是"Mind 顺手看一眼现在几度"这个念头, 在本类里
 * 没有可用的工具 —— <b>缺少工具比缺少纪律可靠</b>。
 *
 * <p>她与世界之间唯一的通路是:
 *
 * <pre>
 *   Mind → Decision → ActionCommand → ActionFabric → Phone → Application
 * </pre>
 *
 * <h2>§3.4.2 的唯一例外, 以及它为什么不算例外</h2>
 * Mind 持有<b>人物对象</b>与<b>关系</b>({@link RelationshipGraph})。文档说得很清楚:
 * 因为"她认识谁"是<b>她自己的知识</b>, 不是世界的一部分。所以本类持有它,
 * 却不持有 {@code ChatAccount} —— 她心里是 {@code PersonObject}, 账号只是一个字符串
 * (见 {@link com.luxera.companion.human.mind.relationship.ChatAccountId})。
 *
 * <h2>它为什么持有 {@link EventFabric}</h2>
 * 因为"她做过什么"必须能被记下来。事件流是<b>两个方向的</b>: 世界往里投事件,
 * Human 也往里投她自己产生的观察(见 {@code AttentionShifted} 与 {@link DecisionMade} ——
 * 它们已经这么做了)。总线给的是<b>投递与回看</b>的能力, 不是<b>查询世界</b>的能力 ——
 * 它上面没有一个方法的返回类型是 {@code world/} 的对象。
 *
 * <p>这一条需要说清楚, 否则"Mind 持有总线"看起来像 §3.4.2 的漏洞。它不是:
 * 判断某样东西是不是"世界", 标准是<b>它背后有没有一个能回答"世界现在什么样"的对象</b>,
 * 而不是"它是不是来自外部"。总线只有事件, 事件是发生过的事 —— 而发生过的事
 * 是她的记忆的一部分, 不是世界的当前状态。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不读系统时钟</b>。本类的每一个方法都要求调用方把时刻传进来。这不是洁癖:
 *       仿真的全部意义是"同样的输入得到同样的结果", 而一个藏在深处的
 *       {@code Instant.now()} 会让同一次回放出不同的结论, 且没有任何报错;</li>
 *   <li><b>不决定"要不要想"</b>。本类提供 {@link #think} 与 {@link #decide},
 *       但什么时机调用它们是外面的事(那需要 tick 与调度, 属于 runtime。
 *       这一条与 {@code Perception} 不做判断是同一种分工);</li>
 *   <li><b>不做措辞</b>。它只负责在<b>决定已经存在之后</b>把措辞这一步接上 ——
 *       见 {@link #speak}, 那里是全项目唯一一处"决定"与"模型"的接缝;</li>
 *   <li><b>不替调用方判断可行性</b>。{@code Intention.evaluate} 在意图自己身上,
 *       本类只是把处境递给它。</li>
 * </ul>
 */
public final class Mind {

    private final String humanId;
    private final EventFabric fabric;
    private final PlanBoard plan;
    private final RelationshipGraph relationships;
    private final ReasoningEngine reasoning;
    private final LanguageEngine language;
    private final Perception perception;
    private final AttentionService attention;
    private final MemoryStore memory;
    private final WorkingMemory workingMemory;
    private final DecisionEngine decisions;

    private long decisionsMade;
    private String lastDecision = "";

    public Mind(String humanId, EventFabric fabric, PlanBoard plan,
                RelationshipGraph relationships, ReasoningEngine reasoning,
                LanguageEngine language, PerceptLexicon lexicon) {
        this(humanId, fabric, plan, relationships, reasoning, language,
                new Perception(lexicon), new InMemoryMemoryStore());
    }

    /**
     * 完整装配。
     *
     * @param fabric        事件总线。可以为 {@code null} —— 那时她自己产生的事件
     *                      只是不记账(见 {@link EventFabric}); 测试用
     * @param language      语言引擎。可以先给 {@link LanguageEngine#silent()},
     *                      之后再换成真的 —— 但注意<b>它在任何情况下都不会在决定之前被调用</b>
     */
    public Mind(String humanId, EventFabric fabric, PlanBoard plan,
                RelationshipGraph relationships, ReasoningEngine reasoning,
                LanguageEngine language, Perception perception, MemoryStore memory) {
        this.humanId = humanId == null || humanId.isBlank() ? "unknown" : humanId;
        this.fabric = fabric;
        this.plan = Objects.requireNonNull(plan, "Mind 必须能看到计划表 —— 否则她永远不知道自己该做什么");
        this.relationships = Objects.requireNonNull(relationships,
                "Mind 必须持有关系网 —— 这是 §3.4.2 允许它持有的例外之一");
        this.reasoning = Objects.requireNonNull(reasoning, "Mind 必须有一个认知引擎");
        this.language = language == null ? LanguageEngine.silent() : language;
        this.perception = Objects.requireNonNull(perception, "Mind 必须能解释刺激");
        this.memory = memory == null ? new InMemoryMemoryStore() : memory;
        this.attention = new AttentionService(fabric);
        this.workingMemory = new WorkingMemory();
        this.decisions = new DecisionEngine(this.humanId, fabric);
    }

    /** 一条刺激进来 —— 与 {@link #absorb} 同名, 但省掉 fabric 之后的常见写法。 */
    public static Mind of(String humanId, EventFabric fabric, PlanBoard plan,
                          RelationshipGraph relationships, ReasoningEngine reasoning,
                          LanguageEngine language, PerceptLexicon lexicon) {
        return new Mind(humanId, fabric, plan, relationships, reasoning, language, lexicon);
    }

    // ─────────────────────────── 感知与注意 (§3.4.3 / §3.4.4) ───────────────────────────

    /**
     * 一批感官事件进来: <b>解释 → 注意 → 进工作记忆</b>。
     *
     * <p>只有被注意力放行的那些才会进工作记忆, 也只有它们能被认知看到 ——
     * 这就是"她没注意到"与"她注意到了但没在意"能分开的机制。
     *
     * @return 被放行的感知, 按注意力得分从高到低。<b>可能是空列表</b> ——
     *         空是一个完全正常的结果(她在睡觉), 不是错误
     */
    public List<AttendedPercept> absorbAll(List<SensoryEvent> events, AttentionContext context) {
        Objects.requireNonNull(context, "没有处境的注意力判断等于'她永远有空'");
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Percept> percepts = new ArrayList<>();
        for (SensoryEvent event : events) {
            percepts.add(perception.explain(event));
        }
        return absorb(percepts, context);
    }

    public List<AttendedPercept> absorb(SensoryEvent event, AttentionContext context) {
        Objects.requireNonNull(event, "要吸收的刺激不能为空");
        return absorb(List.of(perception.explain(event)), context);
    }

    /** 身体内部的感受(饿、冷、疼、累) —— 与外部刺激同构, 只是来源不同。 */
    public List<AttendedPercept> absorb(SensoryStimulus stimulus, AttentionContext context) {
        Objects.requireNonNull(stimulus, "要吸收的内部感受不能为空");
        return absorb(List.of(perception.explainInteroceptive(stimulus)), context);
    }

    /**
     * 已经解释好的感知进来。给"同一条刺激要喂给两个消费者"的场景用。
     */
    public List<AttendedPercept> absorb(List<Percept> percepts, AttentionContext context) {
        List<AttendedPercept> attended = attention.attendAll(percepts, context);
        for (AttendedPercept one : attended) {
            workingMemory.admitPercept(one.percept(), one.attendedAt());
        }
        return attended;
    }

    /**
     * 一个念头自己冒出来 —— "想起有件事没做完"。
     *
     * <p>它不是对外的反应, 而是她<b>内部</b>产生的刺激。走的是感知这条路:
     * 它在架构上是一条 {@code SensoryEvent}, 因为"她忽然想起来"与"她忽然听到"
     * 在后续处理上完全一样 —— 都要经过注意力那一关。
     */
    public Optional<Percept> raiseIntrusiveThought(String contentRef, double intensity, Instant at) {
        IntrusiveThought thought = IntrusiveThought.of(contentRef, intensity, at);
        if (fabric != null) {
            fabric.publish(thought);
        }
        return Optional.of(perception.explain(thought));
    }

    // ─────────────────────────── 认知与决定 (§3.4.5 / §3.4.7) ───────────────────────────

    /** 想一轮。产出的是<b>候选</b>, 不是结论 —— 结论在 {@link #decide}。 */
    public ReasoningResult think(ReasoningContext context) {
        Objects.requireNonNull(context, "认知必须有上下文 —— 无中生有的推理不叫推理");
        return reasoning.reason(context);
    }

    /**
     * 做一次决定, 并把它落到该落的地方。
     *
     * <p>三件事, 顺序固定:
     * <ol>
     *   <li><b>产生决定</b> —— 纯规则, 无模型、无时钟(见 {@code DecisionEngine});</li>
     *   <li><b>应用计划改动</b> —— 交给 {@link PlanBoard#apply}。决定本身是值,
     *       应用它是动作, 两件事分开才能回答"这个决定能不能被应用两次";</li>
     *   <li><b>记账</b> —— 投一条 {@code mind.decision-made.v1}, 包括"她决定不做"的那些。
     *       不记账的话, "她没回"与"她没看见"在数据上长得一样。</li>
     * </ol>
     *
     * <p><b>这里不调用语言引擎。</b>要说的话在 {@link #speak} 里, 而那一步
     * 必须由调用方在拿到决定之后显式地做 —— 顺序纪律靠"两步是两个方法"来保证,
     * 而不是靠一个内部 if。
     */
    public Decision decide(ReasoningResult result, IntentionContext situation, PlanItem current) {
        Decision decision = decisions.decide(result, situation, current);

        if (decision.changesPlan()) {
            plan.apply(situation.now(), decision.reason().narrative(), decision.planMutations());
        }

        decisionsMade++;
        lastDecision = decision.describe();
        decisions.announce(decision, situation.now());
        return decision;
    }

    /**
     * 做一次决定, 手上在做的事由本方法从计划表里查。
     *
     * <p>"此刻在做的那件事"取的是<b>时间上覆盖这一刻、优先级最高</b>的那一项。
     * 它不由决定引擎自己查 —— 引擎要能被纯函数地测试, 就不能持有计划表。
     */
    public Decision decide(ReasoningResult result, IntentionContext situation) {
        return decide(result, situation, currentItem(situation.now()).orElse(null));
    }

    /**
     * 她手头正做着的那件事。
     *
     * <p>同时覆盖这一刻的可能有几项(比如"写作业"里套着"查资料"), 取优先级最高的那一个 ——
     * 因为"她正做着什么"这个问题的答案应当是她最投入的那一件, 而不是随便哪一件。
     * 平局用 id 打断, 理由同 {@code MindDecisionPlanner} 的全序: <b>不能有平局</b>。
     */
    public Optional<PlanItem> currentItem(Instant at) {
        Objects.requireNonNull(at, "查当前活动必须带时刻 —— 不许读系统时钟");
        return plan.activeAt(at).stream().max(
                Comparator.comparingInt((PlanItem item) -> item.priority().level())
                        .thenComparing(item -> item.id().toString()));
    }

    /**
     * 把决定变成一句人话 —— <b>全项目唯一一处"决定"与"模型"的接缝</b>。
     *
     * <pre>{@code
     * Decision d = mind.decide(...);          // 先有决定 —— 可复现、可回溯
     * Optional<String> text = mind.speak(d, situation);   // 再说 —— 措辞错了不影响决定对不对
     * }</pre>
     *
     * <p>还有一个更重要的性质: <b>当决定不需要说话时, 语言引擎根本不会被调用</b>。
     * 这一条做成了返回值而不是异常或日志 —— 它让"这次有没有叫模型"变成测试
     * 可以数出来的东西(用一个计数假引擎), 而不是只能靠读代码确认的约定。
     *
     * @return 要说的话; 决定不需要说话时返回空。返回空<b>不等于</b>失败 ——
     *         最典型的空就是"她决定继续写作业"
     */
    public Optional<String> speak(Decision decision, String situation) {
        Objects.requireNonNull(decision, "没有决定就没有要说的 —— 语言引擎不允许在决定之前被调用");
        if (!decision.needsWording()) {
            return Optional.empty();
        }
        return Optional.of(language.render(decision, situation));
    }

    // ─────────────────────────── 记忆 (§3.4 / §9 验收标准 E) ───────────────────────────

    /**
     * 一次动作的结果回来了。
     *
     * <p>这是<b>正文进入工作记忆的唯一入口</b>(§9 验收标准 E): 她主动读了之后,
     * 读到的东西才成为她的一部分。一条通知响了、一个动作发出去了, 都不够 ——
     * 只有结果回来了才算。这一点决定了"她不该在读到之前就知道内容"能否被验证。
     */
    public Optional<WorkingMemory.Entry> admitActionOutcome(ActionResult result, Instant at) {
        return workingMemory.admitOutcome(result, at);
    }

    /** 记住一件事 —— 进长期记忆。记忆的内容是<b>她的理解</b>, 不是原文。 */
    public void remember(MemoryRecord record) {
        memory.store(Objects.requireNonNull(record, "要记住的东西不能为空"));
    }

    public List<MemoryRecord> recall(java.util.Set<String> cues, int limit) {
        return memory.recall(cues, limit);
    }

    // ─────────────────────────── 她的通讯录 (§3.4.6) ───────────────────────────

    /**
     * 她第一次见到一个账号 —— <b>见到了, 但还不算认识</b>。
     *
     * <p>这一步<b>不发</b> {@code mind.relationship-bound.v1}, 因为绑定还没有发生。
     * 它只留下"有个不认识的人找我"这个状态。若这里就记账, 那么"她的通讯录是她自己
     * 长出来的"这句话里的"长出来"就无从统计了 —— 见 {@link RelationshipBound}。
     */
    public PersonId meetAccount(ChatAccountId accountId, Instant at) {
        return relationships.meet(accountId, at);
    }

    /**
     * 她建立了一条绑定 —— 这一步会记账。
     *
     * <p>记账在<b>绑定成功之后</b>: 若 {@code bind} 抛异常(一个账号绑到第二个人身上),
     * 事件不能已经发出去了, 否则事件流里会留下一条"她认识了某人"而通讯录里没有。
     *
     * @return 那条事件; 没有总线时为 {@link Optional#empty()}
     */
    public Optional<RelationshipBound> bindAccount(ChatAccountId accountId, PersonId personId,
                                                   BindReason reason, Instant at) {
        relationships.bind(accountId, personId, reason);
        return announceBinding(accountId, at);
    }

    /**
     * 把"见过"升格成"认识" —— 用的是当初 {@link #meetAccount} 留下的那个人。
     *
     * <p>它<b>不</b>新建一个人: 见 {@code RelationshipGraph#promote} 关于
     * "那个总在晚上找我的人"这个印象为什么会丢。
     */
    public Optional<RelationshipBound> promoteAccount(ChatAccountId accountId, BindReason reason,
                                                      Instant at) {
        relationships.promote(accountId, reason, at);
        return announceBinding(accountId, at);
    }

    /** 按通讯录里的现状记一条账 —— 人、名字与来源都从图里读, 免得两处不一致。 */
    private Optional<RelationshipBound> announceBinding(ChatAccountId accountId, Instant at) {
        return relationships.resolve(accountId).flatMap(person ->
                relationships.bindReason(accountId).map(reason -> {
                    RelationshipBound event = RelationshipBound.of(
                            at, accountId, person.id(), person.name(), reason);
                    if (fabric != null) {
                        fabric.publish(event);
                    }
                    return event;
                }));
    }

    // ─────────────────────────── 读取 ───────────────────────────

    public String humanId() {
        return humanId;
    }

    public Perception perception() {
        return perception;
    }

    public AttentionService attention() {
        return attention;
    }

    public WorkingMemory workingMemory() {
        return workingMemory;
    }

    public MemoryStore memory() {
        return memory;
    }

    public RelationshipGraph relationships() {
        return relationships;
    }

    public PlanBoard plan() {
        return plan;
    }

    public ReasoningEngine reasoning() {
        return reasoning;
    }

    public LanguageEngine language() {
        return language;
    }

    public long decisionsMade() {
        return decisionsMade;
    }

    // ─────────────────────────── 摘要 ───────────────────────────

    /**
     * 她此刻的样子。
     *
     * <p>只带计数与一句当前活动, <b>不带正文</b> —— 理由见 {@link MindSnapshot}。
     */
    public MindSnapshot snapshot(Instant at) {
        Objects.requireNonNull(at, "快照必须带时刻 —— 不许读系统时钟");
        return new MindSnapshot(
                humanId,
                at,
                workingMemory.size(),
                workingMemory.load(),
                memory.size(),
                relationships.size(),
                relationships.selfMadeBindingCount(),
                currentItem(at).map(item -> item.intent().description()).orElse(""),
                (int) Math.min(Integer.MAX_VALUE, decisionsMade),
                lastDecision);
    }

    public String describe() {
        return "Mind[" + humanId + "] 认知=" + reasoning.engineId()
                + ", 措辞=" + language.engineId()
                + ", 认识 " + relationships.size() + " 人, 长期记忆 " + memory.size() + " 条, "
                + "累计决定 " + decisionsMade + " 次";
    }

    @Override
    public String toString() {
        return describe();
    }
}
