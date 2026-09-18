package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>运动</b>。取代旧 {@code LifeActivity.type = "EXERCISE"}。
 *
 * <p>用户描述里的"去运动"就是这一类: 他觉得写作业被打断之后,
 * 她可能"完全不再继续写作业, 把写作业直接从计划表删掉, 然后插入去运动的计划 event"。
 *
 * <h2>档案里最突出的是情绪影响 +0.35</h2>
 * 这是正向里最高的一档。它的用途不是"让她开心"这么简单, 而是让一个真实的行为
 * 有机会发生: <b>当她心情差的时候, 去运动是一个会让她变好的选择</b> ——
 * 而要做到这一点, 规划器必须能"看到"这件事对心情有帮助。
 *
 * <p>这也是为什么 {@code moodEffect} 是活动的属性而不是计划项的属性:
 * 一件事对心情的影响取决于事情本身（跑步 vs 加班）, 不取决于它被排在哪。
 *
 * <h2>可打断性 0.40 —— 一个"中间值"的意义</h2>
 * 运动不像开会那样一打断就崩, 也不像吃饭那样随便打断。
 * 它的中间值让"跑完这组再回消息"这类行为成为可能: Mind 可以判断
 * "这个刺激值得她中断运动吗", 而答案取决于刺激本身有多急。
 * <b>一个所有活动都取 0.5 的实现会让这个判断永远退化成"看急了"</b> ——
 * 有了真实的差异, 她的行为才会有真实的取舍。
 *
 * <h2>手机可达性 0.30</h2>
 * 跑步时手机在臂包或柜子里 —— 够不太着, 但不是完全拿不到。
 * 这个值会让"她运动时漏掉了一些消息"成为一件可解释的事, 而不是一个 bug。
 */
@DomainType(value = "life.activity.exercise", description = "运动")
public final class ExerciseActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.45, 0.40, 0.30, +0.35, Duration.ofHours(1), "健身房", "走动");

    public ExerciseActivity(CommonFields fields) {
        super(fields);
    }

    public ExerciseActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new ExerciseActivity(next);
    }
}
