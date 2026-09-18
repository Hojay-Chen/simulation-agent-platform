package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>休息/发呆</b>（什么都不做、放空、午休）。
 * 取代旧 {@code LifeActivity.type = "REST"}。
 *
 * <h2>它是全部十二种里可打断性最高的一档: 0.85</h2>
 * 发呆时被打断几乎没有任何代价 —— 这正是它存在的意义:
 * 它让"她此刻可以随时被任何事叫走"这件事有一个合法的表示。
 *
 * <h2>为什么"什么都不做"需要被建成一个活动</h2>
 * 一个空白的时间段在数据上有两种完全不同的读法:
 * <ul>
 *   <li><b>她在休息</b> —— 正常, 甚至是必要的;</li>
 *   <li><b>调度器出错了, 什么都没排上</b> —— 故障。</li>
 * </ul>
 *
 * <p>如果模型里没有"休息"这个活动, 这两种情形在数据里长得一模一样,
 * 而它们需要完全不同的反应: 前者不该被任何人打扰, 后者要有人去查日志。
 * <b>把"什么都不做"显式地建成一件事, 是让它与"什么都没发生"区分开的唯一办法。</b>
 *
 * <p>这也直接对应用户对"她的一天要像真人"的要求: 真人的日程里
 * 有大量看起来"什么也没干"的时段, 而那些时段不是空的。
 *
 * <h2>注意力 0.05 与感官刺激的关系</h2>
 * 注意力极低, 但<b>这不等于她对世界关闭了</b> —— 一条突然的响声、
 * 一阵臭味在她发呆时反而更容易被注意到（因为没有别的事在竞争注意力）。
 * 这个区别由 Mind 的注意力模型处理（见 §3.4）, 本档案只负责说明
 * "这件事本身不占用她的注意力"。
 */
@DomainType(value = "life.activity.rest", description = "休息/发呆")
public final class RestActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.05, 0.85, 0.75, +0.10, Duration.ofMinutes(20), "", "靠着");

    public RestActivity(CommonFields fields) {
        super(fields);
    }

    public RestActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new RestActivity(next);
    }
}
