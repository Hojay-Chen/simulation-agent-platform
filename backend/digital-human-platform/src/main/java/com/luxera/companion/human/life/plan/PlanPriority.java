package com.luxera.companion.human.life.plan;

import java.util.Objects;

/**
 * V2.2 §1.3 P4 / §3.5.2 —— 计划项的重要性。
 *
 * <h2>为什么<b>不</b>是枚举</h2>
 * 直觉上"优先级"就该是 {@code LOW / NORMAL / HIGH / URGENT} 这样的枚举。本设计拒绝它,
 * 理由和拒绝 {@code PlanType} 是同一条, 但更隐蔽 —— 因为优先级看起来"就那么几档"。
 *
 * <p>问题出在<b>第三方</b>身上。用户明确要求这个架构能让三方平台软件自己实现接口接入。
 * 一个接入了"学校教务系统"的第三方会带来它自己的优先级概念:
 * <pre>{@code
 * // 第三方想表达的东西
 * EXAM_DURING_FINAL_WEEK   —— 考试周, 一切让路
 * HOMEWORK_DUE_TOMORROW    —— 明天要交
 * OPTIONAL_READING         —— 可选阅读
 * }</pre>
 * 如果优先级是枚举, 这第三方只有两个选择: 挤进平台的四档(丢失它自己的语义),
 * 或者要求平台作者改枚举(那就回到"每接一个三方就要改平台源码")。
 *
 * <h2>取值是一个<b>可比较的档位 + 一句理由</b></h2>
 * {@code level} 越大越重要, 用于排序与抢占判断; {@code label} 是给人看的一句话,
 * 它会进入 LLM 的 context 和行为分析报告。
 *
 * <p><b>为什么要有 label 而不是只留一个数</b>: 在行为分析里看到"她在 12:15 把 level=70
 * 的事挪后了", 读者无法判断这是不是一个合理的决定。而"她把『考试周复习』挪后了"
 * 本身就带着判断所需的信息。{@link #describe()} 输出的就是这种可读形式。
 *
 * <h2>平台自带的几档</h2>
 * 它们只是<b>常量</b>, 不是封闭集合 —— 第三方可以定义 65、71 这样介于两者之间的档位。
 * <table border="1">
 *   <tr><th>常量</th><th>level</th><th>典型场景</th></tr>
 *   <tr><td>{@link #TRIVIAL}</td><td>10</td><td>可选阅读、随便看看</td></tr>
 *   <tr><td>{@link #ROUTINE}</td><td>40</td><td>日常作息、写作业</td></tr>
 *   <tr><td>{@link #IMPORTANT}</td><td>70</td><td>有截止时间的事、答应别人的事</td></tr>
 *   <tr><td>{@link #CRITICAL}</td><td>95</td><td>考试、报到、紧急健康问题</td></tr>
 * </table>
 *
 * <h2>它与 {@code urgency} 不是一回事</h2>
 * 这是最容易被合并、而合并后一定会出问题的一对概念:
 * <table border="1">
 *   <tr><th></th><th>{@code PlanPriority}（本类）</th><th>{@code SensoryEvent.urgency}</th></tr>
 *   <tr><td>属于</td><td>Human 侧的<b>计划</b></td><td>World 侧的<b>刺激</b></td></tr>
 *   <tr><td>回答</td><td>"这件事对她多重要"</td><td>"这条刺激能不能等"</td></tr>
 *   <tr><td>例子</td><td>毕业论文很重要（level=90）, 但今天不必做</td>
 *       <td>火警很急（urgency=1.0）, 必须现在处理</td></tr>
 * </table>
 *
 * <p>"重要但不紧急"与"紧急但不重要"是两种不同的处境, 而正确的反应也不同。
 * 把它们压成一个数, 就再也分不出这两者了。
 */
public record PlanPriority(int level, String label) implements Comparable<PlanPriority> {

    public static final PlanPriority TRIVIAL = new PlanPriority(10, "可做可不做");
    public static final PlanPriority ROUTINE = new PlanPriority(40, "日常安排");
    public static final PlanPriority IMPORTANT = new PlanPriority(70, "有承诺或截止时间");
    public static final PlanPriority CRITICAL = new PlanPriority(95, "不能让路的事");

    /** 未指定时的默认档 —— 与 {@link #ROUTINE} 相同, 因为大多数计划项都是日常安排。 */
    public static final PlanPriority DEFAULT = ROUTINE;

    public PlanPriority {
        Objects.requireNonNull(label, "优先级的 label 不能为空 —— 它要进 LLM context, "
                + "一个没有理由的档位在行为分析里无法被判断");
        if (label.isBlank()) {
            throw new IllegalArgumentException("优先级的 label 不能是空白");
        }
        if (level < 0 || level > 100) {
            throw new IllegalArgumentException(
                    "优先级档位应在 0..100 之间, 收到 " + level
                            + " —— 定一个约定范围是为了让不同来源的档位可以混在一起比较。"
                            + "如果你需要超出这个范围, 那说明你要表达的不是优先级");
        }
    }

    public static PlanPriority of(int level) {
        return new PlanPriority(level, "自定义档位 " + level);
    }

    /** 第三方定义自己的档位时用这个, 让 label 说明它是什么。 */
    public static PlanPriority of(int level, String label) {
        return new PlanPriority(level, label);
    }

    /** 是否比另一项更重要。 */
    public boolean outranks(PlanPriority other) {
        return level > other.level;
    }

    /**
     * 是否<b>显著</b>比另一项重要。
     *
     * <p>差距阈值的存在是为了避免"她每 tick 都在重排": 如果 41 比 40 就能抢占,
     * 那么任何一点扰动都会导致计划表抖动。留出 {@value #SIGNIFICANT_GAP} 的余量,
     * 意思是"只有当新的这件事明显更重要时, 才值得打断她正在做的"。
     */
    public static final int SIGNIFICANT_GAP = 15;

    public boolean significantlyOutranks(PlanPriority other) {
        return level - other.level >= SIGNIFICANT_GAP;
    }

    @Override
    public int compareTo(PlanPriority other) {
        return Integer.compare(level, other.level);
    }

    public String describe() {
        return label + "(level=" + level + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
