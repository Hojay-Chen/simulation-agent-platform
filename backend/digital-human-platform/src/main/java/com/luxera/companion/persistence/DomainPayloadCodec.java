package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.3 —— <b>{@code PolymorphicSerializer} 与"关系型列 + JSON 列"之间那一段</b>。
 *
 * <h2>它比 {@link PolymorphicSerializer} 多了什么</h2>
 * {@code PolymorphicSerializer} 只回答"对象 ⇄ 带 {@code _type} 的 Map"。
 * 而 §7.2 的表要求的是另一种形状: <b>类型三元组散在三个关系型列上</b> ——
 *
 * <pre>
 *   world_event
 *   ├── type_namespace  VARCHAR   'life.activity'
 *   ├── type_name       VARCHAR   'sleep'
 *   ├── major_version   INT       1
 *   └── payload_json    TEXT      {"_type":"life.activity.sleep.v1","startedAt":"..."}
 * </pre>
 *
 * <p>于是有两个必须回答的问题, 而它们正是本类存在的理由:
 * <ol>
 *   <li><b>写的时候</b> {@code _type} 从哪来、三个列怎么填;</li>
 *   <li><b>读的时候</b>以谁为准 —— 三个列, 还是载荷里那个 {@code _type}?</li>
 * </ol>
 *
 * <h2>为什么类型要<b>冗余</b>存两份(三个列 + 载荷里一个 {@code _type})</h2>
 * 看起来是纯粹的浪费: 多存一份就要保证两份一致, 而不一致就是一个新 bug 来源。
 * 三个替代方案, 每个都更糟:
 * <table border="1">
 *   <tr>
 *     <th>方案</th><th>会出什么事</th>
 *   </tr>
 *   <tr>
 *     <td>只存三个列, 载荷里不放 {@code _type}</td>
 *     <td>任何一条不是通过本类读的数据(人工导出的 JSON、行为分析脚本、另一个服务
 *         直接 {@code SELECT payload_json})都<b>读不出它是什么类型</b>。
 *         而"这一列 JSON 是给人看的"是 {@code PolymorphicSerializer} 已经写下的承诺
 *         (见它关于"为什么 {@code _type} 是平铺的而不是包一层"的论证)。</td>
 *   </tr>
 *   <tr>
 *     <td>只在载荷里放 {@code _type}, 不存三个列</td>
 *     <td><b>索引与约束全部失效。</b> 想回答"这类事件最近发生过吗"要写成
 *         {@code WHERE payload_json::jsonb ->> '_type' = 'x.y.v1'} —— 一个无法用普通
 *         B-tree 索引、且写法在每个数据库上都不一样的前缀匹配。
 *         而 {@code world_event} 最重要的一条查询恰恰是"某一类事件"。</td>
 *   </tr>
 *   <tr>
 *     <td>只存类名({@code com.luxera...SleepActivity})</td>
 *     <td>把<b>代码结构</b>钉进了数据。一次重命名会让库里的历史全部读不回来 ——
 *         而历史可读是 {@code plan.revision-created.v1} 那套"旧版本永不删除"
 *         承诺的前提。类型名({@code life.activity.sleep})是<b>数据</b>, 类名是<b>实现</b>。</td>
 *   </tr>
 * </table>
 *
 * <h2>两份冲突时, <b>关系型列赢</b> —— 方向是刻意选的</h2>
 * 读写两条路径的权威源不同, 这是本类唯一一处"不对称", 所以要说清楚:
 * <ul>
 *   <li><b>写:</b> 以对象为准。{@code _type} 由 {@link DomainTypeRegistry#typeIdOf(Object)}
 *       算出来, 然后<b>同时</b>写进三个列和载荷 —— 两条路各写各的, 但它们来自同一次计算,
 *       所以在写入点不可能不一致;</li>
 *   <li><b>读:</b> 以三个列为准。{@link #read} 拿列拼出类型名, <b>覆盖</b>掉载荷里那个
 *       {@code _type} 再交给反序列化器。<br>
 *       为什么这样选: 三个列是<b>有结构、可校验、可加索引</b>的那一份 ——
 *       {@code EventTypeId.parse} 会拒绝一个拼错的名字, 而载荷里的字符串不会。
 *       若反过来以载荷为准, 那么一次"有人手工修了三个列想把类型改对"的操作会
 *       <b>静默无效</b> —— 那是最难查的一类故障: 你的修复看起来成功了, 行为没变。</li>
 * </ul>
 *
 * <p>反方向的兼容仍然保留: 三个列<b>全空</b>时(历史行、或由外部工具写入的行),
 * 读取回退到载荷里的 {@code _type}。这不是宽容, 是"库里已经存在的数据必须能读" ——
 * 见 {@link #withAuthoritativeType}。
 *
 * <h2>为什么存的是 {@code text} 列而不是 Postgres 的 {@code jsonb}</h2>
 * 这是<b>沿用既有约定</b>, 不是本层的选择。本仓从 V9 起的每一列 JSON 都是
 * {@code @Convert(converter = StringMapConverter.class) @Column(columnDefinition = "text")}
 * —— 参见 {@code world/WorldEvent#payload}、{@code runtime/WorldEventLog#payload}、
 * {@code persona/PersonaJsonConverter}。§7.2 的文档写的是 {@code JSONB};
 * <b>这里以既有约定为准</b>, 理由是三条具体的、而不是风格上的:
 * <ol>
 *   <li>{@code ddl-auto: update} <b>建不出 {@code jsonb} 列</b>。它按 Hibernate 的类型映射建表,
 *       而本仓没有注册任何 JSON 类型(没有 {@code hypersistence-utils}, 没有
 *       {@code @Type(type = "jsonb")})。写 {@code columnDefinition = "jsonb"} 能骗过建表,
 *       但 Hibernate 读的时候会拿一个 {@code PGobject} 去调 {@code AttributeConverter},
 *       而那不是 {@code String} —— 结果是<b>每一行都反序列化失败</b>, 一个只在真机上才暴露的故障;</li>
 *   <li>本仓已经有一个 {@link com.luxera.companion.common.convert.StringMapConverter},
 *       它把 {@code Map<String,Object>} 走 {@code JsonCodec} 存成文本。新表用它,
 *       意味着"库里这一列怎么读"这个问题在整个仓里有<b>唯一</b>一个答案;</li>
 *   <li>§7.2 要 {@code jsonb} 的那个理由(能在数据库侧按字段查询)在本仓的规模上不成立:
 *       这些表的行数由"一个 agent 的一天"决定, 而不是由"全体用户"决定,
 *       而真正需要走索引的字段(类型、时间、外键)已经在关系型列上了。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * 本类无状态, 只持有构造时注入的注册表与序列化器。那两个对象在装配完成后<b>不该再被改</b>
 * (见 {@code DomainTypeRegistry} 的"装配顺序决定谁赢"), 因此本类可以自由地被多个线程共享。
 */
@Slf4j
public class DomainPayloadCodec {

    /**
     * 从"三个列"拼出类型名时用的分隔符 —— 与 {@link EventTypeId#toString()} 完全一致。
     *
     * <p>不自己拼一个 {@code ":"} 之类的分隔符, 而是走 {@link EventTypeId#parse} /
     * {@link EventTypeId#toString} 的往返。理由是这两件事必须永远互为逆运算:
     * 一旦本类自己拼字符串, 就出现了<b>第二套类型名语法</b> —— 而两套语法之间
     * 的任何一处差异都会表现为"某类历史数据读不回来"。
     */
    private final PolymorphicSerializer serializer;

    private final DomainTypeRegistry registry;

    public DomainPayloadCodec(DomainTypeRegistry registry) {
        this(registry, new PolymorphicSerializer(registry));
    }

    /**
     * 用一个已经配好的序列化器。
     *
     * <p>这个重载存在的理由很具体: {@code PolymorphicSerializer} 允许传入自定义
     * {@code ObjectMapper}, 而<b>换 mapper 会改变库里 JSON 的写法</b>
     * (例如时间从 ISO 字符串变成 epoch 数字)。如果本类偷偷用一个自己的默认 mapper,
     * 那么"写的时候用 A、读的时候用 B"就会变成一个只影响某几个字段的静默错位。
     * 让调用方把序列化器传进来, 这个风险就没有了。
     */
    public DomainPayloadCodec(DomainTypeRegistry registry, PolymorphicSerializer serializer) {
        this.registry = Objects.requireNonNull(registry, "类型注册表不能为空");
        this.serializer = Objects.requireNonNull(serializer, "多态序列化器不能为空");
    }

    public PolymorphicSerializer serializer() {
        return serializer;
    }

    public DomainTypeRegistry registry() {
        return registry;
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 把领域对象摊成"三个类型列 + 一列 JSON"。
     *
     * <p>调用方拿到的 {@link PersistedForm} 就是它能直接灌进实体的一组值 ——
     * 本方法<b>不认识任何实体</b>, 这是刻意的: 实体有十一个, 而"怎么把类型拆成三元组"
     * 只有一套逻辑。让每个 store 各拆一遍, 等于把这个逻辑复制十一份。
     */
    public PersistedForm write(Object domainObject) {
        Objects.requireNonNull(domainObject, "要持久化的对象不能为空");
        Map<String, Object> payload = serializer.toMap(domainObject);
        return PersistedForm.of(payload);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 从"三个类型列 + 一列 JSON"读回对象, 并校验它是期望的形态。
     *
     * @param typeNamespace / typeName / majorVersion 三个关系型列。<b>三者任一为空</b>时
     *        回退到载荷里的 {@code _type}（见 {@link #withAuthoritativeType}）
     * @param expected      期望的类型或接口（例如 {@code WorldEvent.class}）。
     *                      传它是为了把"这一行其实是别的东西"这个问题<b>在边界上</b>暴露 ——
     *                      否则它会以一个远得多的 {@code ClassCastException} 出现
     * @throws PolymorphicSerializer.UnknownDomainTypeException 那个类型名没有任何类声明过。
     *         <b>这是刻意的, 不是缺陷</b> —— 见 {@link #tryRead} 与 {@link #withAuthoritativeType} 的说明
     */
    public Object read(String typeNamespace, String typeName, Integer majorVersion,
                       Map<String, Object> payload, Class<?> expected) {
        Map<String, Object> body = withAuthoritativeType(typeNamespace, typeName, majorVersion, payload);
        return serializer.fromMap(body, expected);
    }

    public <T> T read(String typeNamespace, String typeName, Integer majorVersion,
                      Map<String, Object> payload, Class<T> expected, boolean checked) {
        return expected.cast(read(typeNamespace, typeName, majorVersion, payload, (Class<?>) expected));
    }

    /**
     * 读历史时的宽容版本: 读不回来时返回空<b>并记一条 WARN</b>, 而不是让整次回放崩掉。
     *
     * <p>这个区别值得写下来, 因为它是本层唯一允许"吞掉一个异常"的地方:
     * <table border="1">
     *   <tr><th>场景</th><th>该用哪个</th><th>为什么</th></tr>
     *   <tr>
     *     <td>恢复"她此刻在做的那件事"</td><td>{@link #read}（抛）</td>
     *     <td>这里读不出来就意味着状态恢复不完整, 而一个状态不全的 agent
     *         比一个起不来的 agent 更难查 —— 她会带着半个自己继续活</td>
     *   </tr>
     *   <tr>
     *     <td>回放一批历史事件做行为分析</td><td>{@link #tryRead}</td>
     *     <td>某个三方应用被卸载了, 它的历史事件还在库里 —— <b>这是正常处境,
     *         不是故障</b>。让整次分析因为其中一条读不出来而失败, 等于让
     *         "卸载一个插件"变成"历史数据不可用"</td>
     *   </tr>
     * </table>
     */
    public Optional<Object> tryRead(String typeNamespace, String typeName, Integer majorVersion,
                                    Map<String, Object> payload) {
        try {
            return Optional.of(read(typeNamespace, typeName, majorVersion, payload, (Class<?>) null));
        } catch (PolymorphicSerializer.UnknownDomainTypeException e) {
            log.warn("[Persistence] 跳过一条读不回来的数据（类型 {}）: {}",
                    describeType(typeNamespace, typeName, majorVersion, payload), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 三个类型列全空时, 回退到载荷里的 {@code _type}。
     *
     * <h2>为什么需要这个回退</h2>
     * 它服务的是一类真实存在、且会越来越多的行:
     * <ul>
     *   <li><b>历史行</b> —— 本层落地之前由旧代码写下的、只有一列 JSON 的记录;</li>
     *   <li><b>外部工具写入的行</b> —— 导入脚本、行为分析的回填、人工修数据。
     *       它们手上只有一份 JSON, 而那正是 {@code _type} 平铺在 JSON 里的价值
     *       （见 {@code PolymorphicSerializer} 的论证）。</li>
     * </ul>
     *
     * <h2>为什么只在"全空"时回退, 而不是"任一为空"</h2>
     * 半填的三个列是一个<b>必须被看见</b>的损坏, 而不是一个可以绕过的历史遗留。
     * 三个列里有两个是空的, 说明写入路径上有 bug（或者有人手工改了一半）——
     * 这时悄悄用载荷里的值补上, 会让那处 bug 永远不被发现, 直到某一天
     * 关系型列的查询与载荷的查询给出<b>不同的答案</b>。
     *
     * <h2>"全空"里"空"的准确含义</h2>
     * 两个字符串列是 {@code null} 或空白, 版本列是 {@code null} <b>或非正数</b>
     * —— 判断集中在 {@link #hasVersion(Integer)}, 那里写了"为什么 {@code 0}
     * 必须算空"。这一条不是细节: 把 {@code 0} 当成"填了"会让未注册类型的行
     * 全部以"数据损坏"的名义抛异常, 而那类行是本层的常态而不是故障。
     */
    private Map<String, Object> withAuthoritativeType(String typeNamespace, String typeName,
                                                      Integer majorVersion, Map<String, Object> payload) {
        Objects.requireNonNull(payload, "载荷不能为空 —— 一行的 JSON 列读出来是 null 时, "
                + "调用方该决定它是空对象还是损坏, 不该由本层替它决定");
        boolean anyPresent = isPresent(typeNamespace) || isPresent(typeName) || hasVersion(majorVersion);
        if (!anyPresent) {
            return payload;
        }
        if (!isPresent(typeNamespace) || !isPresent(typeName) || !hasVersion(majorVersion)) {
            throw new IllegalStateException(
                    "类型三元组只填了一部分（namespace=" + typeNamespace + ", name=" + typeName
                            + ", version=" + majorVersion + "）—— 这是一处数据损坏, 不是历史遗留。"
                            + "请修数据, 不要靠回退掩盖它: 否则关系型列的查询与载荷里的 _type "
                            + "会给出不同的答案, 而那种分歧没有任何日志能指向根因");
        }

        String typeId = EventTypeId.of(typeNamespace, typeName, majorVersion).toString();
        Map<String, Object> body = new LinkedHashMap<>(payload);
        Object inPayload = body.get(PolymorphicSerializer.TYPE_FIELD);
        if (inPayload != null && !typeId.equals(String.valueOf(inPayload))) {
            // 两份不一致 —— 以列为准（见类注释"两份冲突时关系型列赢"）, 但必须说出来。
            // 静默地以列为准会让"有人手工修了载荷里的 _type 却以为生效了"变成一个谜题
            log.warn("[Persistence] 类型列的 {} 与载荷里的 _type {} 不一致 —— "
                            + "以列为准。若你刚刚手工改过其中一份, 另一份没改, 就是这里",
                    typeId, inPayload);
        }
        body.put(PolymorphicSerializer.TYPE_FIELD, typeId);
        return body;
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 主版本号"算不算填了" —— <b>零与小等于零都不算</b>。
     *
     * <h2>为什么不能只判 {@code != null}</h2>
     * 因为列的类型是 {@code int} 而这里的形参是装箱的 {@code Integer}:
     * 一条<b>没有类型</b>的行（写入时对象没注册类型名, 见
     * {@code PersistedForm.UNTYPED_VERSION}）读出来是 {@code 0},
     * 而 {@code 0 != null} 是 {@code true}。
     * 只判非空会让"三个列全空"这条<b>常态</b>被判成"半填"——
     * 于是每一个未注册类型的对象都读不回来, 而且是以"数据损坏"的名义抛异常,
     * 不是以"读不出来"的名义进 {@code unreadable}。
     * <p>这不是一个理论上的边界: {@link #withAuthoritativeType} 的调用者里有
     * 三条路径（{@code WorldEventStore} 的回放、{@code EffectLedgerStore} 的恢复、
     * {@code ActivityStore} 的活动还原）都会走到未注册的行, 而它们全都会被这一处误判
     * 变成整批失败 —— 一个卸了插件的三方类型会拖垮整个 agent 的恢复。
     * <p>真正的半填（{@code namespace="plan"} 而 {@code version=0}）仍然会被抓住:
     * 那时 {@code namespace}/{@code name} 里至少有一个非空, 于是
     * {@code anyPresent} 为真而三缺一, 抛异常。这是刻意的区分 ——
     * <b>"三个都空"是常态, "填了一半"是损坏</b>。
     */
    private static boolean hasVersion(Integer majorVersion) {
        return majorVersion != null && majorVersion > 0;
    }

    /** 诊断用: 尽量说清"这是哪一行", 哪怕三个列是空的。 */
    private static String describeType(String typeNamespace, String typeName,
                                       Integer majorVersion, Map<String, Object> payload) {
        if (isPresent(typeNamespace) && isPresent(typeName) && hasVersion(majorVersion)) {
            return typeNamespace + "." + typeName + ".v" + majorVersion;
        }
        if (payload != null) {
            Object declared = payload.get(PolymorphicSerializer.TYPE_FIELD);
            if (declared != null) {
                return String.valueOf(declared);
            }
            Object untyped = payload.get(PolymorphicSerializer.UNTYPED_MARKER);
            if (untyped != null) {
                // 未注册类型的那一行: 唯一能说清"它本该是什么"的东西就是写入时打下的类名。
                // 报"三个列都为空"会把最有用的那条线索丢掉。
                return "未注册类型（写入时的类: " + untyped + "）";
            }
        }
        return "(类型列与 _type 都为空)";
    }

    /**
     * 一组"可以直接灌进实体"的值。
     *
     * <p>它是本层与实体之间<b>唯一</b>的交接物。做成不可变 record 的理由与
     * {@code AbstractActivity.CommonFields} 相同: 一个六字段的可变载体,
     * 迟早会有人在某一条路径上漏填一个字段, 而漏填的后果是那一列变成 null ——
     * 一个不会报错、只在查历史时才被发现的空洞。
     *
     * @param typeNamespace 类型名的命名空间段（{@code life.activity}）
     * @param typeName      类型名的名字段（{@code sleep}）
     * @param majorVersion  主版本。{@code 0} 表示<b>没有类型</b>（写入时对象没注册类型名）
     * @param payload       带 {@code _type} 的平铺 JSON 载荷
     */
    public record PersistedForm(String typeNamespace,
                                String typeName,
                                int majorVersion,
                                Map<String, Object> payload) {

        /** 没有类型名的载体 —— {@code DomainTypeRegistry.typeIdOf} 返回空时的形状。 */
        public static final String UNTYPED_NAMESPACE = "";
        public static final String UNTYPED_NAME = "";
        public static final int UNTYPED_VERSION = 0;

        public PersistedForm {
            typeNamespace = typeNamespace == null ? UNTYPED_NAMESPACE : typeNamespace;
            typeName = typeName == null ? UNTYPED_NAME : typeName;
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }

        /**
         * 从一个已经打好 {@code _type} 的载荷拆出三元组。
         *
         * <p>拆不出来时<b>不抛</b>, 而是给一个"没有类型"的载体并保留整份载荷。
         * 理由与 {@code PolymorphicSerializer.toMap} 里那段"照写 + 打标 + 告警"完全一致:
         * 一个三方类型忘了注册, <b>让它读不回来</b> 远好过 <b>让这条数据丢掉</b>。
         * 前者是一条 WARN 加一份完好的 JSON, 后者是不可逆的。
         *
         * <p>注意载荷里此时留着 {@code _untyped}（由序列化器打的标）——
         * 那是<b>故意</b>的: 它是"为什么这条数据读不回来"的唯一线索,
         * 剥掉它就等于把诊断信息从数据里删掉。
         */
        public static PersistedForm of(Map<String, Object> payload) {
            Objects.requireNonNull(payload, "载荷不能为空");
            Object raw = payload.get(PolymorphicSerializer.TYPE_FIELD);
            if (raw == null) {
                return new PersistedForm(UNTYPED_NAMESPACE, UNTYPED_NAME, UNTYPED_VERSION, payload);
            }
            Optional<EventTypeId> parsed = EventTypeId.tryParse(String.valueOf(raw));
            if (parsed.isEmpty()) {
                log.warn("[Persistence] 载荷里的 {} 不是合法的类型名: \"{}\" —— "
                                + "数据不丢, 但它在关系型列里没有类型可查。"
                                + "类型名必须形如 namespace.name.vN",
                        PolymorphicSerializer.TYPE_FIELD, raw);
                return new PersistedForm(UNTYPED_NAMESPACE, UNTYPED_NAME, UNTYPED_VERSION, payload);
            }
            EventTypeId id = parsed.get();
            return new PersistedForm(id.namespace(), id.name(), id.majorVersion(), payload);
        }

        /** 类型名, 形如 {@code life.activity.sleep.v1}。没有类型时为空。 */
        public String typeId() {
            return hasType() ? EventTypeId.of(typeNamespace, typeName, majorVersion).toString() : "";
        }

        public boolean hasType() {
            return !typeNamespace.isBlank() && !typeName.isBlank() && majorVersion > 0;
        }

        /** 载荷的可读副本 —— 实体存的就是它。 */
        public Map<String, Object> payloadOrEmpty() {
            return payload;
        }
    }
}
