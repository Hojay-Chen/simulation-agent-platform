package com.luxera.companion.human.life.plan;

import com.luxera.companion.registry.DomainType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.5.2 —— <b>计划想完成的事情</b>。
 *
 * <h2>这是整个计划系统最关键的一个抽象</h2>
 * 用户对这个系统的全部要求可以归结成一句话: <b>不要有"计划类型"</b>。
 * 计划表里存的应该是"未来某个时间窗口, 我准备完成什么意图", 而不是
 * "一个 {@code STUDY} 类型的条目"。
 *
 * <p>差别在扩展性上体现得最清楚:
 * <table border="1">
 *   <tr><th></th><th>{@code enum PlanType}</th><th>本接口</th></tr>
 *   <tr>
 *     <td>加一种新计划</td>
 *     <td>改平台源码 + 重新编译 + 重新发版</td>
 *     <td>写一个实现类, 注册即可</td>
 *   </tr>
 *   <tr>
 *     <td>第三方能加吗</td>
 *     <td><b>不能</b></td>
 *     <td>能 —— 这正是用户要的</td>
 *   </tr>
 *   <tr>
 *     <td>带参数吗</td>
 *     <td>不能, 枚举常量不能带业务数据</td>
 *     <td>能 —— 字段就是参数, 落库进 JSONB</td>
 *   </tr>
 *   <tr>
 *     <td>行为差异</td>
 *     <td>{@code switch} 散落在各处, 加一种要找出所有 switch</td>
 *     <td>行为在类自己身上（{@link #evaluate} / {@link #decompose}）</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么接口里有 {@link #decompose} 和 {@link #createActivity}</h2>
 * 因为"写作业"这件事在不同抽象层级上要做不同的事:
 * <ul>
 *   <li>在计划层, 它是一个占据 12:00-13:00 的<b>时间块</b>;</li>
 *   <li>在执行层, 它可能拆成"拿出课本 → 做完第 3 章习题 → 对答案";</li>
 *   <li>在能力层, 每一步最终都要落到某个 {@code Capability} 上。</li>
 * </ul>
 *
 * <p>把这三层塞进一个方法会得到一个无法测试、无法被 LLM 复用的东西。
 * 所以接口把它们分开, 并允许 {@link #decompose} 返回空列表 ——
 * 那表示"这是一个原子意图, 不需要再拆"。<b>允许返回空是刻意的</b>:
 * 强制每个意图都必须拆出至少一步, 会逼着实现者写一堆无意义的中间步骤。
 *
 * <h2>实现这个接口要注意什么</h2>
 * <ol>
 *   <li>标上 {@link DomainType} —— 否则它落库之后读不回来（见 {@code PolymorphicSerializer}）;</li>
 *   <li>提供一个公开无参构造 —— 反序列化需要它;</li>
 *   <li>{@link #description} 写<b>给她自己看</b>的自然语言, 它会直接进 LLM context。
 *       不要写 {@code "STUDY_SESSION"} 这种, 那等于把枚举搬回来了;</li>
 *   <li>{@link #evaluate} 必须<b>诚实</b>。返回 feasible 但实际做不成, 会让计划表
 *       排进一堆注定失败的事, 而症状是"她总是完不成计划" —— 一个会被误读成
 *       "她意志力差"的系统缺陷。</li>
 * </ol>
 */
public interface PlanIntent {

    IntentId id();

    /**
     * 自然语言描述 —— 给她自己（LLM context）也给界面和行为分析看。
     *
     * <p>例子: {@code "把数学作业第 3 章做完"}、{@code "给妈妈回个电话"}。
     */
    String description();

    /**
     * 这件事现在做得了吗？缺什么？
     *
     * <p>返回的 {@link Feasibility} 里带着"缺什么", 因为那个信息有两个用处:
     * 重排器可以据此插入一个前置项（"缺厚衣服" → 先排"穿衣服"）;
     * 而她自己也想知道原因（"现在去不了实验室, 因为太远了"）。
     */
    Feasibility evaluate(PlanningContext context);

    /**
     * 拆成更细的动作意图。
     *
     * <p>返回空列表表示这是一个原子意图 —— <b>这是合法的, 不是未实现</b>。
     */
    List<ActionIntent> decompose(PlanningContext context);

    /**
     * 这个意图需要什么能力（capability key）。
     *
     * <p>用途: 重排时检查"她现在有没有这些能力"。如果聊天应用没装上,
     * 那么"给她回个电话"这个意图就是不可行的 —— 而这个检查发生在<b>排进计划表之前</b>,
     * 比"到点了发现做不了"好得多。
     */
    Set<String> requiredCapabilities();

    /**
     * 这一项的时间窗口是否可以由重排器自由改动。
     *
     * <p>默认可以。<b>覆盖成 {@code false} 的场景</b>: 与外部世界绑定的时间 ——
     * "14:00 的考试"不能因为她说冷就挪到 15:00。
     *
     * <p>注意这与 {@link PlanItem#fixed()} 是同一个问题的两个视角:
     * 那个是"这一项被排定后不可动", 这个是"这个意图天生不可动"。
     * 两者都为真时才真正固定 —— 因为"可动的意图被排到不可动的位置"是合理的
     * （考试期间的复习计划可以挪, 考试本身不能）。
     */
    default boolean movableInTime() {
        return true;
    }

    /**
     * 预计需要多久。
     *
     * <p>默认 30 分钟。<b>为什么给一个默认值而不是强制实现</b>: 强制实现会让
     * 一些简单意图（"喝口水"）不得不写出一个假精度的时间。<b>但默认值不等于免责</b> ——
     * 重排器会把实际完成时长与它对比, 偏差大的意图应该覆盖它。
     */
    default java.time.Duration expectedDuration() {
        return java.time.Duration.ofMinutes(30);
    }

    /**
     * 这个意图真的做起来时, 是哪一类活动 —— 一个<b>活动类型名</b>。
     *
     * <h3>为什么是一个字符串, 而不是一个 {@code Class} 或一个枚举</h3>
     * 三个理由, 每个都对应一种会被写坏的设计:
     * <ul>
     *   <li>返回 {@code Class<? extends Activity>} 会让 {@code human.life.plan}
     *       这个包 import {@code human.life.activity} —— 而后者已经 import 了前者
     *       （它要实现本接口）。那是<b>包循环依赖</b>, 而循环依赖的后果是
     *       这两个包再也无法被单独理解和单独测试;</li>
     *   <li>用枚举会让第三方无法新增活动类型 —— 那正是设计文档要消灭的东西
     *       （旧实现里 {@code LifeActivity.type} 那 12 个字符串就是这么来的）;</li>
     *   <li>用字符串 + 注册表, 与 {@code world_event} 的类型机制完全同构:
     *       第三方写一个 {@code @DomainType("life.activity.gaming")} 的类,
     *       在 {@code ActivityFactory} 注册, 然后自己的意图返回这个名字。
     *       <b>平台源码一行都不用改。</b></li>
     * </ul>
     *
     * <h3>默认值为什么指向"其他"而不是抛异常</h3>
     * 因为一个说不出自己属于哪类活动的意图是合法的 —— 用户要的是"她可以做任何事",
     * 而不是"她只能做平台预先认识的十二件事"。默认落到
     * {@code OtherActivity}（中庸档案）让这种意图仍然能被执行、被记录、被分析,
     * 只是它不携带专门的注意力/可打断性参数。
     *
     * <p>实现"写作业"这类意图时应当覆盖它 —— 否则她在做一件需要专注的事时,
     * 模型会以为她只是"不好不坏地忙着"。
     */
    default String activityType() {
        return "life.activity.other";
    }

    // ─────────────────────────── 嵌套类型 ───────────────────────────

    /**
     * 意图的身份。
     *
     * <p>与 {@link PlanItemId} 的区别是本质的: 同一个意图可以出现在多个计划项里
     * （"每天写作业"是一个意图、七个计划项）, 而计划项是"这个意图的这一次安排"。
     * 合并两者会导致"她这周写了五次作业"无法统计 —— 因为五次共用了一个 id。
     */
    record IntentId(String value) {

        public IntentId {
            Objects.requireNonNull(value, "意图 id 不能为空");
            if (value.isBlank()) {
                throw new IllegalArgumentException("意图 id 不能是空白");
            }
        }

        public static IntentId of(String value) {
            return new IntentId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * "这件事做得了吗"的答案。
     *
     * @param feasible 现在做得了吗
     * @param reason   <b>无论可行与否都要给的一句人话</b>。不可行时它进 LLM context
     *                 成为"我为什么没做这件事"的解释; 可行时它是一句确认
     *                 （"课本在书包里"）—— 空白字符串是允许的, 但强烈不推荐,
     *                 因为它会让日志里出现一排没有理由的判定
     * @param missing  缺什么才能做。可行时为空列表。这些字符串会被重排器用来
     *                 推导前置项
     */
    record Feasibility(boolean feasible, String reason, List<String> missing) {

        public Feasibility {
            Objects.requireNonNull(reason, "可行性的理由不能为 null —— 没有理由时请用空字符串");
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        public static Feasibility yes(String reason) {
            return new Feasibility(true, reason, List.of());
        }

        public static Feasibility no(String reason, String... missing) {
            return new Feasibility(false, reason, List.of(missing));
        }

        public static Feasibility no(String reason, List<String> missing) {
            return new Feasibility(false, reason, missing);
        }

        public String describe() {
            if (feasible) {
                return "可行" + (reason.isEmpty() ? "" : " —— " + reason);
            }
            return "不可行: " + reason + (missing.isEmpty() ? "" : " (缺: " + missing + ")");
        }
    }

    /**
     * 一个可以直接落到 {@code ActionFabric} 上的动作意图。
     *
     * <p>它存在的意义是把"意图"翻译成"动作"这件事<b>变成一个数据</b>, 而不是
     * 一段代码。这样 {@link #decompose} 的产物就能被:
     * <ul>
     *   <li>校验（这个 capability 存在吗、参数对吗）;</li>
     *   <li>展示（界面上"她准备做这三步"）;</li>
     *   <li>持久化（重排算出的计划在落库前要能存下来）。</li>
     * </ul>
     *
     * <p>{@code capabilityKey} 直接就是 {@code ActionCommand.capabilityKey()} ——
     * 中间不设转换层。多一层映射的代价是"两边各有一份能力名清单",
     * 而它们迟早会分叉。
     */
    record ActionIntent(String capabilityKey, Map<String, Object> arguments, String description) {

        public ActionIntent {
            Objects.requireNonNull(capabilityKey, "能力 key 不能为空");
            if (capabilityKey.isBlank()) {
                throw new IllegalArgumentException(
                        "能力 key 不能是空白 —— 一个没有 key 的动作意图无法被执行, "
                                + "而它会在很久以后表现为'计划到点了但什么也没发生'");
            }
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            Objects.requireNonNull(description, "动作描述不能为 null —— 它要进 LLM context");
        }

        public static ActionIntent of(String capabilityKey, String description) {
            return new ActionIntent(capabilityKey, Map.of(), description);
        }

        public static ActionIntent of(String capabilityKey, Map<String, Object> args, String description) {
            return new ActionIntent(capabilityKey, args, description);
        }
    }
}
