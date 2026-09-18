package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>工作</b>。取代旧 {@code LifeActivity.type = "WORK"}。
 *
 * <h2>档案里最重要的一条: 可打断性 0.15</h2>
 * 这是全部十二种活动里最低的一档（只有"开会"这类场景比它更低）。
 * 它意味着: 一条消息要打断她, Mind 需要给出一个<b>明显更高</b>的理由 ——
 * 一条"今天中午吃什么"的消息不该让她放下手上的活, 而"她妈妈连打了三个电话"应该。
 *
 * <p>这个值不是"她不想被打扰"的意愿表达, 而是<b>打断的代价</b>:
 * 从专注状态被拉出来再回去, 通常要花十几分钟。所以它是一个物理量,
 * 不是心情。用户要求的"感官实时 event 必须立即让 Mind 处理并决定如何反应"
 * 与这个值不冲突 —— 事件永远会被 Mind 看到, 本值只影响 Mind 的决策。
 *
 * <h2>为什么典型时长是 4 小时</h2>
 * 一个工作日是一整块, 而不是一串 30 分钟的小段。这直接影响重排的形状:
 * 当中午觉得冷要加衣服时, "把工作顺延 10 分钟"与"把工作从上午挪到下午"
 * 是两个完全不同的改动, 而它们的可行性取决于这一项有多长 —— 一个 4 小时的块
 * 一旦挪动就会撞到别的安排, 而 30 分钟的块不会。
 */
@DomainType(value = "life.activity.work", description = "工作")
public final class WorkActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.85, 0.15, 0.35, -0.05, Duration.ofHours(4), "工位", "坐着");

    public WorkActivity(CommonFields fields) {
        super(fields);
    }

    public WorkActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new WorkActivity(next);
    }
}
