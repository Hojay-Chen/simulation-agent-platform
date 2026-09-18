package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>家务</b>（做饭、打扫、洗衣）。取代旧 {@code LifeActivity.type = "HOUSEWORK"}。
 *
 * <h2>它是"低了也不会有人发现"的那一类活动 —— 所以更需要被建出来</h2>
 * <pre>{@code
 * moodEffect = -0.10   // 微负: 家务是义务, 不是享受
 * }</pre>
 *
 * <p>家务是最容易被一个"优化日程"的规划器挤掉的东西: 它不紧急、不产生可见成果、
 * 而且推迟一天没有明显代价。而它累积起来的后果是真实的 ——
 * 一周不做饭的 agent 与每天做饭的 agent 过的是两种生活。
 *
 * <p>把它建成一个有明确档案的活动（而不是归进"其他"）, 是为了让规划器
 * 在压缩日程时能"看到"它, 并且能算出挤掉它的代价。这正是
 * 用户要求的"她的一天要像真人的一天"在细节上的落点。
 *
 * <h2>可打断性 0.65 与"被打断也不亏"</h2>
 * 家务的打断代价低, 因为它随时可以捡起来（衣服洗完再晾也一样）。
 * 但注意这里仍然没有"剩余时长"这个字段 —— "随时可以捡起来"是<b>活动的性质</b>
 * （由 0.65 这个值表达）, 而不是一个被存储的续做游标。
 * 她重新开始晾衣服时是一条新的 {@link Activity}, 只是那条新活动恰好也很容易被再次打断。
 *
 * <h2>地点固定在家里</h2>
 * 这会让"她 15:00 在外面, 却被排了一项家务"这种安排被校验拦下 ——
 * 而那种错误恰恰是 LLM 提议计划时最常见的错误之一。
 */
@DomainType(value = "life.activity.housework", description = "家务")
public final class HouseworkActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.35, 0.65, 0.70, -0.10, Duration.ofMinutes(40), "家里", "走动");

    public HouseworkActivity(CommonFields fields) {
        super(fields);
    }

    public HouseworkActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new HouseworkActivity(next);
    }
}
