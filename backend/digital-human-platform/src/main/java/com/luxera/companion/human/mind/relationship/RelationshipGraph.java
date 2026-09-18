package com.luxera.companion.human.mind.relationship;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.6 —— <b>她认识谁、和谁什么关系、以及哪个账号对应哪个人</b>。
 *
 * <h2>它要守住的那句话</h2>
 * <blockquote>
 * 她的"通讯录"是<b>她自己长出来的</b>, 不是从聊天平台同步下来的。<br>
 * 这与真人一致 —— 你手机里的备注名是你自己起的, 平台不告诉你这个人是谁。
 * </blockquote>
 *
 * <p>这句话在代码里靠<b>两张表</b>成立, 而不是靠约定:
 *
 * <pre>{@code
 * Map<ChatAccountId, PersonId> accountBindings;   // 平台给的标识 → 她给的身份
 * Map<PersonId, PersonObject>  persons;           // 她给的身份 → 她心里的那个人
 * }</pre>
 *
 * <p>如果只留一张 {@code Map<String, PersonObject>}, 那么"这个账号是谁"这个问题
 * 只能靠平台的字符串回答, 而 {@link PersonObject#name()} 会不可避免地变成平台昵称的
 * 缓存 —— 那正是 §3.4.6 要避免的实现。两张表还有一个直接后果:
 * <b>"见过"与"认识"是两件事</b>, 见 {@link #meet}。
 *
 * <h2>创建 agent 时的四步(§3.4.6 原文)</h2>
 * <ol>
 *   <li>创建一个 {@link PersonObject}: 用户。name = 用户昵称, relation = 由 persona 指定;</li>
 *   <li>创建 {@link Relationship}(用户 ↔ agent), 带上初始的亲密值;</li>
 *   <li>创建绑定: 用户的 {@link ChatAccountId} → 用户的 {@code PersonObject}。
 *       这一步用聊天平台侧的名册端口拿到 owner 对应的账号;</li>
 *   <li>之后新出现的账号(她加了新朋友), 绑定由她在聊天过程中<b>逐步建立</b> ——
 *       第一次见到一个陌生账号时, 她只知道"有个不认识的人找我"; 见过几次、聊过之后,
 *       她会形成对这个人的印象并建立绑定。</li>
 * </ol>
 *
 * <p>前两步 + 第 3 步是 {@link #bootstrap}; <b>第 4 步不是任何方法, 而是一段使用方式</b> ——
 * 没有 {@code importContacts()} 这样的入口, 因为这正是本设计要防的那条捷径。
 * 谁想"把平台好友列表灌进来", 会发现这里没有任何一个方法收得下那份列表:
 * 唯一的入口 {@link #meet} 一次只接受一个账号, 而且只记下"有这么个账号"。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不生成印象</b>。{@link #impression} 是认知环节算好了传进来的。
 *       在这里做一次"根据聊天次数自动生成印象"会让她的评价变成聊天频率的函数 ——
 *       而那与"她怎么想"是两件事;</li>
 *   <li><b>不读时钟</b>。所有时刻都是参数。一个自己读时钟的关系网会让回放出来的
 *       "她什么时候认识他的"随运行时刻漂移;</li>
 *   <li><b>不落库</b>。持久化在 {@code persistence} 层。</li>
 * </ul>
 *
 * <h2>它是可变的 —— 这是一个有意的选择</h2>
 * 与 {@link PersonObject} / {@link Relationship}(不可变 record)相反, 本类
 * <b>就地修改</b>并返回 {@code this} 以便链式调用。理由是关系网的性质是
 * "她的知识", 而知识是累积的: 每次变化都复制整张图, 会让 {@code Mind} 每轮
 * 多出一次全量拷贝, 而收益(撤销某一轮的认识)从来没有被需要过 ——
 * 需要的是<b>历史</b>, 而历史在关系记忆里, 不在这里。
 */
public final class RelationshipGraph {

    /** 预置的主人 —— 见 {@link #bootstrap} 的第 ① 步。 */
    public static final PersonId OWNER = PersonId.of("owner");

    private final Map<PersonId, PersonObject> persons = new LinkedHashMap<>();
    private final Map<PersonId, Relationship> relationships = new LinkedHashMap<>();
    private final Map<ChatAccountId, PersonId> accountBindings = new LinkedHashMap<>();
    private final Map<PersonId, Set<ChatAccountId>> accountsByPerson = new LinkedHashMap<>();

    /**
     * 见过但<b>还不算认识</b>的账号 —— 第六步到第七步之间的那个中间状态。
     *
     * <p>它必须单独放, 不能往 {@link #accountBindings} 里塞一个"暂定"的人:
     * 一旦塞进去, {@link #resolve} 就不再为空, 于是"她认不认识这个账号"这个判断
     * 会永远回答"认识" —— 而那等于删掉了"陌生人"这个状态。
     */
    private final Map<ChatAccountId, PersonId> metNotBound = new LinkedHashMap<>();

    /**
     * 每条绑定是怎么来的 —— 与 {@link BindReason} 的存在理由一一对应。
     *
     * <p>它与 {@link #accountBindings} 分开而不是合成一个 {@code record Binding(...)},
     * 是因为两者的读法完全不同: 绑定表是热路径(每条消息都要查一次"这是谁"),
     * 而来源只在统计与回放时被读。合成一个会让热路径多绕一层对象。
     */
    private final Map<ChatAccountId, BindReason> bindReasons = new LinkedHashMap<>();

    private final AtomicLong meetSequence = new AtomicLong();

    private RelationshipGraph() {
    }

    /** 一张空的图 —— 用于测试, 以及"她还没被创建出来"的状态。 */
    public static RelationshipGraph empty() {
        return new RelationshipGraph();
    }

    /**
     * §3.4.6 的初始化流程 —— 前三步, 一次做完。
     *
     * <p>它<b>只</b>做这三步。第四步(她自己认识新的人)不在这里, 也不该在这里:
     * 一个"顺便把平台好友都建好"的 bootstrap 会让今天这个设计立刻退回原样。
     *
     * <p>调用方要自己拿到 {@code ownerAccount}: §3.4.6 写明那来自聊天平台侧的
     * 名册端口。本方法收下它而不去查它, 于是 {@code human.mind} 这一层
     * <b>不需要引用任何平台侧的类型</b>。
     */
    public static RelationshipGraph bootstrap(PersonaSpec persona, ChatAccountId ownerAccount,
                                              Instant at) {
        Objects.requireNonNull(persona, "创建时的人格配置不能为空 —— 见 PersonaSpec");
        Objects.requireNonNull(ownerAccount, "主人的账号不能为空 —— §3.4.6 第 ③ 步需要它");
        Objects.requireNonNull(at, "初始化必须带时刻 —— 不许读系统时钟");

        RelationshipGraph graph = new RelationshipGraph();

        // ① 创建一个人物对象: 用户
        graph.persons.put(OWNER, PersonObject.of(OWNER, persona.ownerName(), at));

        // ② 创建 Relationship(用户 ↔ agent), 带上初始的亲密值
        graph.relationships.put(OWNER, Relationship.of(OWNER, persona.ownerRelation(),
                persona.initialCloseness(), persona.initialTrust(), at, at));

        // ③ 创建绑定: 用户的账号 → 用户的人物对象
        graph.bind(ownerAccount, OWNER, BindReason.bootstrap());

        // ④ (不在这里)之后新出现的账号由她自己逐步建立 —— 见类注释
        return graph;
    }

    // ─────────────────────────── 她怎么认识人 ───────────────────────────

    /**
     * 她看到一个账号 —— <b>"这是谁? 我认识吗?"</b>。
     *
     * <p>返回空, 意味着她不认识: 她会说"有个不认识的人找我", 而不是编一个名字出来。
     * 这是 §3.4.6 里"陌生账号"的<b>唯一来源</b>。
     *
     * <p>注意它<b>不会</b>因为账号看起来眼熟就返回什么 —— 它只查 {@link #accountBindings},
     * 也就是"她自己建立过的绑定"。平台说这个账号是什么、以前有没有出现过, 这里一概不知道,
     * 因为那些信息没有通道进入这个类。
     */
    public Optional<PersonObject> resolve(ChatAccountId accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        PersonId personId = accountBindings.get(accountId);
        return personId == null ? Optional.empty() : Optional.ofNullable(persons.get(personId));
    }

    /**
     * 她看到一个账号, 而且这个账号<b>她见过</b> —— 但未必认识。
     *
     * <p>它与 {@link #resolve} 的差别就是"见过"与"认识"的差别, 而这个差别是
     * §3.4.6 第四步的核心: 第一次见面时她只能形成一个模糊的印象
     * ("那个总在晚上找我的人"), 那个印象必须先有地方放, 才谈得上"聊过几次之后建立绑定"。
     *
     * <p>没有它在数据上的位置时, 实现者只有两条路: 要么立刻建立绑定(于是"陌生人"没了),
     * 要么每次见面都新建一个人(于是同一个账号在她心里变成十个人)。
     */
    public Optional<PersonObject> seen(ChatAccountId accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        Optional<PersonObject> known = resolve(accountId);
        if (known.isPresent()) {
            return known;
        }
        PersonId staged = metNotBound.get(accountId);
        return staged == null ? Optional.empty() : Optional.ofNullable(persons.get(staged));
    }

    /**
     * 她第一次见到一个账号 —— 记下"有这么个账号", 并给她一个可以挂印象的位置。
     *
     * <p>它<b>不建立绑定</b>: 返回的 {@link PersonId} 指向一个还没有名字的人物对象,
     * 而 {@link #resolve} 对这个账号仍然返回空。这个不对称是刻意的, 也是本类最容易被
     * "顺手改成对称"的地方 —— 改成对称的那一刻, "她慢慢认识一个人"就不再会发生,
     * 因为她在第一眼就已经认识所有人了。
     *
     * <p>对同一个账号重复调用是<b>幂等</b>的: 第二次返回同一个人, 不会多出一个人来。
     */
    public PersonId meet(ChatAccountId accountId, Instant at) {
        Objects.requireNonNull(accountId, "见到的账号不能为空");
        Objects.requireNonNull(at, "见面必须带时刻 —— 不许读系统时钟");
        PersonId existing = accountBindings.get(accountId);
        if (existing != null) {
            return existing;
        }
        PersonId staged = metNotBound.get(accountId);
        if (staged != null) {
            return staged;
        }
        PersonId created = newPersonId();
        persons.put(created, PersonObject.unnamed(created, at));
        relationships.put(created, Relationship.stranger(created, at));
        metNotBound.put(accountId, created);
        return created;
    }

    /**
     * 她认识了一个<b>还没有账号的人</b>(现实里的邻居、同学)。
     *
     * <p>这个重载存在的理由就是 §3.4.6 那句话: "她也可能认识一个还没有聊天账号的人,
     * 那是一个没有绑定的 {@code PersonObject}"。没有一个"不带账号"的入口,
     * 这句话在代码里就无法表达, 而实现者会被迫造一个假账号 —— 那是最糟的一种
     * 折中: 它会安静地污染绑定表, 并且永远没人敢删。
     */
    public PersonId introduce(Instant at) {
        Objects.requireNonNull(at, "认识一个人必须带时刻 —— 不许读系统时钟");
        PersonId created = newPersonId();
        persons.put(created, PersonObject.unnamed(created, at));
        relationships.put(created, Relationship.stranger(created, at));
        return created;
    }

    /**
     * 建立绑定: <b>账号 → 人物对象</b>。§3.4.6 的第 4 步就是它被反复调用的过程。
     *
     * <p>两条约束:
     * <ul>
     *   <li><b>一个账号只对应一个人。</b>把同一个账号绑到第二个人身上会抛异常 ——
     *       否则同一条消息在她心里会同时属于两个人, 而"是谁在跟我说话"这个问题
     *       会取决于查表的顺序;</li>
     *   <li><b>一个人可以有多个账号</b>(小号)。这正是 {@link PersonId} 存在的理由,
     *       所以这里<b>不</b>阻止一个 {@code PersonId} 被绑定多次。</li>
     * </ul>
     */
    public RelationshipGraph bind(ChatAccountId accountId, PersonId personId, BindReason reason) {
        Objects.requireNonNull(accountId, "要绑定的账号不能为空");
        Objects.requireNonNull(personId, "要绑定的人不能为空");
        Objects.requireNonNull(reason, "绑定必须说明来源 —— 见 BindReason");
        PersonObject person = persons.get(personId);
        if (person == null) {
            throw new IllegalArgumentException(
                    "要把 " + accountId + " 绑到 " + personId + " 上, 但她心里没有这个人 —— "
                            + "先让它存在(" + "meet" + " 或 " + "introduce" + "), 再建立绑定。"
                            + "反过来做等于把平台的账号当成了人的身份, 而 §3.4.6 明确说那是两个概念");
        }
        PersonId bound = accountBindings.get(accountId);
        if (bound != null && !bound.equals(personId)) {
            throw new IllegalArgumentException(
                    "账号 " + accountId + " 已经绑在 " + bound + " 上, 不能再绑到 " + personId
                            + " —— 一个账号只对应一个人。若这是同一个人换号的场景, "
                            + "请为新账号建一条绑定, 而不是改这条");
        }
        accountBindings.put(accountId, personId);
        bindReasons.put(accountId, reason);
        accountsByPerson.computeIfAbsent(personId, k -> new LinkedHashSet<>()).add(accountId);
        metNotBound.remove(accountId);
        return this;
    }

    /** 这条绑定是怎么来的 —— 见 {@link BindReason} 的"为什么这个字段是必需的"。 */
    public Optional<BindReason> bindReason(ChatAccountId accountId) {
        return accountId == null ? Optional.empty() : Optional.ofNullable(bindReasons.get(accountId));
    }

    /**
     * 把"见过"升格成"认识" —— 用 {@link #meet} 留下的那个人, 不新建。
     *
     * <p>这是 §3.4.6 第四步的正常收尾: "见过几次、聊过之后, 她会形成对这个人的印象
     * 并建立绑定"。它需要一个独立的入口, 因为升格时用的是<b>当初那个人</b> ——
     * 若实现者写成"再 {@code meet} 一次然后 bind", 会得到一个新人,
     * 于是"那个总在晚上找我的人"这个印象就丢了。
     */
    public RelationshipGraph promote(ChatAccountId accountId, BindReason reason, Instant at) {
        Objects.requireNonNull(accountId, "要升格的账号不能为空");
        Objects.requireNonNull(at, "升格必须带时刻 —— 不许读系统时钟");
        PersonId staged = metNotBound.get(accountId);
        if (staged == null) {
            PersonId bound = accountBindings.get(accountId);
            if (bound != null) {
                return this;
            }
            throw new IllegalArgumentException(
                    "要把 " + accountId + " 升格成认识, 但她从没见过这个账号 —— "
                            + "先调 meet(...) 记下这次见面, 再升格");
        }
        bind(accountId, staged, reason);
        // 关系说法跟上: 既然已经建立了绑定, 那她就不再是"陌生人"了。
        // 这一步不放在 bind(...) 里, 是因为 bind 也被 bootstrap 用 ——
        // 主人的关系说法由 persona 给出, 不该被这里改掉。
        Relationship relationship = relationships.get(staged);
        if (relationship != null && relationship.isStranger()) {
            relationships.put(staged, relationship.reclassified(Relationship.KIND_ACQUAINTANCE, at));
        }
        return this;
    }

    /** 她给这个人起了个名字(备注名) —— <b>这个名字不来自任何平台</b>。 */
    public RelationshipGraph name(PersonId personId, String name, Instant at) {
        mutate(personId, p -> p.named(name, at), "起名字");
        return this;
    }

    /** 换一句印象 —— 由认知环节算好了传进来。 */
    public RelationshipGraph impression(PersonId personId, String impression, Instant at) {
        mutate(personId, p -> p.withImpression(impression, at), "记下印象");
        return this;
    }

    public RelationshipGraph tag(PersonId personId, String tag, Instant at) {
        mutate(personId, p -> p.withTag(tag, at), "打标签");
        return this;
    }

    // ─────────────────────────── 关系怎么变 ───────────────────────────

    /** 关系动了一下 —— 增量式, 见 {@link Relationship#adjusted}。 */
    public RelationshipGraph adjust(PersonId personId, double closenessDelta, double trustDelta,
                                    Instant at) {
        Relationship current = requireRelationship(personId, "调整关系");
        relationships.put(personId, current.adjusted(closenessDelta, trustDelta, at));
        return this;
    }

    /** 她对这个人关系的说法变了 —— 比如从"同学"变成"朋友"。 */
    public RelationshipGraph reclassify(PersonId personId, String kind, Instant at) {
        Relationship current = requireRelationship(personId, "改写关系");
        relationships.put(personId, current.reclassified(kind, at));
        return this;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public Optional<PersonObject> person(PersonId personId) {
        return personId == null ? Optional.empty() : Optional.ofNullable(persons.get(personId));
    }

    public Optional<Relationship> relationship(PersonId personId) {
        return personId == null ? Optional.empty() : Optional.ofNullable(relationships.get(personId));
    }

    /** 她与这个账号背后那个人的关系 —— 陌生账号返回空。 */
    public Optional<Relationship> relationshipWith(ChatAccountId accountId) {
        return resolve(accountId).flatMap(p -> relationship(p.id()));
    }

    /** 她认识的所有人的身份。 */
    public Set<PersonId> personIds() {
        return Set.copyOf(persons.keySet());
    }

    public List<PersonObject> allPersons() {
        return List.copyOf(persons.values());
    }

    public List<Relationship> allRelationships() {
        return List.copyOf(relationships.values());
    }

    /** 她建立过的全部绑定(不含"只见过"的那些)。 */
    public Map<ChatAccountId, PersonId> bindings() {
        return Map.copyOf(accountBindings);
    }

    /** 见过但还没建立绑定的账号 —— "有几个陌生人在找我"。 */
    public Set<ChatAccountId> unmetAccounts() {
        return Set.copyOf(metNotBound.keySet());
    }

    /**
     * 她给这个人绑过几个账号。
     *
     * <p>它让"一个人有两个号"这件事是<b>可见且可测</b>的 —— 否则这个能力只存在于
     * 设计文档里: 没有任何调用方会去验证它, 于是第一个"改绑定"的实现会被当成等价改动。
     */
    public Set<ChatAccountId> accountsOf(PersonId personId) {
        return Set.copyOf(accountsByPerson.getOrDefault(personId, Set.of()));
    }

    /**
     * 她认识、但<b>没有任何账号</b>的人 —— §3.4.6 里"现实中的邻居"那一类。
     */
    public List<PersonObject> withoutAccount() {
        List<PersonObject> out = new ArrayList<>();
        for (PersonObject p : persons.values()) {
            if (accountsOf(p.id()).isEmpty()) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(PersonObject::firstSeenAt));
        return List.copyOf(out);
    }

    /**
     * 她还<b>叫不出名字</b>的人 —— 见过面但没有名字的那些。
     *
     * <p>这个视图是"她慢慢认识一个人"这件事的度量: 它的大小随时间下降,
     * 才说明绑定真的在建立, 而不是一开始就把所有人认识完了。
     */
    public List<PersonObject> unnamed() {
        List<PersonObject> out = new ArrayList<>();
        for (PersonObject p : persons.values()) {
            if (!p.isNamed()) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** 她心里有多少个人。 */
    public int size() {
        return persons.size();
    }

    public boolean knows(ChatAccountId accountId) {
        return accountId != null && accountBindings.containsKey(accountId);
    }

    /**
     * 她自己建立起来的绑定条数 —— §3.4.6 那个产品承诺的<b>直接度量</b>。
     *
     * <p>预置的那一条(主人)不算, 见 {@link BindReason#selfMade()}。
     * 这个方法存在的意义是让"她的通讯录是她自己长出来的"这句话<b>可以被断言</b> ——
     * 否则它只是一句写在文档里的产品愿景, 而任何一次"顺手同步一下平台好友列表"的
     * 实现都不会让它变红。
     */
    public int selfMadeBindingCount() {
        int count = 0;
        for (BindReason reason : bindReasons.values()) {
            if (reason.selfMade()) {
                count++;
            }
        }
        return count;
    }

    public String describe() {
        return "RelationshipGraph[认识 " + persons.size() + " 人, 绑定 " + accountBindings.size()
                + " 个账号, 其中还没有名字的 " + unnamed().size() + " 人]";
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private PersonId newPersonId() {
        return PersonId.of("person-" + meetSequence.incrementAndGet());
    }

    private void mutate(PersonId personId, java.util.function.UnaryOperator<PersonObject> change,
                        String what) {
        Objects.requireNonNull(personId, "要" + what + "的人不能为空");
        PersonObject person = persons.get(personId);
        if (person == null) {
            throw new IllegalArgumentException(
                    "要" + what + "的 " + personId + " 不在她心里 —— "
                            + "不认识的人不能被起名字, 也不能有印象");
        }
        persons.put(personId, change.apply(person));
    }

    private Relationship requireRelationship(PersonId personId, String what) {
        Objects.requireNonNull(personId, "要" + what + "的人不能为空");
        Relationship relationship = relationships.get(personId);
        if (relationship == null) {
            throw new IllegalArgumentException(
                    "要" + what + "的 " + personId + " 不在她心里 —— 关系总是关于某个人的");
        }
        return relationship;
    }
}
