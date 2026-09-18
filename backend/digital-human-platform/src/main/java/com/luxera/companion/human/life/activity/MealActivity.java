package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>吃饭</b>。取代旧 {@code LifeActivity.type = "MEAL"}。
 *
 * <h2>它是"低注意力但高可打断"的代表</h2>
 * <pre>{@code
 * attentionDemand   = 0.25   // 吃饭不需要多少注意力
 * interruptibility  = 0.60   // 而且她乐意被打断 —— 有人这时找她聊天正合适
 * }</pre>
 *
 * <p>这两个数一起刻画了一个很典型的场景: <b>饭桌是她最容易回应别人的时候</b>。
 * 一个在写作业时不会理消息的人, 在吃饭时大概率会回。
 * 这不是因为她更闲, 而是因为这一项活动的性质允许注意力被分走而<b>没有代价</b>。
 *
 * <p>把这件事建成"吃饭这个活动的可打断性是 0.6", 而不是在代码里写
 * {@code if (currentActivity == "MEAL") replyImmediately()}, 区别在于:
 * 第三方接入的"喝咖啡"活动可以自己声明 0.55, 而平台的判断逻辑一行都不用改。
 *
 * <h2>手机可达性 0.80</h2>
 * 手机在手边（很多人吃饭时就放在桌上）。配合 0.6 的可打断性,
 * 结果是"她吃饭时消息的到达率高得多的" —— 而那是真实的, 也是可解释的。
 *
 * <h2>为什么它是重排里最容易被挪动的一项</h2>
 * 典型时长 30 分钟、地点无硬约束、而且它本身是每日固定的锚点。
 * 当一件事需要挤进日程时, 先挪吃饭是最常见的选择 —— 而那种"她今天 14:00 才吃午饭"
 * 的观察, 恰恰是行为分析里很有价值的信号。
 */
@DomainType(value = "life.activity.meal", description = "吃饭")
public final class MealActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.25, 0.60, 0.80, +0.15, Duration.ofMinutes(30), "餐桌", "坐着");

    public MealActivity(CommonFields fields) {
        super(fields);
    }

    public MealActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new MealActivity(next);
    }
}
