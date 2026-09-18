package com.luxera.companion.boundary.event;

import java.util.Locale;
import java.util.Objects;

/**
 * V2.2 §5.2 —— <b>事件类型的身份</b>: {@code (namespace, name, majorVersion)} 三元组,
 * 不是枚举常量。
 *
 * <h2>为什么类型标识必须是一个可构造的值, 而不是一个枚举</h2>
 * 设计文档 P4 的原话是: <b>"不要用枚举做领域扩展机制"</b>。把事件类型做成枚举, 就等于
 * 把"世界上能发生哪些事"这件事写死在编译期 —— 而这件事在仿真系统里恰恰是<b>永远在变</b>的:
 * 今天有 {@code environment.temperature-changed.v1}, 下个月会有
 * {@code laboratory.centrifuge-finished.v1}, 明年接上真机器人还会有
 * {@code robot.bump-detected.v1}。
 *
 * <p>如果类型是枚举, 加一个事件的代价是: 改枚举 → 改所有 switch → 重新编译整个平台 →
 * 重新部署。而那是一个<b>插件作者</b>该付的代价吗? 不是。插件的定义就是"不改宿主就能扩展"。
 *
 * <h2>那为什么不用裸字符串</h2>
 * 裸字符串 {@code "temperature_changed"} 会立刻退化成"每个人拼自己的写法":
 * {@code temperatureChanged} / {@code TEMPERATURE_CHANGED} / {@code temp_changed} 同时存在,
 * 而它们之间的匹配错误是<b>静默</b>的 —— 事件投出去了, 没有 handler 接, 于是她就"没感觉到冷"。
 * 这类 bug 的表现是"她偶尔行为不对", 没有任何日志能指向根因。
 *
 * <p>所以这里取中间: <b>类型是一个有结构的值对象</b>。它可以在运行时构造、可以从配置读、
 * 可以由插件注册 —— 但它有确定的语法(三段式, 小写, 点分命名空间), 因此可以被校验、
 * 被索引、被拼进日志而不会歧义。
 *
 * <h2>三段各是什么</h2>
 * <table border="1">
 *   <tr><th>段</th><th>含义</th><th>例子</th></tr>
 *   <tr>
 *     <td>{@code namespace}</td>
 *     <td><b>谁</b>定义了这类事件 —— 一个域, 不是一个类。用点分层级来表达归属,
 *         与 Java 包名同构但<b>不绑定</b>包名(插件可以定义 {@code environment.*} 下的类型,
 *         只要它被注册)。</td>
 *     <td>{@code environment} / {@code device.phone} / {@code body}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code name}</td>
 *     <td>在这个域里, <b>发生了什么</b>。用过去式或状态名, 因为事件是"已发生的事实",
 *         不是"应当做的事"。{@code temperature-changed} 是事件;
 *         {@code change-temperature} 是命令, 它属于 Action 不属这里。</td>
 *     <td>{@code temperature-changed} / {@code notification-raised}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code majorVersion}</td>
 *     <td>载荷结构的<b>不兼容</b>版本。加一个可选字段不升版本; 改一个字段的含义必须升。
 *         订阅方按 {@code (namespace, name)} 订阅、按版本解析, 因此新旧版本可以并存 ——
 *         这正是"插件独立演进"的技术前提。</td>
 *     <td>{@code 1}</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 majorVersion 是 int 而不是 semver 字符串</h2>
 * 只有<b>不兼容</b>的变更值得进类型标识。次要版本的差异是"载荷里多了个字段",
 * 那件事由 payload 自己的可选字段表达就够了 —— 把它塞进类型标识会让订阅方被迫
 * 为每一次向后兼容的改动重新订阅一遍, 而那正是我们想要避免的耦合。
 *
 * <p>因此: {@code environment.temperature-changed.v1} 与 {@code ....v2} 是两个类型;
 * 而"v1 的载荷多了 {@code humidity} 字段"仍然只是 v1。
 *
 * <h2>规范性</h2>
 * 构造时校验 {@code namespace} 与 {@code name}, {@link #toString()} 产出
 * {@code namespace.name.vN} 的单行形式, {@link #parse} 是它的逆运算。
 * <b>两者必须互为逆运算</b> —— 这个类型会被写进数据库列、写进日志、写进 HTTP 载荷,
 * 任何一处不一致都会变成一个"重放时找不到 handler"的诡异故障。
 */
public record EventTypeId(String namespace, String name, int majorVersion)
        implements Comparable<EventTypeId> {

    /** 三段之间的分隔符。选点是因为它已经用在 namespace 内部, 不引入新字符。 */
    private static final char SEPARATOR = '.';

    /** 版本段的前缀 —— 单独一个 {@code v} 让"这是版本"在一行里一眼可辨。 */
    private static final String VERSION_PREFIX = "v";

    /**
     * 一个<b>片段</b>允许的字符 —— namespace 的每一节, 以及整个 name。
     *
     * <p>刻意<b>不允许</b>点出现在 {@code name} 里: {@link #parse} 靠"最后一个点之前的
     * 都是 namespace、之后的是 name+version"来切分, 若 name 里也能有点, 切分就歧义了。
     * 层级表达由 namespace 承担 —— 那是它存在的理由。
     */
    private static final String SEGMENT = "[a-z][a-z0-9-]*";

    /**
     * 命名空间允许的形状: <b>点分路径</b>, 每一节都是一个 {@link #SEGMENT}。
     *
     * <h2>这里曾经写的是 {@code [a-z][a-z0-9-]*} —— 一个让平台起不来的 bug</h2>
     * 上面那段关于"层级表达由 namespace 承担"的说明与 {@link #parse} 的 javadoc
     * （"namespace 本身含点, 所以唯一无歧义的切法是从右往左数两段"）都<b>明说了
     * namespace 含点</b>, 而正则却把它一并禁掉了。于是:
     *
     * <pre>
     *   EventTypeId.of("device.phone", "battery-changed")
     *     → IllegalArgumentException("namespace 只允许 [a-z][a-z0-9-]*")
     *
     *   而 CoreEventCatalog 的静态初始化里有一整批这样的事件名
     *     → ExceptionInInitializerError
     *     → 任何引用该 catalog 的类都起不来 → 整个平台启动失败
     * </pre>
     *
     * <p>这个 bug 的性质值得记下来: <b>设计说得对、文档说得对, 只有那一行实现说错了</b>,
     * 而它的症状（平台起不来）与它的原因（一个字符类）离得非常远。它躲过了编译
     * —— 因为出问题的是运行期才执行的静态初始化; 也躲过了此前所有的审查
     * —— 因为没人会去逐字核对一个正则与三段文字描述是否一致。
     *
     * <p>{@code EventTypeIdTest} 里现在有一条针对它的断言
     * （{@code namespace 可以含点}）, 就是不让它再回来。
     */
    private static final String NAMESPACE = SEGMENT + "(?:\\." + SEGMENT + ")*";

    public EventTypeId {
        namespace = requireNamespace(namespace);
        name = requireSegment(name, "name");
        if (majorVersion < 1) {
            throw new IllegalArgumentException(
                    "事件类型的 majorVersion 从 1 起算, 收到 " + majorVersion + " —— "
                            + "0 或负数无法与'忘了设'区分开, 那会让版本号变成一个没人维护的摆设");
        }
    }

    private static String requireNamespace(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("事件类型的 namespace 不能为空");
        }
        rejectCamelCase(raw, "namespace");
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.matches(NAMESPACE)) {
            throw new IllegalArgumentException(
                    "事件类型的 namespace 必须是点分路径, 每一节是小写字母开头的 "
                            + SEGMENT + ", 收到 \"" + raw + "\" —— 例如 device.phone / environment / body"
                            + "。类型标识会被写进数据库列与日志, 宽松的语法会让 temperature_changed /"
                            + " TemperatureChanged / temp-changed 同时存在, 而它们之间的失配是静默的");
        }
        return trimmed;
    }

    private static String requireSegment(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("事件类型的 " + what + " 不能为空");
        }
        rejectCamelCase(raw, what);
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.matches(SEGMENT)) {
            throw new IllegalArgumentException(
                    "事件类型的 " + what + " 只允许小写字母开头的 " + SEGMENT + " 片段, 收到 \"" + raw + "\""
                            + " —— 类型标识会被写进数据库列与日志, 宽松的语法会让 temperature_changed /"
                            + " TemperatureChanged / temp-changed 同时存在, 而它们之间的失配是静默的");
        }
        return trimmed;
    }

    /**
     * 拒绝驼峰 —— <b>在归一化之前</b>。
     *
     * <h2>为什么"统一转小写"这件事在这里必须有一个例外</h2>
     * 本类对大小写是宽松的: {@code Body} 与 {@code body} 都会归一成 {@code body},
     * 因为"她多打了一个大写字母"和"她想表达另一件事"是两回事。但驼峰不是多打了一个大写字母:
     *
     * <pre>
     *   EventTypeId.of("body", "Warmth-Changed")  →  body.warmth-changed.v1   ✅ 写者的意思保住了
     *   EventTypeId.of("body", "WarmthChanged")   →  body.warmthchanged.v1    ❌ 写者多半想要的是上一条
     * </pre>
     *
     * <p>第二行<b>不会报错</b>。它只是静静地生成了一个谁也没打算要的类型名 ——
     * 而那个名字看起来完全正常。等到有人问"为什么 {@code warmth-changed} 的
     * handler 从来没被调用过", 中间已经隔了几天的事件数据和一个订阅失配。
     *
     * <p>所以这一条不归一, 它拒绝 —— 而且拒绝得<b>早于</b>归一步骤, 因为一旦转成小写,
     * {@code WarmthChanged} 与 {@code warmthchanged} 就再也分不开了。同理, 它也拒绝了
     * {@code warmth_changed} 之外的所有"本可以拆成两个词却粘在一起"的写法。
     *
     * <h2>判据: "小写字母后面直接跟大写字母"</h2>
     * 这是驼峰唯一的形状特征。它不误伤下面这些:
     * <ul>
     *   <li>{@code Body} —— 大写在小写之后吗? 不在, 它是第一个字符;</li>
     *   <li>{@code Body.Phone} —— 大写都出现在一节的<b>开头</b>（前一字符是点或分隔符）;</li>
     *   <li>{@code BODY}、{@code WARMTH-CHANGED} —— 全大写是"喊出来", 不是驼峰;
     *       归一成小写之后语义没有任何歧义, 所以照收。</li>
     * </ul>
     */
    private static void rejectCamelCase(String raw, String what) {
        String trimmed = raw.trim();
        for (int i = 1; i < trimmed.length(); i++) {
            if (Character.isUpperCase(trimmed.charAt(i))
                    && Character.isLetterOrDigit(trimmed.charAt(i - 1))
                    && i + 1 < trimmed.length()
                    && Character.isLowerCase(trimmed.charAt(i + 1))) {
                throw new IllegalArgumentException(
                        "事件类型的 " + what + " 里出现了驼峰写法: \"" + raw + "\"。"
                                + "请写成连字符形式（" + trimmed.toLowerCase(Locale.ROOT)
                                + " 这类词应写成 " + camelToKebab(trimmed) + "）—— "
                                + "因为本类对大小写是宽松的, 驼峰会被悄悄归一成一个"
                                + "和它长得不像的名字, 而那种失配在两条记录对不上时才会被发现");
            }
        }
    }

    /**
     * 把驼峰拆成连字符 —— <b>只为出错信息</b>, 不参与归一化。
     *
     * <p>它的存在只为一件事: 让上面那句报错直接给出"你多半想要的是这个"。
     * 一个只说"不许驼峰"的错误会让人改成 {@code warm th changed} 或是干脆全小写,
     * 而给出目标写法能让正确的那条路最短。
     */
    private static String camelToKebab(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && text.charAt(i - 1) != '.'
                    && text.charAt(i - 1) != '-') {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 当前版本(v1)。<b>绝大多数新事件都该用它</b> —— 换版本是给破坏性变更留的。 */
    public static EventTypeId of(String namespace, String name) {
        return new EventTypeId(namespace, name, 1);
    }

    public static EventTypeId of(String namespace, String name, int majorVersion) {
        return new EventTypeId(namespace, name, majorVersion);
    }

    /**
     * 解析 {@code namespace.name.vN}。
     *
     * <p><b>不用正则切分</b>: namespace 本身含点, 所以唯一无歧义的切法是
     * "从右往左数两段" —— 最后一段是 {@code vN}, 倒数第二段是 name, 剩下的全部是 namespace。
     * 用正则去猜哪一段是版本, 在 namespace 恰好以 {@code v2} 这样的片段结尾时会切错,
     * 而那是一个只在特定命名下才出现的故障。
     */
    public static EventTypeId parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("事件类型标识不能为空");
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "事件类型标识至少要有 namespace.name.vN 三段, 收到 \"" + text + "\"");
        }
        String versionPart = parts[parts.length - 1];
        if (!versionPart.startsWith(VERSION_PREFIX) || versionPart.length() < 2) {
            throw new IllegalArgumentException(
                    "事件类型标识必须以 v<数字> 结尾, 收到 \"" + text + "\" —— "
                            + "少了版本段意味着这个标识无法表达'载荷结构变过'");
        }
        int version;
        try {
            version = Integer.parseInt(versionPart.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "事件类型标识的版本段 \"" + versionPart + "\" 不是数字: \"" + text + "\"", e);
        }
        String name = parts[parts.length - 2];
        String namespace = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 2));
        return new EventTypeId(namespace, name, version);
    }

    /**
     * 尽力解析 —— {@link #parse} 的"垃圾进, 空出"版本。
     *
     * <p>用在<b>读取外部数据</b>的地方(反序列化旧记录、解析插件清单)。那里的失败不该
     * 让调用方去写 try/catch, 因为"这条历史记录的版本号坏了"通常应当被跳过并记一条日志,
     * 而不是让整次事件重放崩掉。
     */
    public static java.util.Optional<EventTypeId> tryParse(String text) {
        try {
            return java.util.Optional.of(parse(text));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 订阅键 —— <b>不含版本</b>。
     *
     * <p>这是"按类型订阅"该用的东西: 一个只想在温度变化时算保暖值的 handler,
     * 不该因为载荷从 v1 升到 v2 就静默地收不到事件。版本是<b>解析载荷</b>时才需要的,
     * 而解析失败的路径是显式的(记 {@code plan.validation-failed.v1} 那样的失败事件),
     * 不是静默的。
     */
    public String subscriptionKey() {
        return namespace + SEPARATOR + name;
    }

    /** 这个类型是否定义在给定命名空间之下(含其子命名空间)。 */
    public boolean under(String candidateNamespace) {
        return candidateNamespace != null
                && (namespace.equals(candidateNamespace)
                || namespace.startsWith(candidateNamespace + SEPARATOR));
    }

    public EventTypeId withMajorVersion(int newMajor) {
        return new EventTypeId(namespace, name, newMajor);
    }

    /** {@code namespace.name.vN} —— {@link #parse} 的逆运算。 */
    @Override
    public String toString() {
        return namespace + SEPARATOR + name + SEPARATOR + VERSION_PREFIX + majorVersion;
    }

    /**
     * 排序: 先 namespace, 再 name, 最后版本。
     *
     * <p>实现 {@link Comparable} 是为了让目录({@code CoreEventCatalog})的输出稳定 ——
     * 一个每次迭代顺序都不同的目录, 会让"文档里的清单"和"运行时的清单"悄悄分叉。
     */
    @Override
    public int compareTo(EventTypeId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        if (byNamespace != 0) {
            return byNamespace;
        }
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : Integer.compare(majorVersion, other.majorVersion);
    }

    /** 两个类型是否"同名不同版本"。版本迁移的判定用的就是这个。 */
    public boolean sameType(EventTypeId other) {
        return other != null && namespace.equals(other.namespace) && name.equals(other.name);
    }

    public static boolean sameType(EventTypeId a, EventTypeId b) {
        return a != null && a.sameType(b);
    }

    /** 非空的便捷判定, 供可选字段与集合筛选用。 */
    public static boolean isPresent(EventTypeId id) {
        return Objects.nonNull(id);
    }
}
