package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>睡觉</b>。取代旧 {@code LifeActivity.type = "SLEEP"}。
 *
 * <h2>它的档案里最值得看的是那一对"矛盾"的数</h2>
 * <pre>{@code
 * attentionDemand  = 0.02   // 注意力几乎为零, 她基本没有清醒的意识
 * interruptibility = 0.35   // 但打断她的代价并不低
 * }</pre>
 *
 * <p>这两个数放在一起容易被误读成"因为注意力低, 所以打断她很便宜" —— 恰恰相反。
 * 一个 0.02 的注意力说明<b>她此刻不在</b>, 而 <b>0.35 的可打断性说的是</b>:
 * 闹钟和电话确实能把她叫醒（所以她不是"完全打不断"）, 但代价不是"分心几分钟",
 * 而是<b>废掉她接下来的一整天</b>。
 *
 * <p>把这两个概念拆成两个数, 是为了让 Mind 能做出正确的反应:
 * "她被叫醒了"和"她被叫醒之后心情很差、注意力涣散"是两件事,
 * 而它们分别由 {@code interruptibility} 和 {@code moodEffect} 影响。
 * 如果只有一个"重要程度"字段, 这两种后果会被迫挤在一起。
 *
 * <h2>手机可达性 0.05</h2>
 * 睡着了手机不在手上。这个低值会让她"想查看手机"这个动作需要先跨过一道物理障碍
 * （起床、找到手机）。它是"手机就在枕头边但没看"与"手机在客厅充电"的分界 ——
 * 后者应当让查看动作失败, 而失败的原因要能被她自己理解。
 */
@DomainType(value = "life.activity.sleep", description = "睡觉")
public final class SleepActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.02, 0.35, 0.05, +0.30, Duration.ofHours(7), "卧室", "躺着");

    public SleepActivity(CommonFields fields) {
        super(fields);
    }

    public SleepActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new SleepActivity(next);
    }
}
