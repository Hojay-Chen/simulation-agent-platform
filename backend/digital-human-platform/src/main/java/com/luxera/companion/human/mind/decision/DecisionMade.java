package com.luxera.companion.human.mind.decision;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.WorldEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.7 / §5.4.4 —— <b>"她做过一次决定"这件事本身</b>。
 *
 * <h2>为什么"她想了想然后继续写作业"也需要一条事件</h2>
 * 因为它是"她没回那条消息"的<b>唯一可查的答案</b>。没有这条事件时, 事后能看到的只有:
 *
 * <ul>
 *   <li>一条设备事件说"手机通知响了";</li>
 *   <li>以及<b>什么都没有</b>。</li>
 * </ul>
 *
 * <p>而"什么都没有"同时意味着"她没注意到"、"她注意到了但决定不理"、
 * 以及"回消息的代码还没写"。这三件事在数据上必须能分开, 否则"她今天是不是有点冷淡"
 * 这个问题的答案只能靠人去读日志 —— 而人读日志读不出一个可统计的量。
 *
 * <h2>它为什么只带摘要, 不带内容</h2>
 * 载荷里是<b>理由的分类码与一句人话</b>, 不是"她决定说的那句话"。
 * 两个原因:
 * <ol>
 *   <li>一句话正文属于 {@link ActionIntent} 的参数, 它会被装进 {@code ActionCommand} ——
 *       同一件事记两处, 迟早不一致;</li>
 *   <li>行为分析要的是"有多少次决定"、"理由分布是什么", 而这些在摘要里就够了。
 *       把正文也带上, 只会让这张表变成第二份聊天记录。</li>
 * </ol>
 *
 * <h2>它为什么不实现 {@code StateEffectEvent}</h2>
 * 因为它<b>不是作用在某个通道上的持续影响</b>。目录里它的类别是
 * {@code STATE_EFFECT}(这是分类), 而路由是按<b>能力接口</b>做的(§5.2) ——
 * 本类只实现 {@link WorldEvent}, 于是它会被记录、会被回放、会被行为分析看到,
 * 但不会被 {@code ContinuousEffectLedger} 当成一次累积。
 *
 * <p>这与 {@code plan.item-scheduled.v1} / {@code plan.revision-created.v1}
 * 是同一种做法: 它们也都只实现 {@code WorldEvent}。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>{@code sourceObjectId} 为空</b>。这个决定是<b>她自己做的</b>, 不是某个世界对象
 *       造成的 —— 与 {@code mind.intrusive-thought.v1} 同一条理由。
 *       填上"那条消息"的 id 会让"这个决定是那条消息的直接后果"变成一个未经证明的断言;</li>
 *   <li><b>不带措辞</b>。措辞在决定之后才由语言引擎生成 —— 见 {@link LanguageEngine}。
 *       把措辞放进这条事件, 就等于承认"决定"依赖那次模型调用, 而那正是要被否定的东西。</li>
 * </ul>
 *
 * @param decisionId       这次决定的身份 —— 用它把事件、动作命令与计划版本串起来
 * @param reasonCode       理由的机读分类码, 见 {@link DecisionReason}
 * @param narrative        理由的那句人话
 * @param actionCount      这次决定要执行几个动作
 * @param planMutationCount 这次决定要改几处计划
 */
public record DecisionMade(
        EventTypeId typeId,
        Instant occurredAt,
        String decisionId,
        String reasonCode,
        String narrative,
        int actionCount,
        int planMutationCount) implements WorldEvent {

    /** 目录里登记的类型 —— 见 {@code CoreEventCatalog} 的 {@code mind.decision-made.v1}。 */
    public static final EventTypeId TYPE = EventTypeId.parse("mind.decision-made.v1");

    public DecisionMade {
        Objects.requireNonNull(occurredAt, "事件必须带时刻 —— 不许读系统时钟");
        Objects.requireNonNull(decisionId, "事件必须指明是哪一次决定 —— 否则它无法被溯源到动作");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        narrative = narrative == null ? "" : narrative.trim();
        if (actionCount < 0 || planMutationCount < 0) {
            throw new IllegalArgumentException(
                    "动作数与计划改动数不能为负(动作 " + actionCount + ", 计划改动 " + planMutationCount
                            + ") —— 负数会让统计里出现无法解释的条目");
        }
        typeId = typeId == null ? TYPE : typeId;
    }

    public static DecisionMade from(Decision decision, Instant at) {
        Objects.requireNonNull(decision, "要记录的决定不能为空");
        return new DecisionMade(TYPE, at, decision.id().value(), decision.reason().code(),
                decision.reason().narrative(), decision.actions().size(),
                decision.planMutations().size());
    }

    /**
     * 她自己做的决定, <b>没有外部来源</b> —— 见类注释。
     *
     * <p>返回 {@code null} 而不是空串: 空串在别处表示"有一个人但名字是空的",
     * 两者含义不同。
     */
    @Override
    public String sourceObjectId() {
        return null;
    }

    public String describe() {
        return "她做了决定 " + decisionId + " [" + reasonCode + "] " + narrative
                + " (动作 " + actionCount + " 个, 计划改动 " + planMutationCount + " 处)";
    }

    @Override
    public String toString() {
        return describe();
    }
}
