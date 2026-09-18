package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>爱好</b>（画画、弹琴、做手工、写点自己的东西）。
 * 取代旧 {@code LifeActivity.type = "HOBBY"}。
 *
 * <h2>为什么它必须与 {@link LeisureActivity} 分开</h2>
 * 两者都"让人高兴", 但它们的档案在<b>注意力</b>和<b>可打断性</b>上几乎相反:
 *
 * <table border="1">
 *   <tr><th></th><th>休闲</th><th>爱好</th></tr>
 *   <tr><td>注意力</td><td>0.30 —— 看剧是消遣, 走神也没关系</td>
 *       <td>0.70 —— 画画弹琴需要真的投入</td></tr>
 *   <tr><td>可打断性</td><td>0.75</td><td>0.30</td></tr>
 *   <tr><td>手机可达性</td><td>0.90</td><td>0.40</td></tr>
 * </table>
 *
 * <p>把它们合并成一个"娱乐"类别, 会让一个正在弹琴的人与一个正在刷剧的人
 * 在模型里行为完全一样 —— 而前者被打断会烦, 后者被打断就打断吧。
 * 这正是"用枚举把不同的东西压成同一个"的典型代价。
 *
 * <h2>它是最容易被长期挤掉的一类, 而 {@code moodEffect +0.30} 是对它的保护</h2>
 * 爱好通常"没用" —— 它不产出成果、不推进任何目标。一个只看目标达成度的
 * 规划器会系统性地把它挤到日程之外, 而每一步挤压都局部合理。
 *
 * <p>把它建成一个有 +0.30 心情贡献的活动, 是让"她有爱好"这件事
 * 在算术上有一席之地。这也回答了用户要的那种真实感:
 * <b>她的日程里得有她自己想做的事, 而不只有应该做的事。</b>
 */
@DomainType(value = "life.activity.hobby", description = "爱好")
public final class HobbyActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.70, 0.30, 0.40, +0.30, Duration.ofHours(1), "书桌", "坐着");

    public HobbyActivity(CommonFields fields) {
        super(fields);
    }

    public HobbyActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new HobbyActivity(next);
    }
}
