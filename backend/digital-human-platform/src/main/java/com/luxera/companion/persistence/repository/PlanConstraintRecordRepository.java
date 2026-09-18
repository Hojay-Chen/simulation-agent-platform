package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.PlanConstraintRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2.2 §7.2 —— {@code plan_constraint} 的读口。
 *
 * <h2>两个查询, 对应两种归属</h2>
 * 见 {@link PlanConstraintRecord} 的类注释: {@code plan_item_id} 为空表示"这一版的
 * 全局约束", 非空表示"某一项自己的约束"。于是读约束的正确做法是<b>两个查询</b>:
 * <pre>
 *   全局:  findByRevisionIdAndPlanItemIdIsNull   →  idx_plan_constraint_revision
 *   项上:  findByRevisionIdAndPlanItemId         →  idx_plan_constraint_revision（前缀）
 * </pre>
 *
 * <h2>为什么<b>没有</b>一个"把这一版所有约束一起查出来"的方法</h2>
 * 那种写法（{@code findByRevisionId} —— 不带 {@code planItemIdIsNull}）看起来更省事,
 * 但它把两种归属混在了一起, 于是调用方必须<b>自己</b>再判一次"这条是全局的还是某项的"。
 * 判两次的地方就有两次判错的机会, 而判错的后果很具体: 全局约束被当成项约束,
 * 于是"考试周不许娱乐"只在某一项上生效 —— 表现为"她考试周还在打游戏"。
 *
 * <h2>一处<b>被推翻的</b>旧判断: "项的 id 在全库唯一"</h2>
 * 这个接口曾经基于"重排产生新 id, 所以 {@code planItemId} 单独就足以定位"而只提供
 * {@code findByPlanItemId}。那个前提<b>是错的</b>: {@code PlanBoard.apply} 只给
 * <b>被改动碰过</b>的项换新 id, 没被碰过的项原样带进下一版 —— 于是
 * {@code item-3} 会同时存在于 rev-17、rev-18、rev-19 里。
 * 按 {@code planItemId} 单独查会返回<b>三个版本的行</b>, 而调用方
 * （{@code PlanStore.assemble}）当时用一行"读到别的版本就跳过"的过滤兜住了它 ——
 * 那个兜底能工作, 但它把一次本可以走索引的等值查询变成了"把这一项的全部历史读回来再丢"。
 * 现在两个列都带, 查询落在 {@code idx_plan_constraint_revision} 上。
 *
 * <p>保留 {@link #findByPlanItemId} 是因为它服务的<b>不是</b>恢复, 而是
 * "这一项的历史上都有哪些约束"这个分析问题 —— 那一问要的就是全部版本。
 */
public interface PlanConstraintRecordRepository extends JpaRepository<PlanConstraintRecord, String> {

    /**
     * 这一版的<b>全局</b>约束 —— 走 {@code idx_plan_constraint_revision}。
     *
     * <p>关键在 {@code PlanItemIdIsNull} 这个后缀。Spring Data 会把
     * {@code PlanItemIdIsNull} 翻成 {@code plan_item_id IS NULL} ——
     * 也就是说"全局"这个概念是用 SQL 的 NULL 语义表达的, 而不是一个特殊的字符串
     * （{@code ""} / {@code "GLOBAL"}）。用字符串的代价是: 每个写约束的地方都要
     * 知道那个魔法值, 而漏掉一次的表现是"这条全局约束在恢复后消失了"。
     */
    List<PlanConstraintRecord> findByRevisionIdAndPlanItemIdIsNull(String revisionId);

    /**
     * 某一项在<b>这一版里</b>自己的约束 —— 走 {@code idx_plan_constraint_revision}
     * 的前缀。这是恢复路径用的那一个, 见类注释"一处被推翻的旧判断"。
     */
    List<PlanConstraintRecord> findByRevisionIdAndPlanItemId(String revisionId, String planItemId);

    /**
     * 某一项<b>历史上</b>的全部约束, 跨版本 —— 走 {@code idx_plan_constraint_item}。
     *
     * <p>刻意<b>不</b>是恢复路径要用的那个方法: 它返回的是"这一项被哪些约束管过",
     * 而恢复要问的是"它<b>此刻</b>被哪些管着"。
     */
    List<PlanConstraintRecord> findByPlanItemId(String planItemId);

    /** 这一版一共有几条约束（两种归属都算）—— 诊断用。 */
    List<PlanConstraintRecord> findByRevisionId(String revisionId);
}
