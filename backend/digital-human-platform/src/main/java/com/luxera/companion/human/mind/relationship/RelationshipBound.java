package com.luxera.companion.human.mind.relationship;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.WorldEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.6 / §5.4.4 —— <b>她的通讯录里多了一个人</b>。
 *
 * <h2>为什么这一步值得一条事件</h2>
 * §3.4.6 结尾那句话("她的通讯录是她自己长出来的, 不是从聊天平台同步下来的")
 * 是一条<b>可验证的断言</b>, 而验证它需要的正是这条事件:
 *
 * <ul>
 *   <li>{@link BindReason#KIND_BOOTSTRAP} 的那一条是装配时给的(她生来认识 owner),
 *       它<b>不算"长出来"</b>;</li>
 *   <li>其余每一条都是一次真实的建立过程 —— 见过面、被介绍、被告知、推断出来。
 *       这些条数就是那句断言的<b>证据</b>;</li>
 *   <li>没有这条事件时, 通讯录是一张<b>当前状态的快照</b>。快照无法区分
 *       "她自己认识了 12 个人"与"平台给她同步了 12 个人" —— 两者在数据上长得一样。</li>
 * </ul>
 *
 * <h2>它为什么带 {@link #personName()}</h2>
 * 因为建立关系的那一刻, 她的备注名可能还是空的(见 {@link PersonObject#isNamed()})。
 * 事件是<b>当时</b>的记录: 带上那一刻的名字, 日后她改了备注名也不会把历史抹掉 ——
 * 这正是"事件"与"当前状态"的差别, 也是它值得单独存在的理由。
 *
 * <h2>它为什么不实现 {@code StateEffectEvent}</h2>
 * 与 {@code plan.item-scheduled.v1} / {@link com.luxera.companion.human.mind.decision.DecisionMade}
 * 同理: 它不是作用在某个通道上的持续影响, 只实现 {@link WorldEvent} 就够了 ——
 * 会被记录、会被回放、会被行为分析看到, 但不会被当成一次状态累积。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>{@code sourceObjectId} 为空</b>。绑定的发生地在她心里, 不在世界里 ——
 *       与 {@code mind.intrusive-thought.v1}、{@code mind.decision-made.v1} 同一条理由;</li>
 *   <li><b>不带 {@code closeness} 与 {@code trust}</b>。它们会随时间变, 而事件
 *       一旦写下就不该改。带上它们会诱使读者把这条事件当成"当前关系"来用,
 *       于是两个人各读一份, 版本就对不上了。</li>
 * </ul>
 *
 * @param accountId   平台侧的那个账号 —— 只是一个字符串, 见 {@link ChatAccountId}
 * @param personId    她心里的这个人
 * @param personName  绑定那一刻她的备注名, 可能为空
 * @param reason      为什么会绑定 —— 见 {@link BindReason}
 */
public record RelationshipBound(
        EventTypeId typeId,
        Instant occurredAt,
        ChatAccountId accountId,
        PersonId personId,
        String personName,
        BindReason reason) implements WorldEvent {

    /** 目录里登记的类型 —— 见 {@code CoreEventCatalog} 的 {@code mind.relationship-bound.v1}。 */
    public static final EventTypeId TYPE = EventTypeId.parse("mind.relationship-bound.v1");

    public RelationshipBound {
        Objects.requireNonNull(occurredAt, "事件必须带时刻 —— 不许读系统时钟");
        Objects.requireNonNull(accountId, "绑定必须有账号 —— 否则这条记录无法对应到任何一次相遇");
        Objects.requireNonNull(personId, "绑定必须有那个人 —— 否则这条记录说的不是'认识了谁'");
        personName = personName == null ? "" : personName.trim();
        reason = reason == null ? BindReason.bootstrap() : reason;
        typeId = typeId == null ? TYPE : typeId;
    }

    public static RelationshipBound of(Instant at, ChatAccountId accountId, PersonId personId,
                                       String personName, BindReason reason) {
        return new RelationshipBound(TYPE, at, accountId, personId, personName, reason);
    }

    /**
     * 这次绑定是不是<b>她自己建立的</b> —— 见 {@link BindReason#selfMade()}。
     *
     * <p>把它做成方法而不是让调用方去读 reason 的 kind: "算不算自己长出来的"
     * 这个判断只能有一处, 否则行为分析里会同时存在两种口径的统计。
     */
    public boolean selfMade() {
        return reason.selfMade();
    }

    /**
     * 绑定发生在她心里, <b>没有外部来源对象</b> —— 见类注释。
     */
    @Override
    public String sourceObjectId() {
        return null;
    }

    public String describe() {
        return "她把 " + accountId.describe() + " 认成了 " + personId.describe()
                + (personName.isEmpty() ? "(还没起名字)" : "(" + personName + ")")
                + " —— " + reason.describe() + (selfMade() ? " [自己长出来的]" : " [装配时给的]");
    }

    @Override
    public String toString() {
        return describe();
    }
}
