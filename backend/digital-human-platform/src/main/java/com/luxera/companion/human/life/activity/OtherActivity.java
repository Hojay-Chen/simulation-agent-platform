package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.registry.DomainType;

import java.time.Instant;

/**
 * V2.2 §8.4 —— <b>其他</b>。取代旧 {@code LifeActivity.type = "OTHER"}。
 *
 * <h2>它是本次迁移里唯一一个"没有档案"的实现, 而这需要解释</h2>
 * 前面十一个活动各自声明了一组具体的数。本类直接用
 * {@link ActivityProfile#neutral()} —— 一组中庸的默认值。
 * 看起来它就是一个"什么都没有"的兜底, 而兜底正是设计文档要消灭的东西。
 *
 * <h2>它为什么仍然需要存在</h2>
 * 因为迁移必须无损。旧的 {@code life_activities} 表里确实存在
 * {@code type = "OTHER"} 的行, 而它们是<b>真实发生过的事</b> ——
 * 那些行不能因为新模型没有对应的类而被丢掉, 也不能被随便塞进某个
 * 具体活动里（把"其他"当成"休息"会让她的休息时间凭空多出一截）。
 *
 * <h2>它与"枚举的 OTHER"的区别 —— 这是关键</h2>
 * <table border="1">
 *   <tr><th></th><th>枚举里的 {@code OTHER}</th><th>本类</th></tr>
 *   <tr><td>它是什么</td>
 *       <td>一个<b>终点</b>: 所有归不进去的东西都塞进这里, 而它们从此没有区别</td>
 *       <td>一个<b>过渡态</b>: 它明确标着"这一类还没被建模"</td></tr>
 *   <tr><td>第三方新增活动时</td>
 *       <td>往枚举里加一个常量 —— 而枚举在平台源码里, 加不了</td>
 *       <td>新写一个类, 注册类型名。已经存在的 {@code OtherActivity} 记录不受影响</td></tr>
 *   <tr><td>诊断</td>
 *       <td>看不到有多少东西落在里面</td>
 *       <td>{@link #NEUTRAL} 的中庸档案让这些记录在行为统计里表现为
 *           "不好不坏的一段", 而它们的数量本身就是一个可查的指标 ——
 *           数量高说明有一类活动该被建模了</td></tr>
 * </table>
 *
 * <p>所以本类的定位不是"垃圾桶", 而是<b>一个有明确含义的兜底</b>:
 * "她做了一件我们还不知道该怎么形容的事"。它有主, 有描述
 * （来自 {@link PlanIntent}, 那才是真正的信息载体）, 只是没有专门的档案。
 */
@DomainType(value = "life.activity.other", description = "其他（尚未建模的活动）")
public final class OtherActivity extends AbstractActivity {

    /** 中庸档案 —— 说不上专注也说不上分心。 */
    public static final ActivityProfile NEUTRAL = ActivityProfile.neutral();

    public OtherActivity(CommonFields fields) {
        super(fields);
    }

    public OtherActivity(PlanIntent intent, Instant startedAt, PlanItemId planItemId) {
        super(intent, startedAt, planItemId);
    }

    @Override
    protected ActivityProfile activityProfile() {
        return NEUTRAL;
    }

    @Override
    protected AbstractActivity create(CommonFields next) {
        return new OtherActivity(next);
    }
}
