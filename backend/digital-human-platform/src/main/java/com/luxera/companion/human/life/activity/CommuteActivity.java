package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>通勤</b>。取代旧 {@code LifeActivity.type = "COMMUTE"}。
 *
 * <h2>它是全部十二种里"手机可达性最高"的一档: 0.85</h2>
 * 地铁上、公交上、等车时 —— 手机就在手里。这个值配合 0.15 的注意力,
 * 刻画了一个真实且重要的窗口: <b>她在路上, 什么正事都做不了, 但完全够得着手机</b>。
 *
 * <p>这个窗口的行为后果很具体: 一条在她工作时不会理的消息,
 * 在她通勤时大概率会被回。而她自己也倾向于<b>把想做的事攒到路上做</b> ——
 * 于是通勤时段会自然地吸引那些"碎片化的、需要手机"的计划项。
 *
 * <h2>为什么情绪影响是 -0.05（微负）</h2>
 * 通勤被普遍认为是消耗, 但不像加班那样明显。这个微负值累积一整天之后
 * 会让"她今天心情偏低"有一个可解释的来源 —— 而不是一个凭空的数值波动。
 *
 * <h2>地点是"路上"而不是某个具体地方</h2>
 * 这一点影响时间安排的合法性: 一项要求"在厨房"的活动不能在通勤时段被排进来,
 * 而"听播客"这类不挑地点的事可以。地点用字符串而不是枚举,
 * 因为第三方会带来"瑜伽馆""自习室"这类平台无从预知的场所。
 */
@DomainType(value = "life.activity.commute", description = "通勤")
public final class CommuteActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.15, 0.70, 0.85, -0.05, Duration.ofMinutes(40), "路上", "走动");

    public CommuteActivity(CommonFields fields) {
        super(fields);
    }

    public CommuteActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new CommuteActivity(next);
    }
}
