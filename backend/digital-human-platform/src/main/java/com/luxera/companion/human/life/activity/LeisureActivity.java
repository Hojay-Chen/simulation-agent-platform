package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>休闲</b>（看剧、刷手机、打游戏）。取代旧 {@code LifeActivity.type = "LEISURE"}。
 *
 * <h2>它是最容易被误当成"不重要"的一类活动 —— 而那不是本模型的态度</h2>
 * <pre>{@code
 * interruptibility   = 0.75   // 高, 但它不是"可以随便打断"
 * phoneAvailability  = 0.90   // 极高 —— 通常她手上拿的就是手机
 * moodEffect         = +0.25  // 恢复性的, 不是浪费
 * }</pre>
 *
 * <p>把可打断性设成 0.75 而不是 0.95, 是因为一个具体的观察:
 * <b>被打断的休闲会让人烦</b>。看剧看到关键时刻被叫走, 与开会时被叫走,
 * 代价的类型不同但都不小。如果休闲的可打断性接近 1, 她的行为会变成
 * "随时可以被任何事打断", 而那不是一个真实的人。
 *
 * <h2>为什么要给它一个真实的 +0.25 而不是 0</h2>
 * 因为休闲是<b>恢复</b>。如果休闲对心情没有正贡献, 那么一个把休息时间
 * 全部挤掉、换成"有用的事"的 agent 会在数据上看起来毫无损失 ——
 * 而真实的人不是这样: 连续工作不休息会崩。
 *
 * <p>换句话说, 这个值存在的目的是让"她需要休息"这件事在模型的算术里
 * 有一个位置。没有它, "她今天一直在干活"会被算成好的一天。
 */
@DomainType(value = "life.activity.leisure", description = "休闲")
public final class LeisureActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.30, 0.75, 0.90, +0.25, Duration.ofMinutes(45), "客厅", "靠着");

    public LeisureActivity(CommonFields fields) {
        super(fields);
    }

    public LeisureActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new LeisureActivity(next);
    }
}
