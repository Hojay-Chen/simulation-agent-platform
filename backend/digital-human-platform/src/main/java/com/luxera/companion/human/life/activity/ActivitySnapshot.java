package com.luxera.companion.human.life.activity;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §7.2 —— <b>一次执行从数据库读回来时, 需要交回给领域的全部字段</b>。
 *
 * <h2>为什么需要这个类型, 而不是直接把 {@code CommonFields} 公开出去</h2>
 * {@link AbstractActivity.CommonFields} 是 {@code protected} 的嵌套 record,
 * 于是 {@code com.luxera.companion.persistence} 那个包<b>物理上看不见它</b> ——
 * 这是刻意设计的边界（活动是领域对象, 它的内部形状不该被持久化层直接操作）。
 *
 * <p>但"看不见"与"不需要"是两件事: 把一条 {@code activity_record} 读回来之后,
 * 要交回领域的正是那八个字段。于是需要一个<b>同包内的公开中转类型</b> ——
 * 就是本 record。它不是 {@code CommonFields} 的第二份定义, 而是它的
 * <b>公开投影</b>: 字段一一对应, 没有任何新增或改写。
 *
 * <h2>为什么这个 record 在 {@code human/life/activity} 包里, 而不是在持久化包里</h2>
 * 因为它是<b>领域的输入</b>, 不是持久化的输出。定义在持久化包里的话,
 * 领域层就要 import 持久化包才能声明"我能从这八个字段恢复" ——
 * 而那会让"领域不知道数据库"这句话失效, 且正好把 §8.4 的包结构反过来。
 *
 * <h2>它<b>不是</b> {@code Activity} 的替身</h2>
 * 一个常见的误用是把它当成"轻量的 Activity"传进业务逻辑。它没有行为 ——
 * 没有 {@code progressAt}、没有 {@code interruptibility}、没有档案。
 * 它的唯一合法用途是把字段交给 {@link ActivityFactory#restore}, 换回一个
 * <b>真正的</b> {@code Activity} 实例。
 *
 * @param id            执行 id —— {@code act-N}。<b>必须是原来那个</b>:
 *                      新生成一个 id 会让"这条活动的后续事件"引用一个不存在的执行
 * @param intent        她当时想的是什么。它决定恢复出来的活动是哪一类
 *                      （见 {@link ActivityFactory#restore(String, ActivitySnapshot)}）
 * @param planItemId    它实现的是计划表上的哪一项。<b>可以为空</b> ——
 *                      计划表外的事是真实的（见 {@link Activity} 的说明）
 * @param startedAt     开始时刻（仿真时刻）
 * @param state         状态。{@code RUNNING} 表示她重启时正在做这件事
 * @param endedAt       结束时刻。进行中时为 {@code null}
 * @param closingNote   收尾时的一句话。进行中时为 {@code null}
 * @param finalProgress 收尾时记下的进度。<b>{@code null} 与 {@code 0.0} 不是一回事</b>
 *                      （0.0 是"她一点没做就结束了"）, 所以它是 {@code Double} 而不是
 *                      {@code double} —— 见 {@code CommonFields} 的同一条说明
 */
public record ActivitySnapshot(
        ActivityId id,
        PlanIntent intent,
        PlanItemId planItemId,
        Instant startedAt,
        ActivityState state,
        Instant endedAt,
        String closingNote,
        Double finalProgress) {

    public ActivitySnapshot {
        Objects.requireNonNull(id, "恢复一次执行必须带原来的 id —— "
                + "新生成一个 id 会让指向它的后续事件全部悬空");
        Objects.requireNonNull(intent, "恢复一次执行必须带意图 —— 它是'她在做什么'的全部内容");
        Objects.requireNonNull(startedAt, "开始时刻不能为空");
        Objects.requireNonNull(state, "状态不能为空");
        // 其余的一致性约束(已结束必须有结束时刻、放弃必须有理由)由 CommonFields
        // 的规范构造器统一把关 —— 这里**不重复一遍**。
        // 重复会造出两个判据, 而它们迟早会不一致: 一处放宽、一处收紧的那天,
        // "为什么测试里能存进去的生产上起不来"会成为一个没有答案的问题。
    }

    /** 她重启时正在做这件事吗。 */
    public boolean running() {
        return state.ongoing();
    }

    public String describe() {
        return id.value() + " " + intent.activityType() + " " + state
                + " @ " + startedAt + (endedAt == null ? "" : "→" + endedAt);
    }
}
