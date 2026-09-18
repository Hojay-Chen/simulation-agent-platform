package com.luxera.companion.human.life.activity;

import java.time.Duration;
import java.util.Objects;

/**
 * V2.2 §8.4 —— <b>一类活动"长什么样"的那组数</b>: 多费注意力、多怕被打断、手机在不在手边。
 *
 * <h2>为什么把它单独抽出来, 而不是散在每个实现类的 getter 里</h2>
 * 这些数在旧实现里是 {@code LifeActivity} 的<b>列</b>
 * （{@code attention_demand} / {@code interruptibility} / {@code phone_availability}
 * / {@code mood_effect}），而 {@code type} 是一个 12 值的字符串。那种结构有两个问题:
 *
 * <ol>
 *   <li><b>数只能靠改数据改。</b>想调"开会时手机可用性"要写一条 UPDATE ——
 *       于是"她的行为为什么变了"这件事在代码里找不到痕迹;</li>
 *   <li><b>12 个类型的差别被藏进了数据行。</b>打开代码看不到"睡觉和开会有什么不同",
 *       只能去查某一行数据。而它们的不同恰恰是这个模型最要紧的部分。</li>
 * </ol>
 *
 * <p>把数收进一个 record 之后, 每一个活动实现类都<b>用代码说出自己的样子</b>:
 * <pre>{@code
 * public final class SleepActivity extends AbstractActivity {
 *     static final ActivityProfile PROFILE = new ActivityProfile(
 *             0.02,   // 注意力: 几乎不需要清醒的注意力
 *             0.35,   // 可打断性: 闹钟能叫醒她, 但代价很大 —— 见实现类的说明
 *             0.05,   // 手机可用性: 睡着了手机不在手上
 *             +0.30,  // 情绪影响: 睡好了心情会好
 *             Duration.ofHours(7),
 *             "卧室", "躺着");
 * }
 * }</pre>
 *
 * <h2>第三方要不要用这个类?</h2>
 * <b>不必。</b>{@link Activity} 才是契约, 本类只是一个现成的实现零件。
 * 一个接入"打游戏"这种活动的三方可以直接在 {@code attentionDemand()} 里
 * 写自己的算法（比如按当前关卡难度算）, 而不必迁就这四个数。
 * 本类存在是为了让<b>常见情形不必重新发明</b>, 而不是为了让所有活动都长成一样。
 *
 * @param attentionDemand  占用多少注意力 0..1。开会 0.92, 散步 0.2。
 *                         它决定"消息来了她会不会注意到" —— 一个 0.9 的活动
 *                         会让手机上的通知<b>更难进入她的意识</b>, 而不是"她不想看"
 * @param interruptibility 多容易被外部事件打断 0..1。越接近 1 越容易被消息打断。
 *                         吃饭 0.6, 开会 0.12。
 *                         <b>注意它是"代价"不是"许可"</b>: 决定要不要打断的是
 *                         Mind（见 §3.4），这里回答的是"打断她有多难、多伤"
 * @param phoneAvailability 手机在不在手边 0..1。开会放包里 → 低。
 *                         它<b>不</b>等于"她想不想看手机": 一个 0.05 的值
 *                         表示"就算她想看也看不到", 而那是一种比"不想看"
 *                         更硬的约束 —— 它会让查看手机这个动作失败,
 *                         而失败的原因必须能被她自己理解（"手机在包里"）
 * @param moodEffect       这件事对心情的影响 -1..1。见朋友 +0.2, 加班 -0.15
 * @param typicalDuration  通常持续多久。给规划器做默认值用 ——
 *                         一个没有给定时长的意图会取它
 * @param place            通常在哪做（进 {@code PlanningContext.locations} 的键）。
 *                         空串表示不限地点
 * @param posture          身体姿态的简短描述（"躺着"/"坐着"/"走动"）。
 *                         它进 LLM context, 让"她能不能顺手拿手机"这类判断有依据
 */
public record ActivityProfile(
        double attentionDemand,
        double interruptibility,
        double phoneAvailability,
        double moodEffect,
        Duration typicalDuration,
        String place,
        String posture) {

    public ActivityProfile {
        checkUnit("attentionDemand", attentionDemand);
        checkUnit("interruptibility", interruptibility);
        checkUnit("phoneAvailability", phoneAvailability);
        if (moodEffect < -1.0 || moodEffect > 1.0) {
            throw new IllegalArgumentException(
                    "moodEffect 应在 -1..1 之间, 收到 " + moodEffect
                            + " —— 越界的心情影响会让'她今天心情如何'的累加失去意义");
        }
        Objects.requireNonNull(typicalDuration, "典型时长不能为空 —— 规划器要靠它填默认值");
        if (typicalDuration.isNegative() || typicalDuration.isZero()) {
            throw new IllegalArgumentException(
                    "典型时长必须为正, 收到 " + typicalDuration);
        }
        place = place == null ? "" : place;
        posture = posture == null ? "" : posture;
    }

    private static void checkUnit(String field, double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    field + " 应在 0..1 之间, 收到 " + value
                            + " —— 一个超出范围的占比会让所有基于它的加权失去可比性");
        }
    }

    /** 一条中庸的默认档案 —— 说不上专注也说不上分心。 */
    public static ActivityProfile neutral() {
        return new ActivityProfile(0.5, 0.5, 0.6, 0.0,
                Duration.ofMinutes(30), "", "坐着");
    }

    /** 这件事是不是"基本没法被打断"。 */
    public boolean focused() {
        return interruptibility <= 0.2;
    }

    /** 手机是不是"基本拿不到"。 */
    public boolean phoneOutOfReach() {
        return phoneAvailability <= 0.2;
    }

    /** 这件事是让人开心还是让人烦。 */
    public String moodLabel() {
        if (moodEffect >= 0.15) {
            return "让人高兴";
        }
        if (moodEffect <= -0.15) {
            return "消耗人";
        }
        return "谈不上好坏";
    }

    public String describe() {
        return String.format("注意力 %.2f, 可打断 %.2f, 手机可达 %.2f, 心情 %+.2f, 通常 %d 分钟%s",
                attentionDemand, interruptibility, phoneAvailability, moodEffect,
                typicalDuration.toMinutes(),
                place.isEmpty() ? "" : ", 地点 " + place);
    }
}
