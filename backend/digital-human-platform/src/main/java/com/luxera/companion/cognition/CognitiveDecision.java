package com.luxera.companion.cognition;

import com.luxera.companion.digitalhuman.decision.PersonDecision;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.pipeline.MessagePipeline;

import java.time.LocalDateTime;

/**
 * V11 §12.1 —— <b>一次认知的结论</b>。
 *
 * <p>{@code {"decision": "DEFER", "reason": "currently_busy", "nextWakeupAt": "..."}}
 * 就是本类。设计文档 §12.1 的原话是"Agent 每次 Cognition 后必须产生 decision,
 * 而不是 response" —— 本类是那句话的落地。
 *
 * <h2>为什么 {@code reason} 是字符串而不是枚举</h2>
 * 因为理由的集合是<b>开放</b>的, 而行动的集合是<b>封闭</b>的。
 * "为什么押后"可以是 currently_busy / sleeping / not_noticed / low_value /
 * reality_conflict / state_version_conflict ……这个清单会随着认知变聪明而变长;
 * 而"她能做什么"是一份产品决定, 不该因为多了一个理由就改枚举。
 * 把理由做成枚举的后果很具体: 要么枚举被撑到几十个值(那时它已经不是枚举了),
 * 要么新理由被硬塞进一个意思相近的旧值里 —— 于是日志开始说谎。
 *
 * <h2>{@code nextWakeupAt} 只有一部分决策有</h2>
 * 它是"这个决策什么时候该被重新考虑", 不是"日程"。{@link DecisionType#REPLY} 没有
 * (回了就了结了), {@link DecisionType#DEFER} 必须有 —— 一个没有复查时刻的 DEFER
 * 与"永远不回"是同一件事, 而它看上去像是"待会儿就回"。所以
 * {@link #needsWakeup()} 把这条不变量变成一个可以被断言的方法。
 */
public record CognitiveDecision(DecisionType type,
                                String reason,
                                LocalDateTime nextWakeupAt,
                                double confidence) {

    public CognitiveDecision {
        if (type == null) {
            throw new IllegalArgumentException("决策不能没有类型 —— 没有类型的决策不是'默认'，是'不知道'");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("决策必须说明理由 —— 否则日志里只剩一个动词，无法归因");
        }
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public static CognitiveDecision of(DecisionType type, String reason) {
        return new CognitiveDecision(type, reason, null, 1.0);
    }

    /** 押后 / 等待类决策: 必须带上"什么时候再看一眼"。 */
    public static CognitiveDecision until(DecisionType type, String reason, LocalDateTime nextWakeupAt) {
        return new CognitiveDecision(type, reason, nextWakeupAt, 1.0);
    }

    /** "以后再说"类的决策必须有一个复查时刻, 否则它与静默丢消息无法区分。 */
    public boolean needsWakeup() {
        return type.needsFollowUp() && nextWakeupAt == null;
    }

    // ─────────────────────── 老链 → 新词表(单向) ───────────────────────

    /**
     * V10 的 {@code PipelineResult.Outcome} → 本类。
     *
     * <p>这个映射<b>有损</b>, 而且是刻意有损的: {@code IGNORE} 与 {@code IGNORE_NOT_NOTICED}
     * 在老链里是两种结果, 在新词表里分别是 {@link DecisionType#OBSERVE}(看到了, 不介入)
     * 与 {@link DecisionType#DO_NOTHING}(压根没看到)。它们看起来像同义词, 实际上
     * 一个是"她无视了你", 一个是"她根本不知道" —— 这正是 V11 要把它俩分开的原因。
     *
     * <p>反过来(新 → 老)没有映射, 也不该有: 新词表里有 READ_MESSAGES / THINK /
     * INITIATE_CONVERSATION 三个老链不存在的动作, 硬映射回去只能丢信息。
     */
    public static CognitiveDecision from(MessagePipeline.PipelineResult.Outcome outcome, String reason) {
        if (outcome == null) {
            return of(DecisionType.DO_NOTHING, reason == null ? "no_outcome" : reason);
        }
        return switch (outcome) {
            case IGNORE_NOT_NOTICED -> of(DecisionType.DO_NOTHING,
                    reason == null ? "not_noticed" : reason);
            case IGNORE -> of(DecisionType.OBSERVE,
                    reason == null ? "chose_not_to_engage" : reason);
            case DEFERRED -> until(DecisionType.DEFER,
                    reason == null ? "deferred" : reason, null);
            case REPLY -> of(DecisionType.REPLY, reason == null ? "reply" : reason);
        };
    }

    /**
     * V10 的 {@code BrainDecision.action()} → 本类。
     *
     * <p>{@code CHECK_PHONE_FIRST} 映射成 {@link DecisionType#READ_MESSAGES}: 老链把它当
     * "先打开手机再看一眼"的一个中间步骤, 而 V11 词表里"读"本身就是一个动作,
     * 不再是一个需要二次决策的分支。
     */
    public static CognitiveDecision fromBrain(String action, String reason) {
        if (action == null) {
            return of(DecisionType.DO_NOTHING, "no_brain_decision");
        }
        return switch (action) {
            case BrainDecision.REPLY, BrainDecision.SHORT_ACK, BrainDecision.END_CONVERSATION ->
                    of(DecisionType.REPLY, reason == null ? "brain_" + action.toLowerCase() : reason);
            case BrainDecision.CHECK_PHONE_FIRST -> of(DecisionType.READ_MESSAGES, "check_phone_first");
            case BrainDecision.READ_NO_REPLY -> of(DecisionType.DEFER, "read_no_reply");
            case BrainDecision.IGNORE -> of(DecisionType.OBSERVE, "brain_ignore");
            default -> of(DecisionType.OBSERVE, "unknown_brain_action:" + action);
        };
    }

    /**
     * V10 的 sealed {@code PersonDecision} → 本类。
     *
     * <p>本工程是 JDK 17, 而 switch 的模式匹配在 17 上还是预览特性 —— 所以这里用
     * {@code instanceof} 链。这不是风格选择: 用 switch 版本会要求全工程开
     * {@code --enable-preview}, 而那会让 class 文件绑死在一个 JDK 小版本上。
     *
     * <p>只有 {@code DelayReplyDecision} 自带延迟分钟数, 所以只有它能算出一个真的复查时刻;
     * 其余"待会儿再说"的老决策没有这个信息。{@code now} 为空时它退化成
     * {@code needsWakeup()==true} —— 这是<b>诚实的信号</b>: 老链的 DEFER 到今天也没有
     * 接进 V11 的唤醒系统(那是 Phase 5 的事), 与其编一个假时刻, 不如让它显形。
     */
    public static CognitiveDecision from(PersonDecision decision, LocalDateTime now) {
        if (decision == null) {
            return of(DecisionType.DO_NOTHING, "no_person_decision");
        }
        if (decision instanceof PersonDecision.IgnoreDecision d) {
            return of(DecisionType.OBSERVE, d.reason());
        }
        if (decision instanceof PersonDecision.InspectDeviceDecision d) {
            return of(DecisionType.READ_MESSAGES, d.reason());
        }
        if (decision instanceof PersonDecision.ReplyDecision d) {
            return of(DecisionType.REPLY, d.reason());
        }
        if (decision instanceof PersonDecision.DelayReplyDecision d) {
            return until(DecisionType.DEFER, d.reason(),
                    now == null ? null : now.plusMinutes(d.delayMinutes()));
        }
        if (decision instanceof PersonDecision.ChangeActivityDecision d) {
            return of(DecisionType.PERFORM_ACTION, d.reason());
        }
        // 走到这里说明 sealed 接口加了新实现而本适配器没跟上。不抛异常: 适配器是并跑期
        // 的观察设施, 它自己炸掉会把被观察的那条主链一起带走。
        return of(DecisionType.OBSERVE, "unmapped_person_decision:" + decision.getClass().getSimpleName());
    }

    /** 不带时刻的便捷重载(见 {@link #from(PersonDecision, LocalDateTime)} 对复查时刻的说明)。 */
    public static CognitiveDecision from(PersonDecision decision) {
        return from(decision, null);
    }
}
