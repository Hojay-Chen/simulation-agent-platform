package com.luxera.companion.architecture;

import com.luxera.companion.boundary.event.EventFabric;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §8.2.7 —— <b>把"Human ⟂ World"从一句设计原则变成一条会红的规则</b>。
 *
 * <h2>为什么这条边界需要一条自动化规则, 而不是靠自觉</h2>
 * 用户的原话是"human 和 world <b>完全没有交互</b>", 只留一个类消息队列在中间。
 * 这条约束在纸面上很清楚, 但它有一个致命的性质: <b>违反它的代码看起来很正常</b>。
 *
 * <pre>
 *   // 在 Life.java 里:
 *   double temp = world.environment().temperature();     // ← 破坏了一切, 但看不出问题
 *   if (temp &lt; 10) { replanWith(clothes); }
 * </pre>
 *
 * <p>这几行能编译、能跑、结果也<b>看起来对</b>。它毁掉的是别的东西:
 * <ul>
 *   <li>回放不再是"世界喂进去什么"的函数, 而是"世界对象当时恰好什么状态"的函数 ——
 *       同一份历史数据喂回去, 她的一天不一样了;</li>
 *   <li>{@code human/} 的单元测试从此必须搭一个 World 才能跑;</li>
 *   <li>"她的行为完全由进入她感官的信息决定"这个论断<b>不再可证</b>。</li>
 * </ul>
 *
 * <p>最要紧的是: 这个依赖会<b>越来越多</b>。第一个出现时看起来只是图个方便,
 * 而它一旦在, 第二个就不再需要理由了。所以这条规则必须在第一个出现之前就在。
 *
 * <h2>为什么规则分成两类, 而不是全用 ArchUnit</h2>
 * <table border="1">
 *   <tr><th>规则</th><th>用什么查</th><th>为什么是它</th></tr>
 *   <tr>
 *     <td>human ⟂ world</td><td>ArchUnit（{@code import} 依赖图）</td>
 *     <td>这是纯粹的"谁依赖了谁" —— ArchUnit 的本行, 而且它看的是<b>字节码</b>,
 *         注释里提到 {@code com.luxera.companion.world.WearableObject} 不会误报</td>
 *   </tr>
 *   <tr>
 *     <td>不读系统时钟</td><td>源码文本扫描</td>
 *     <td>{@code Instant.now()} 是一次<b>静态调用</b>, 它不产生任何 import
 *         （{@code java.time.Instant} 本来就该被 import）。ArchUnit 的依赖图画不出
 *         "这个静态方法被调了" —— 写成 ArchUnit 规则会得到一个永远绿的规则,
 *         而那比不写更糟: 它让人以为有人守着</td>
 *   </tr>
 *   <tr>
 *     <td>没有域枚举</td><td>源码文本扫描</td>
 *     <td>要禁的是<b>类型声明的形状</b>（{@code enum XxxType}）, 不是依赖关系</td>
 *   </tr>
 * </table>
 *
 * <h2>继承自上一版的一条规则（那个类已经删了）</h2>
 * 上一版有一个 {@code DhApplicationKnowledgeArchitectureTest}, 断言"数字人的源码里
 * 不许出现任何一个具体应用的知识"。那条规则的<b>意图</b>在 V2.2 里依然成立并且更强了
 * （现在连"手机应用"都是第三方的实现类）, 所以
 * {@link #humanAndWorldKnowNothingAboutAnyParticularPlatform()} 把它整条接了过来。
 *
 * <p>旧类本身<b>已经删除</b>, 删它有三个理由, 而三个都不是"它挡路了":
 * <ol>
 *   <li>它的第二条规则白名单挂在 {@code tool/ReminderService.java} 上, 而那个类
 *       正是 V2.2 要拿掉的那个适配器 —— 白名单一旦随它一起消失, 那条规则就会
 *       以"没人再提提醒应用"的姿态变绿。一个会因为<b>被守的东西被删掉</b>而变绿的守卫,
 *       不能留着。</li>
 *   <li>它的棋类词表里有 {@code \bboard\b}。当时代码里唯一的 "board" 是棋盘;
 *       现在计划表叫 {@code PlanBoard}, 于是 {@code board} 成了一个局部变量名。
 *       词表本身需要修正（见
 *       {@link #humanAndWorldKnowNothingAboutAnyParticularPlatform()} 里关于这些词的说明）。</li>
 *   <li>它的意图与新规则完全重合, 而两份各自演化的词表<b>必然</b>会分叉 ——
 *       到那时"哪一份是真的"就没人说得清了。</li>
 * </ol>
 *
 * <p>所以这里是<b>搬过来</b>, 不是删掉: 平台名、{@code game.*}、{@code tictactoe}、
 * {@code 井字棋}、{@code checkWinner} 这些词一个没少（只去掉了 {@code board}）。
 */
class V22BoundaryArchitectureTest {

    /** 新架构的四个包 —— 规则只扫它们, 不扫待删的旧包。 */
    private static final String HUMAN = "com.luxera.companion.human";
    private static final String WORLD = "com.luxera.companion.world";
    private static final String BOUNDARY = "com.luxera.companion.boundary";
    private static final String REGISTRY = "com.luxera.companion.registry";

    // ─────────────────────── 迁移期的三个已知旧文件 ───────────────────────

    /**
     * 读了系统时钟的旧文件 —— 整体待删除, 现在还没轮到它们。
     *
     * <h2>为什么这里要有一份名单, 而不是干脆不扫这几个文件</h2>
     * 因为 {@code world/} 这个包<b>同时</b>装着新架构（{@code world/device}、
     * {@code world/environment}、{@code world/object}、{@code world/application}）
     * 和上一版留下的七个文件。按包排除做不到"排除旧的、留下新的" ——
     * 现有的三条规则只扫新包, 而新旧同处一个包。
     *
     * <p>另一条路是"等旧文件删完了再启用这条规则"。那条路更糟: 它意味着这道守卫
     * 在迁移最忙、最容易写错的那段时间里<b>恰好是关着的</b>。而它要防的正是
     * "第一个 {@code Instant.now()} 看起来只是图个方便, 而它一出现第二个就不需要理由了"。
     *
     * <h2>这份名单只会变短, 而且是被测试逼着变短的</h2>
     * {@link #assertNoStaleLegacyExcuses} 断言名单里的每一项<b>这一次真的被用作借口</b>。
     * 于是两种情况下这个测试会红, 而两种都必须动手改名单:
     * <ul>
     *   <li>文件被删了（迁移的正常结局）—— 它不是 offender 了, 名单里那一项作废;</li>
     *   <li>文件里的违规被修了 —— 同上, 它不再需要被豁免。</li>
     * </ul>
     * <b>名单不会自己变长</b>: 新出现的违规不在名单里, 会直接打红上面那些规则。
     * 所以它是一条只下不上的棘轮, 而不是一张可以随手加人的白名单 ——
     * 后者是守卫失效的第一步, 这一点在上一版那个已被删除的
     * {@code DhApplicationKnowledgeArchitectureTest} 里已经踩过一次。
     */
    private static final List<String> LEGACY_WALL_CLOCK = List.of(
            "com/luxera/companion/world/WorldEventEngine.java",
            "com/luxera/companion/world/EventEnvelope.java");

    /** 旧词汇表里的枚举 —— 同样待删除。 */
    private static final List<String> LEGACY_DOMAIN_ENUM = List.of(
            "com/luxera/companion/world/AgentEventType.java");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    // ─────────────────────────── 一、Human ⟂ World ───────────────────────────

    /**
     * {@code human/} 不许 import {@code world/}。
     *
     * <p>这是用户那句"human 和 world 完全没有交互"的<b>第一半</b>。
     * 她要感知世界, 只能通过 {@link EventFabric} 投进来的事件 ——
     * 而 {@code EventFabric} 住在 {@code boundary/} 里, 两边都认识它,
     * 它不认识两边中的任何一个。
     */
    @Test
    void theHumanNeverReachesIntoTheWorld() {
        noClasses()
                .that().resideInAPackage(HUMAN + "..")
                .should().dependOnClassesThat().resideInAPackage(WORLD + "..")
                .because("Human 与 World 之间没有直接交互 —— 她只能通过 boundary 的 "
                        + "EventFabric 收到世界发生的事, 而不能伸手去读世界的状态。"
                        + "一旦这里出现依赖, 回放就不再可复现(她的一天变成'世界对象当时恰好什么状态'的函数), "
                        + "而且这个依赖只会越来越多")
                .check(classes);
    }

    /** {@code world/} 不许 import {@code human/} —— 第二半, 同样重要。 */
    @Test
    void theWorldNeverReachesIntoTheHuman() {
        noClasses()
                .that().resideInAPackage(WORLD + "..")
                .should().dependOnClassesThat().resideInAPackage(HUMAN + "..")
                .because("对称的另一半: 世界不该知道她是谁、她在做什么。"
                        + "手机响不响由手机的规则决定, 而不是由'她此刻忙不忙'决定 —— "
                        + "后者是 Human 侧的处理, 发生在事件投递之后")
                .check(classes);
    }

    /**
     * 边界层是唯一被两边共享的东西, 而它<b>自己不许认识任何一边</b>。
     *
     * <p>少了这一条, 前两条可以被绕过: 在 {@code boundary/} 里放一个
     * {@code HumanSideBridge} 持有 {@code Life}, 那么 {@code human → boundary → human}
     * 是合法的, 而 {@code world → boundary} 也合法 —— 两个包通过中间人握上了手,
     * 而三条 ArchUnit 规则全是绿的。
     */
    @Test
    void theBoundaryItselfKnowsNeitherSide() {
        noClasses()
                .that().resideInAPackage(BOUNDARY + "..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(HUMAN + "..", WORLD + "..")
                .because("boundary 是双方共享的词汇表, 不是双方的传声筒。"
                        + "它一旦认识任何一边, 前两条规则就可以被绕过: "
                        + "world → boundary → human 每一跳都合法, 而握手已经完成了")
                .check(classes);
    }

    // ─────────────────────────── 二、时间只有一个来源 ───────────────────────────

    /**
     * 会不会读系统时钟的写法。
     *
     * <h2>为什么 {@code Instant.now} 只匹配<b>无参</b>形式</h2>
     * {@code Instant.now(clock)} 与 {@code Instant.now()} 长得几乎一样, 性质却相反:
     * 前者是"用别人给我的钟读一次", 后者是"向操作系统要一次当前时刻"。
     * 只有后者破坏可重放性 —— 一个从构造函数收下 {@link java.time.Clock} 的对象,
     * 在测试里喂一个 {@code Clock.fixed} 就是完全确定的。
     *
     * <p>把它们一起禁掉会得到一个<b>过于宽</b>的规则, 而过于宽的规则的结局是可预见的:
     * 有人为了让它变绿, 往允许名单里加一个文件。那条路一旦走上去, 这道守卫就废了。
     *
     * <h2>但放宽 {@code Instant.now} 会开一个洞, 所以下一条补上了它</h2>
     * 洞是这样的: <pre>Instant.now(Clock.systemUTC())</pre> —— 形式上是"用别人给的钟",
     * 实际上还是系统钟。所以 {@code Clock.system*} 被<b>单独</b>列为一个模式:
     * 拿系统钟的那一下在哪里都拦得住, 无论它被内联进 {@code Instant.now} 还是
     * 存进一个字段。
     *
     * <p><b>这两条是一起改的, 不是两条独立的规则。</b>上一版的阳性对照
     * （{@link #theClockRuleWouldActuallyCatchAViolation}）就是被 {@code Clock.systemUTC()}
     * 打红的 —— 它当时扫不到 {@code SimulationClock}, 因为后者用的正是这个写法。
     * 那次失败说明的是: 一个只认识 {@code Instant.now()} 的规则, 认识不了
     * "读系统钟"这件事的全部写法。
     */
    private static final List<Pattern> WALL_CLOCK_READS = List.of(
            Pattern.compile("Instant\\.now\\s*\\(\\s*\\)"),
            Pattern.compile("System\\.currentTimeMillis\\s*\\("),
            Pattern.compile("System\\.nanoTime\\s*\\("),
            Pattern.compile("LocalDate(Time)?\\.now\\s*\\("),
            Pattern.compile("ZonedDateTime\\.now\\s*\\("),
            Pattern.compile("OffsetDateTime\\.now\\s*\\("),
            Pattern.compile("new\\s+java\\.util\\.Date\\s*\\(\\s*\\)"),
            Pattern.compile("new\\s+Date\\s*\\(\\s*\\)"),
            Pattern.compile("Clock\\.system[A-Za-z]*\\s*\\(")
    );

    /**
     * {@code human/}、{@code world/}、{@code boundary/} 里一次都不许读系统时钟。
     *
     * <p>仿真时刻只有一个来源: {@code runtime.SimulationClock}, 由外部推进。
     * 这条规则是把设计里反复出现的那些话（"重排必须带时刻"、"调度器不读时钟"、
     * "推进必须带仿真时刻"）一次性地钉死。
     *
     * <p>为什么这件事值得一条会红的规则: 一个内部读 {@code now()} 的方法
     * <b>在测试里也能过</b> —— 它只是让测试结果随运行时刻漂移。而漂移的测试
     * 通常被写成"结果应当落在某个范围里", 那种断言放过了大部分 bug。
     * 等到有人想回放"她上周三为什么这样排"时, 才发现已经做不到 ——
     * 而到那时, 读时钟的地方已经有几十处了。
     */
    @Test
    void nothingUnderHumanWorldOrBoundaryReadsTheWallClock() {
        List<String> offenders = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        for (Source source : sources()) {
            if (!isUnder(source, HUMAN) && !isUnder(source, WORLD) && !isUnder(source, BOUNDARY)) {
                continue;
            }
            if (excuse(source, LEGACY_WALL_CLOCK, excused)) {
                continue;
            }
            for (Pattern pattern : WALL_CLOCK_READS) {
                if (pattern.matcher(code(source.text)).find()) {
                    offenders.add(source.name + " 命中 " + pattern.pattern());
                }
            }
        }
        assertNoStaleLegacyExcuses(LEGACY_WALL_CLOCK, excused);
        assertTrue(offenders.isEmpty(),
                "human/ world/ boundary/ 里出现了系统时钟读取 —— 仿真时刻只能由外部传入"
                        + "（runtime.SimulationClock）, 这些地方必须改成接收一个 Instant 参数:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * 上一条规则<b>不是空转的</b>。
     *
     * <p>它靠通配符匹配, 而一个写错的通配符会让它扫零个文件、报零个违规、永远绿。
     * 这里用 {@code runtime/SimulationClock.java} 当阳性对照 ——
     * 那个类<b>必须</b>被扫出"读了墙钟"（它正是唯一允许读的地方）。
     *
     * <p>这与上一版那个已被删除的 {@code DhApplicationKnowledgeArchitectureTest} 里
     * 的 {@code theWhitelistIsNotVacuous} 是同一种自检, 也是同一条教训:
     * <b>一个靠"什么都没做"通过的守卫比没有守卫更糟, 因为它会让人以为有人守着。</b>
     */
    @Test
    void theClockRuleWouldActuallyCatchAViolation() {
        Source clock = sources().stream()
                .filter(s -> s.name.endsWith("runtime/SimulationClock.java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到 runtime/SimulationClock.java —— 时钟规则的白名单指着一个不存在的文件"));

        boolean caught = WALL_CLOCK_READS.stream()
                .anyMatch(p -> p.matcher(code(clock.text)).find());

        assertTrue(caught,
                "SimulationClock 里扫不到任何墙钟读取, 说明上面那条规则是空转的 —— "
                        + "要么模式写错了, 要么源码扫描根本没读到文件");
    }

    // ─────────────────────────── 三、没有域枚举 ───────────────────────────

    /**
     * P4 的机器化: 新架构的四个包里不许出现"域扩展型枚举"。
     *
     * <p>判据是那条 <b>enum 合法性检验</b>（在多个类的 javadoc 里出现过）:
     * <blockquote>
     *   这个东西的取值集合是由本设计的内部逻辑决定的, 还是由外部世界的多样性决定的?
     * </blockquote>
     * 由外部多样性决定的, 就不能是 enum —— 因为加一个新取值要改平台源码。
     *
     * <p>被禁的<b>名字形状</b>（{@code enum XxxType}）不是随手挑的: 它们正是旧实现里
     * 那六个词汇表（{@code PlanType} / {@code WorldEventType} / {@code ExternalEventType} /
     * {@code AgentEventType} / {@code ActivityType} / {@code ActionType}）的形状。
     * 用形状而不是用具体类名, 是为了拦住"换个名字再写一个"。
     *
     * <p><b>刻意不禁的东西</b>: {@code ActivityState}、{@code ActionStatus}、
     * {@code PlanLifecycle}、{@code PlanConstraint.Severity}、{@code WeatherCondition} ——
     * 它们的取值集合由本设计的内部逻辑决定（一次活动只有"在做/做完了/放弃了"三种去处,
     * 这是设计决定的, 不是世界决定的）, 所以它们是<b>正确的</b> enum。
     * 这条规则的名字里有 "Type" 这个形状约束, 正好不会误伤它们。
     */
    @Test
    void noDomainExtensionEnumsInTheNewPackages() {
        Pattern domainEnum = Pattern.compile(
                "\\benum\\s+\\w*(Plan|Event|Activity|Action|Device|Application|Command|Intent)Type\\b");

        List<String> offenders = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        for (Source source : sources()) {
            if (!isUnder(source, HUMAN) && !isUnder(source, WORLD)
                    && !isUnder(source, BOUNDARY) && !isUnder(source, REGISTRY)) {
                continue;
            }
            if (excuse(source, LEGACY_DOMAIN_ENUM, excused)) {
                continue;
            }
            if (domainEnum.matcher(code(source.text)).find()) {
                offenders.add(source.name);
            }
        }
        assertNoStaleLegacyExcuses(LEGACY_DOMAIN_ENUM, excused);
        assertTrue(offenders.isEmpty(),
                "新架构里出现了域扩展型枚举 —— 这类取值由外部世界的多样性决定, "
                        + "写成 enum 就等于'第三方要加一种, 得改我们的源码'。"
                        + "改成: 接口 + @DomainType + JSONB 载荷 + 运行时类型注册表:\n  "
                        + String.join("\n  ", offenders));
    }

    // ─────────────────────────── 四、不认识任何具体平台 ───────────────────────────

    /**
     * 她不认识任何一个具体的第三方平台 —— 继承自上一版那条规则的全部内容。
     *
     * <p>V2.2 把这件事实实在在地结构化了: 手机应用是一个<b>接口</b>
     * （{@code DeviceApplication}）, 聊天平台只是它的一个实现类, 由聊天平台自己提供。
     * 所以 {@code human/} 与 {@code world/} 里不该出现任何一个平台的名字、
     * 它的 action id、它的资源 URI 前缀。
     *
     * <p>旧版这条规则的白名单里有一个 {@code tool/ReminderService.java}
     * （"数字人侧通往提醒应用的唯一一道门"）。V2.2 里<b>这个白名单没有了</b> ——
     * 因为不再需要: 平台能力走"实现 {@code DeviceApplication} 接口 + 注册",
     * 不需要在核心包里留一个会说它的话的适配器。白名单消失是这次重构的一个可度量的成果,
     * 所以它值得写在这里, 免得日后有人"为了方便"又加回来。
     *
     * <h2>为什么还管"棋类词汇"</h2>
     * 旧版有<b>两条</b>规则: 一条管平台名（提醒/聊天）, 一条管棋类词汇
     * （{@code tictactoe} / {@code 井字棋} / {@code game.make_move} / {@code checkWinner}）。
     * 它们看着像两件事, 其实是同一句话的两种说法 ——
     * "她不认识任何一个具体的应用"。井字棋只是当时唯一存在的那个第三方应用,
     * 而 {@code game.*} 与 {@code chat.*} 是同一层的、应用自己的动作 id。
     * 所以两条在这里合成一条, 规则不变, 少了一个只对某一种应用生效的特例。
     * 棋类的那几个词仍在下面, 一个没少。
     *
     * <h2>为什么没有 {@code board}</h2>
     * 旧版的棋类词表里有 {@code \bboard\b}。它在当时是准确的 —— 那时源码里
     * 唯一出现的 "board" 就是棋盘。但现在不同了: 这个平台的计划表叫
     * {@link com.luxera.companion.human.life.plan.PlanBoard}, 于是 {@code board}
     * 变成了一个局部变量名（{@code PlanBoard board = ...}）。
     *
     * <p>两种处理: 把它加进允许名单, 或者把它从词表里拿掉。
     * 这里选后者, 因为<b>{@code board} 这个英文词本身并不携带任何应用知识</b> ——
     * 它既可能是棋盘, 也可能是计划表、面板、董事会。一个靠一个普通英文单词工作的守卫,
     * 迟早会被下一个恰好用了这个词的类打红; 而它每一次打红都会消耗一次"这个守卫值不值得留"的
     * 讨论。真正携带棋类知识的是 {@code tictactoe}、{@code 井字棋}、{@code checkWinner}
     * 那些词 —— 它们<b>只在说这件事的时候才会被写出来</b>, 所以它们留下。
     *
     * <p>这条判断有个更一般的形状: <b>守卫的判据应当落在"只有做这件事才会写出来的东西"上</b>,
     * 而不是落在"做这件事时可能用到的东西"上。
     */
    @Test
    void humanAndWorldKnowNothingAboutAnyParticularPlatform() {
        List<Pattern> platformKnowledge = List.of(
                // 平台名与它们的坐标 —— 一个平台的身份
                Pattern.compile("com\\.luxera\\.(chat|reminder|calendar|game|mail)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b(chat|reminder|calendar|game)\\.[a-z_]+\\.[a-z_]+", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(chat|reminder|calendar|game)://", Pattern.CASE_INSENSITIVE),
                // game.make_move / game.state / game.create —— 一段就够, 别等它凑齐两段
                Pattern.compile("\\bgame\\.[a-z_]+", Pattern.CASE_INSENSITIVE),
                // 应用自己的词汇 —— 她不该知道自己在下的叫什么棋
                Pattern.compile("tictactoe", Pattern.CASE_INSENSITIVE),
                Pattern.compile("gomoku", Pattern.CASE_INSENSITIVE),
                Pattern.compile("井字棋"),
                Pattern.compile("五子棋"),
                Pattern.compile("checkWinner"),
                Pattern.compile("parseBoard")
        );

        List<String> offenders = new ArrayList<>();
        for (Source source : sources()) {
            if (!isUnder(source, HUMAN) && !isUnder(source, WORLD)) {
                continue;
            }
            for (Pattern pattern : platformKnowledge) {
                if (pattern.matcher(code(source.text)).find()) {
                    offenders.add(source.name + " 命中 " + pattern.pattern());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "human/ 或 world/ 里出现了具体第三方平台的知识 —— 平台软件应该是"
                        + " DeviceApplication 接口的一个实现类, 由平台方自己提供并注册, "
                        + "我们的核心包里不该有任何一个平台的名字:\n  "
                        + String.join("\n  ", offenders));
    }

    // ─────────────────────────── 四、目录必须是完整的 ───────────────────────────

    /**
     * 目录里那些域 —— 它们里面的事件类型<b>必须</b>在 {@code CoreEventCatalog} 里有一条。
     *
     * <p>判断"一个命名空间属不属于核心"用的就是目录自己的 {@code namespaces()},
     * 而不是在这里再抄一份清单 —— 后者会随目录变化而失修, 而失修的守卫比没有更糟
     * （它给人"这里查过了"的错觉）。
     */
    private static final List<String> CORE_NAMESPACES =
            List.of("environment", "device", "body", "mind", "plan", "object", "system");

    /**
     * 声明成 {@link com.luxera.companion.boundary.event.EventTypeId}、但<b>不是事件类型</b>的那几个。
     *
     * <p>{@code EventTypeId.of("device", "phone")} 这样的写法标识的是<b>对象类型</b>
     * （手机这个对象类型、环境这个对象类型、地点这个对象类型），不是"发生了一件事"。
     * 本类复用 {@code EventTypeId} 这个值对象来表达"一个带命名空间与版本的标识" ——
     * 它恰好就是这个形状，而复用一个值对象不该被误读成一次类型混淆。
     *
     * <p>这一条是<b>显式名单而不是启发式判断</b>，因为两者在语法上长得一模一样，
     * 没有任何结构特征能把它们分开。名单的代价是新加一个对象类型标识时这里会红一次 ——
     * 而那正是想要的：它逼着加的人停下来想一秒"我加的到底是不是一个事件"。
     *
     * <p>{@code world.*} 那两个其实会被 {@link #CORE_NAMESPACES} 顺带滤掉，
     * 放在这里是为了让"哪些不是事件"这件事在一处说得完整。
     */
    private static final List<String> OBJECT_TYPE_IDS =
            List.of("device.phone.v1", "world.environment.v1", "world.place.v1");

    private static final Pattern DECLARED_OF = Pattern.compile(
            "EventTypeId\\.of\\(\\s*\"([a-z0-9.-]+)\"\\s*,\\s*\"([a-z0-9-]+)\"\\s*(?:,\\s*(\\d+)\\s*)?\\)");
    private static final Pattern DECLARED_PARSE = Pattern.compile(
            "EventTypeId\\.parse\\(\\s*\"([a-z0-9.-]+\\.v\\d+)\"\\s*\\)");
    private static final Pattern DECLARED_ANNOTATION = Pattern.compile(
            "@DomainType\\(\\s*\"([a-z0-9.-]+)\"\\s*\\)");

    /**
     * <b>代码里声明了的核心事件类型，一条都不能不在目录里。</b>
     *
     * <h2>为什么需要这一条</h2>
     * 用户对 V2.1 的批评是"没定义 eventtype 有哪些枚举值，不了解的人看完完全不知道
     * 都有哪些 event"。V2.2 的回应是给出一份完整目录 —— 而一份目录要能兑现那句话，
     * 它就必须<b>真的是完整的</b>。
     *
     * <p>而"完整"这件事没有任何机制在保：写事件类的人在自己的文件里声明一个
     * {@code TYPE}，目录在另一个文件里。两边各自都编译得过，测试也全绿 ——
     * 于是漏掉的那条只会在某天有人问"她怎么从来没闻到过味道"时才被发现。
     * 这不是假设：{@code device.phone.sound-emitted.v1} 就是这么漏掉的
     * （扬声器发了一个声音，媒体播放用的一直是它，而它不在这张表里）。
     *
     * <h2>为什么规则是单向的</h2>
     * 只查"代码 → 目录"，不查"目录 → 代码"。反方向是<b>合法</b>的：
     * 目录里可以有代码还没实现的类型（{@code system.clock-tick.v1} 标着"留给将来"），
     * 那是这份目录作为<b>设计声明</b>的一部分 —— 它要先说出"这里有这么一件事"，
     * 实现才有着落。要求两向一致会把这个顺序倒过来，逼着人先写代码再补设计。
     *
     * <h2>它能查什么、不能查什么</h2>
     * 它扫的是<b>源码文本里的字符串字面量</b>。所以
     * {@code EventTypeId.of(SOME_CONSTANT, "x")} 这种把命名空间放进常量的写法它看不见 ——
     * 今天的所有声明都是字面量，所以这条限制不产生漏报；而一个漏报的守卫，
     * 比一个会误报的守卫安全得多（后者会让人开始往白名单里加东西）。
     */
    @Test
    void everyCoreEventTypeTheCodeDeclaresIsInTheCatalogue() {
        Set<String> declared = new LinkedHashSet<>();
        Map<String, String> where = new java.util.LinkedHashMap<>();
        for (Source source : sources()) {
            String text = code(source.text);
            var of = DECLARED_OF.matcher(text);
            while (of.find()) {
                String id = of.group(1) + "." + of.group(2) + ".v" + (of.group(3) == null ? "1" : of.group(3));
                declared.add(id);
                where.putIfAbsent(id, source.name);
            }
            var parsed = DECLARED_PARSE.matcher(text);
            while (parsed.find()) {
                declared.add(parsed.group(1));
                where.putIfAbsent(parsed.group(1), source.name);
            }
            var annotated = DECLARED_ANNOTATION.matcher(text);
            while (annotated.find()) {
                String id = annotated.group(1) + ".v1";
                declared.add(id);
                where.putIfAbsent(id, source.name);
            }
        }

        Set<String> excused = new LinkedHashSet<>();
        declared.removeIf(id -> {
            if (OBJECT_TYPE_IDS.contains(id)) {
                excused.add(id);
                return true;
            }
            return false;
        });
        assertNoStaleLegacyExcuses(OBJECT_TYPE_IDS, excused);

        Set<String> known = new LinkedHashSet<>();
        for (com.luxera.companion.boundary.event.EventTypeId id :
                com.luxera.companion.registry.CoreEventCatalog.typeIds()) {
            known.add(id.toString());
        }

        List<String> missing = new ArrayList<>();
        for (String id : declared) {
            String namespace = id.substring(0, id.lastIndexOf('.'));
            String domain = namespace.contains(".") ? namespace.substring(0, namespace.indexOf('.')) : namespace;
            if (!CORE_NAMESPACES.contains(domain)) {
                continue;   // 插件与应用自己域里的事件 —— 它们本来就不该进核心目录
            }
            if (!known.contains(id)) {
                missing.add(id + "   (声明在 " + where.get(id) + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "有事件类型在代码里声明了、却不在 CoreEventCatalog 里 —— 于是那份"
                        + "“这个世界里能发生哪些事”的清单是假的, 而它恰恰是 V2.2 对"
                        + "“不知道都有哪些 event”这个批评的回应。请把它们登记进目录:\n  "
                        + String.join("\n  ", missing));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Source(String name, String text) {}

    /**
     * 这个文件是不是"名单里的旧文件"。是的话记一笔并返回 {@code true}（调用方应当跳过它）。
     *
     * <p>记那一笔是为了 {@link #assertNoStaleLegacyExcuses} —— 名单的每一项都必须
     * <b>这一次真的用上了</b>, 否则就是一条该删掉的死条目。
     */
    private static boolean excuse(Source source, List<String> allowlist, Set<String> excused) {
        if (allowlist.contains(source.name)) {
            excused.add(source.name);
            return true;
        }
        return false;
    }

    /**
     * 名单里有没有<b>白挂着的</b>条目。
     *
     * <p>它同时管住两件事, 而两件的处置都是"去改名单":
     * <ol>
     *   <li>文件已被删除 —— 迁移的正常结局, 条目该跟着删;</li>
     *   <li>文件还在但违规已修 —— 它不再是 offender, 条目也该删。</li>
     * </ol>
     *
     * <p>少了这一条, 名单只会越来越长: 每一个被修好或删掉的旧文件都会留下一行
     * 没人敢动的注释, 而"名单里有三十个文件、其中二十八个是历史"和
     * "名单里有三个文件"在看的人眼里完全不一样 —— 前者让人不再读它。
     */
    private static void assertNoStaleLegacyExcuses(List<String> allowlist, Set<String> excused) {
        List<String> stale = new ArrayList<>(allowlist);
        stale.removeAll(excused);
        assertTrue(stale.isEmpty(),
                "这份豁免名单里有白挂着的条目 —— 它们既不在源码里了, 也不再违规, "
                        + "但还挂在名单上。请把它们删掉（这份名单只该变短）:\n  "
                        + String.join("\n  ", stale));
    }

    private static boolean isUnder(Source source, String pkg) {
        return source.name.startsWith(pkg.replace('.', '/') + "/");
    }

    /**
     * 去掉整行注释后再扫。
     *
     * <p>与上一版那个已被删除的 {@code DhApplicationKnowledgeArchitectureTest#code} 同样的理由, 但这里
     * <b>多了一层必要性</b>: 本文件里那些"❌ 错误写法"的 javadoc 演示片段
     * （比如 {@code if (temp < 10)} 和 {@code Instant.now()}）本身就是注释 ——
     * 不剥注释的话, 说明"为什么禁止"的那段文字会把自己举报了。
     *
     * <p>只处理整行注释, 不处理行尾注释: 正确切开行尾注释必须懂字符串字面量
     * （{@code "https://…"} 里就有一条斜杠跟着一条斜杠）, 而写错的切法会把真正的代码
     * 一起吃掉。<b>一个会漏报的行尾注释, 好过一个会误报的扫描器</b> ——
     * 后者会让人开始往白名单里加东西, 而那正是守卫失效的第一步。
     */
    private static String code(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 读 {@code src/main/java} 下的全部源码。
     *
     * <p>路径从<b>编译产物的位置</b>反推, 而不是从 {@code user.dir} 猜 ——
     * 后者在 {@code gradle -p} 与 IDE 里可以完全不同, 而这个测试一旦走错目录
     * 就会以"零个文件、零个违规"的姿态变绿。同一个锚点在
     * 上一版那个已被删除的 {@code DhApplicationKnowledgeArchitectureTest} 里已经用过, 那次是对的。
     */
    private static List<Source> sources() {
        Path moduleRoot;
        try {
            URI classes = EventFabric.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path dir = Paths.get(classes);
            moduleRoot = null;
            for (int i = 0; i < 6 && dir != null; i++) {
                if (Files.isDirectory(dir.resolve("src/main/java"))) {
                    moduleRoot = dir;
                    break;
                }
                dir = dir.getParent();
            }
            assertTrue(moduleRoot != null,
                    "从编译产物位置向上找不到含 src/main/java 的模块根: " + Paths.get(classes));
        } catch (Exception e) {
            throw new IllegalStateException("定位模块根目录失败", e);
        }

        Path root = moduleRoot.resolve("src/main/java");
        assertTrue(Files.isDirectory(root), "找不到源码目录: " + root);

        List<Source> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.add(new Source(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读源码失败: " + root, e);
        }
        assertTrue(out.size() > 50,
                "只读到 " + out.size() + " 个源文件 —— 目录定位多半是错的, 这个测试现在什么也没检查");
        return out;
    }
}
