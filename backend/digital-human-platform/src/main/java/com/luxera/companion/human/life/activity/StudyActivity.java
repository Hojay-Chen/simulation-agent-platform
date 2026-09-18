package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>学习/写作业</b>。取代旧 {@code LifeActivity.type = "STUDY"}。
 *
 * <p>这就是用户描述里那件事: "比如有一个写作业的 event, 安排在 12:00 持续 1 小时"。
 *
 * <h2>为什么它与 {@link WorkActivity} 是两个类, 而不是一个类加个标志位</h2>
 * 它们的档案确实接近（都很专注、都怕被打断）, 但有三处真实的差别:
 * <table border="1">
 *   <tr><th></th><th>学习</th><th>工作</th></tr>
 *   <tr><td>可打断性</td><td>0.25 —— 作业做错一题的代价是她的, 分心一会儿还能补回来</td>
 *       <td>0.15 —— 会议里走神、线上出故障, 代价落在别人身上, 补不回来</td></tr>
 *   <tr><td>典型时长</td><td>1 小时 —— 一门课的作业是一段一段的</td>
 *       <td>4 小时 —— 一个工作日是一整块</td></tr>
 *   <tr><td>情绪影响</td><td>+0.05 —— 做完有成就感, 过程略苦, 净值为微正</td>
 *       <td>-0.05 —— 净值为微负</td></tr>
 * </table>
 *
 * <p>这些差别会真实地改变她的行为: 一个正在工作的人比一个正在写作业的人
 * <b>更不容易</b>被消息拉走, 而两者被拉走之后的心情变化方向相反。
 * 用标志位区分的写法会把这三种差异都压成一个 boolean, 而 boolean 无法承载
 * "差别有多大"这个问题。
 *
 * <h2>手机可达性 0.45</h2>
 * 手机就在桌上, 但她的注意力在作业上 —— <b>这是"够得着但没看", 不是"够不着"</b>。
 * 这个区分很重要: 它意味着一条通知<b>可以</b>被她注意到（而手机在包里时不行）,
 * 于是"她没回消息"在这里应当被归因成"她在专注", 而不是"她拿不到手机"。
 */
@DomainType(value = "life.activity.study", description = "学习/写作业")
public final class StudyActivity extends AbstractActivity {

    public static final ActivityProfile PROFILE = new ActivityProfile(
            0.80, 0.25, 0.45, +0.05, Duration.ofHours(1), "书桌", "坐着");

    public StudyActivity(CommonFields fields) {
        super(fields);
    }

    public StudyActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return PROFILE;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new StudyActivity(next);
    }
}
