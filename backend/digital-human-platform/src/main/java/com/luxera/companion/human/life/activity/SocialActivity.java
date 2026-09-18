package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>社交</b>（见朋友、聚餐、打电话聊天）。取代旧 {@code LifeActivity.type = "SOCIAL"}。
 *
 * <h2>它有一个别的活动都没有的性质: 注意力被"别人"占着</h2>
 * <pre>{@code
 * attentionDemand   = 0.55   // 中等偏高 —— 但这份注意力是给对面那个人的
 * interruptibility  = 0.35   // 低 —— 中途看手机是失礼的
 * }</pre>
 *
 * <p>低可打断性在这里的含义与 {@link WorkActivity} 不同: 工作时的低值来自
 * <b>重新进入状态的代价</b>, 社交时的低值来自<b>对面那个人在场</b>。
 * 这个差别必须能被表达出来, 因为它带来一个真实的后果:
 * 她在社交时没回消息, 与她在工作时没回消息, <b>该给的解释完全不同</b> ——
 * 前者是"她正和朋友在一起", 后者是"她在忙"。
 *
 * <p>而这两句话在人格上留下的印象差别很大。如果两种情形都只用一个
 * "她在忙"来概括, 她的行为在行为分析里就会丢掉一整个维度。
 *
 * <h2>手机可达性 0.55</h2>
 * 手机在包里或扣在桌上 —— 不是拿不到, 是不方便拿。
 * 这个值让"她看到了但没回"成为可能, 而不是"她没看到"。
 * 这两种归因的差别, 正是这套模型最想区分的东西之一。
 */
@DomainType(value = "life.activity.social", description = "社交")
public final class SocialActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.55, 0.35, 0.55, +0.25, Duration.ofMinutes(90), "外面", "坐着");

    public SocialActivity(CommonFields fields) {
        super(fields);
    }

    public SocialActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new SocialActivity(next);
    }
}
