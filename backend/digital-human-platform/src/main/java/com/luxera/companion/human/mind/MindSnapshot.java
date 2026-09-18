package com.luxera.companion.human.mind;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4 —— <b>她此刻心里的样子</b>。一张<b>只读的摘要</b>, 不是她的状态本身。
 *
 * <h2>它为什么存在</h2>
 * 因为"她为什么这么做"这个问题, 事后只能靠一份<b>当时</b>的记录来回答。而 Mind 内部
 * 有七八个部件, 每个都有自己的状态: 工作记忆里排着队的东西、通讯录里有几个人、
 * 计划表上正做着什么、已经做过多少次决定。让诊断面板与行为分析挨个去问这些部件,
 * 有两个具体后果:
 *
 * <ul>
 *   <li>每个调用方都要知道 Mind 内部装了什么 —— 于是 Mind 的任何一个部件变动
 *       都会波及一串调用方, 而它们其实只是想把这一刻截个图;</li>
 *   <li>它们会在<b>不同的时刻</b>问出不同的答案, 而拼起来的那张图在时间上不自洽
 *       —— 比如"工作记忆是空的"与"刚做完一个决定"同时出现。</li>
 * </ul>
 *
 * <p>换句话说: 它存在的理由是让"看一眼她"这件事只发生一次。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不带正文</b>。它只有计数与一句当前活动 —— 工作记忆里的东西、
 *       记忆里的内容都不进来。摘要一旦带上正文, 它就会开始被当成"她的记忆"的第二份拷贝,
 *       于是"她不该在读到之前就知道内容"这条约束会从后面被绕过(见 §9 验收标准 E);</li>
 *   <li><b>不是可变状态</b>。它是一张某一刻的照片。把它存下来再改, 或者拿它当
 *       输入去做决定, 都会让"决定是纯函数"这条性质失效 —— 决定只能看
 *       {@code IntentionContext}, 不能看快照;</li>
 *   <li><b>不判断她好不好</b>。它没有"情绪分"、"健康分"这类合成指标。分数一旦出现,
 *       就会有人拿它去做分支, 而那种分支没法解释 —— 见 {@code PlanPriority} 里
 *       同一条理由("标签只给人看, 不许用来判断")。</li>
 * </ul>
 *
 * @param humanId             这是谁的快照
 * @param at                  这份快照说的是哪一刻 —— 不许读系统时钟
 * @param workingMemorySize   工作记忆里现在有几条
 * @param workingMemoryLoad   工作记忆的占用比例 0–1
 * @param memoryRecords       长期记忆里一共几条
 * @param peopleKnown         她认识几个人
 * @param selfMadeBindings    其中有多少账号绑定是<b>她自己建立的</b> —— §3.4.6 那句
 *                            "通讯录是她自己长出来的"在数字上的体现
 * @param currentActivity     她此刻在做的那件事的一句话描述, 闲着时是空串
 * @param decisionsMade       她一共做过多少次决定(含"决定不做"的那些)
 * @param lastDecision        最近一次决定的一句话描述, 没做过时空串
 */
public record MindSnapshot(
        String humanId,
        Instant at,
        int workingMemorySize,
        double workingMemoryLoad,
        int memoryRecords,
        int peopleKnown,
        int selfMadeBindings,
        String currentActivity,
        int decisionsMade,
        String lastDecision) {

    public MindSnapshot {
        Objects.requireNonNull(at, "快照必须带时刻 —— 不许读系统时钟");
        humanId = humanId == null || humanId.isBlank() ? "unknown" : humanId;
        currentActivity = currentActivity == null ? "" : currentActivity.trim();
        lastDecision = lastDecision == null ? "" : lastDecision.trim();
        if (workingMemorySize < 0 || memoryRecords < 0 || peopleKnown < 0
                || selfMadeBindings < 0 || decisionsMade < 0) {
            throw new IllegalArgumentException("快照里的计数不能为负 —— 负数的计数会让趋势图无法解释");
        }
    }

    public boolean isBusy() {
        return !currentActivity.isEmpty();
    }

    /** 她自己长出来的绑定占全部绑定的比例。一个人都没认识时返回 0, 而不是 NaN。 */
    public double selfMadeShare() {
        return selfMadeBindings <= 0 || peopleKnown <= 0
                ? 0.0
                : Math.min(1.0, (double) selfMadeBindings / (double) peopleKnown);
    }

    public String describe() {
        return "MindSnapshot[" + humanId + " @ " + at
                + "] 工作记忆 " + workingMemorySize + " 条(占用 "
                + Math.round(workingMemoryLoad * 100) + "%), 长期记忆 " + memoryRecords
                + " 条, 认识 " + peopleKnown + " 人(其中 " + selfMadeBindings + " 个是自己认识的), "
                + (isBusy() ? "正做着: " + currentActivity : "此刻闲着")
                + ", 累计决定 " + decisionsMade + " 次"
                + (lastDecision.isEmpty() ? "" : ", 最近一次: " + lastDecision);
    }

    @Override
    public String toString() {
        return describe();
    }
}
