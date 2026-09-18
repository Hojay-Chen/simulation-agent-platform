package com.luxera.companion.persistence.store;

import com.luxera.companion.boundary.event.ScheduledEvent;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.WorldEventRecord;
import com.luxera.companion.persistence.repository.WorldEventRecordRepository;
import com.luxera.companion.registry.CoreEventCatalog;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.3 —— {@code world_event} 的读写路径: <b>事件进库、事件出库、事件被投递</b>。
 *
 * <h2>它在 §7.3 的位置</h2>
 * §7.3 的图是:
 * <pre>
 *   写: 领域对象 ──DomainTypeRegistry.typeIdOf──► 三个类型列
 *              └──PolymorphicSerializer.toMap──► payload_json（含 _type）
 *   读: 三个类型列 + payload_json ──DomainPayloadCodec──► 领域对象
 * </pre>
 * 中间那一步（"谁能当这个对象"）没有任何规定, 而<b>它就是本类</b>。
 * {@code DomainPayloadCodec} 不认识任何实体（那是它刻意的: 实体有十一个,
 * 而"怎么把类型拆成三元组"只有一套逻辑）, 于是把"一行的字段怎么填"
 * 放在每一张表自己的 store 里。
 *
 * <h2>类别（category）从哪来 —— <b>从对象本身, 不从目录</b></h2>
 * 这是本类最容易做错的一处, 所以写在最前面。三个候选来源:
 * <table border="1">
 *   <tr><th>来源</th><th>为什么不行</th></tr>
 *   <tr>
 *     <td>{@code CoreEventCatalog.find(typeId)} 的 {@code EventSpec.category()}</td>
 *     <td><b>它只覆盖内置事件。</b> 三方接入的 {@code petfeeder.bowl-emptied} 不在目录里,
 *         于是它的类别是 {@code NULL} —— 而 {@code NULL} 的后果是
 *         <b>{@code StimulusReplayStore} 永远捞不到它</b>。
 *         表现是"她重启之后漏掉了那几条刺激", 且没有任何报错 ——
 *         目录是"平台认识的事件"的清单, 不是"事件是什么"的定义</td>
 *   </tr>
 *   <tr>
 *     <td>由事件的实现类自己声明一个 {@code category()} 方法</td>
 *     <td>那就是在 {@link WorldEvent} 上再加一个必须实现的成员, 而"类别"是
 *         <b>平台的</b>分类, 不是事件的属性 —— 一个宠物的实现类凭什么要理解
 *         {@code STATE_EFFECT} 这个词? 这会逼着每个三方实现去猜它该填哪个</td>
 *   </tr>
 *   <tr>
 *     <td><b>{@code instanceof} 三个标记接口</b>（本实现）</td>
 *     <td>—— 类别由<b>已经存在的</b>接口决定: {@code StateEffectEvent} / {@code SensoryEvent}
 *         / {@code ScheduledEvent} 各自就是一个类别。这三条判断不需要目录、
 *         不需要三方做任何声明, 且<b>编译期就成立</b>（{@code instanceof} 不认识
 *         的接口编译不过）</td>
 *   </tr>
 * </table>
 *
 * <p>类别是<b>多值</b>的, 因为一个事件可以同时是几类 ——
 * {@code environment.temperature-changed} 既改变账本又需要她知道
 * （{@code ContinuousEffectLedger} 的类注释就是这么说的）。存储形态是逗号分隔,
 * 顺序由本类的 {@code instanceof} 顺序<b>固定</b>: 没有固定顺序的话,
 * 两个字段完全相同的事件会因为 JVM 的接口遍历顺序而在两行里写出不同的字符串,
 * 于是"按类别筛"的结果会依赖写入顺序。
 *
 * <h2>只 append, 只更新一列</h2>
 * 本类<b>不提供</b>任何删除方法, 也<b>不提供</b>改载荷的方法。唯一允许的 UPDATE 是
 * {@link #markPublished} 写 {@code published_at}（且只在它为 {@code null} 时写）。
 * 理由是 {@code WorldEvent} 的类注释说的那句话: <b>事件是历史记录</b>。
 * 一张可以改历史表上的"她当时听到了什么"就不再是一个可以被引用的答案。
 *
 * <h2>不读时钟</h2>
 * 本类的三个时间来源全部是参数: {@code event.occurredAt()}（事件自己的时刻）、
 * {@code publishedAt}（调用方给的投递时刻）、{@code @CreationTimestamp}
 * （Hibernate 写的"这一行什么时候入库"）。<b>没有一处</b>
 * {@code Instant.now()} —— 见 {@code persistence/package-info} 关于
 * "仿真时刻 vs 审计时刻"的说明。
 */
@Slf4j
public class WorldEventStore {

    /** 逗号分隔多值单元格的分隔符 —— 与 {@code WorldEventRecord.getCategory()} 的说明一致。 */
    private static final char CATEGORY_SEPARATOR = ',';

    private final WorldEventRecordRepository repository;
    private final DomainPayloadCodec codec;

    public WorldEventStore(WorldEventRecordRepository repository, DomainPayloadCodec codec) {
        this.repository = Objects.requireNonNull(repository, "事件仓库不能为空");
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 记一件事。返回写入的那一行。
     *
     * <p>{@code worldId} 是参数而不是从事件上取: {@link WorldEvent} <b>没有</b>
     * "我在哪个世界"这个成员（它的 javadoc 明确说了事件不该持有活对象引用）。
     * 于是这个归属关系由调用方给 —— 而调用方本来就站在某一个世界里。
     *
     * @return 写入的实体。它的 {@code id} 由 {@code @PrePersist} 生成,
     *         因此在<b>真实数据库路径</b>上 {@code save} 返回之后才有值;
     *         纯内存测试里它是 {@code null}, 调用方不该依赖它
     */
    public WorldEventRecord append(String worldId, WorldEvent event) {
        Objects.requireNonNull(worldId, "事件必须属于某一个世界 —— 没有世界的事件查不出来");
        Objects.requireNonNull(event, "要落库的事件不能为空");

        DomainPayloadCodec.PersistedForm form = codec.write(event);

        WorldEventRecord record = new WorldEventRecord();
        record.setWorldId(worldId);
        record.setTypeNamespace(form.typeNamespace());
        record.setTypeName(form.typeName());
        record.setMajorVersion(form.majorVersion());
        record.setCategory(categoryOf(event));
        record.setPayloadJson(form.payload());
        record.setSourceObjectId(event.sourceObjectId());
        record.setOccurredAt(event.occurredAt());
        // 刻意<b>不</b>设 publishedAt: 刚发生的事还没投进她的意识。
        // 让它默认 null 而不是由调用方传 —— "写入即已投递"会让崩溃恢复认为无事可做
        return repository.save(record);
    }

    /**
     * 事件对象 → 那一格逗号分隔的类别。
     *
     * <p>顺序固定为 {@code STATE_EFFECT, SENSORY, SCHEDULED}（即 {@code CoreEventCatalog.Category}
     * 的声明顺序）。同一次写入里, 这个字符串必须可复现: 否则两行字段完全相同的事件
     * 会有不同的 {@code category}, 而"这两条是同一类吗"变成了一个需要解析的问题。
     *
     * <p>一个三类都不实现的事件（只实现 {@link WorldEvent}）得到的是<b>空串</b>
     * 而不是 {@code null}: 空串是"它确实不属于任何一类"这个事实的表达, 而
     * {@code null} 会与"这一列没写"混淆。这个区别在读的时候很要紧 ——
     * 见 {@link #hasCategory}。
     */
    public static String categoryOf(WorldEvent event) {
        Objects.requireNonNull(event, "要判类别的事件不能为空");

        List<String> parts = new ArrayList<>(3);
        if (event instanceof StateEffectEvent) {
            parts.add(CoreEventCatalog.Category.STATE_EFFECT.name());
        }
        if (event instanceof SensoryEvent) {
            parts.add(CoreEventCatalog.Category.SENSORY.name());
        }
        if (event instanceof ScheduledEvent) {
            parts.add(CoreEventCatalog.Category.SCHEDULED.name());
        }

        crossCheckAgainstCatalog(event, parts);
        return String.join(String.valueOf(CATEGORY_SEPARATOR), parts);
    }

    /**
     * 内置事件: 拿目录里的登记与接口推出的结果对一遍, 不一致就告警。
     *
     * <p>这个交叉检查存在的理由很具体: 目录里的 {@code .category(...)} 是<b>人写下的</b>,
     * 而接口是<b>编译器保证的</b>。两者不一致时, 目录那一条是错的
     * （一个标了 {@code STATE_EFFECT} 的类却没有实现 {@code StateEffectEvent},
     * 它的影响永远不会进账本）—— 而"她穿的那件羽绒服没有保暖效果"这种问题,
     * 在目录里看起来是完全正常的。
     *
     * <p>刻意<b>不</b>用目录的值去覆盖接口的判定: 见类注释"类别从对象本身来"。
     * 告警是给人看的, 不是给流程用的。
     */
    private static void crossCheckAgainstCatalog(WorldEvent event, List<String> derived) {
        Optional<CoreEventCatalog.EventSpec> hit = CoreEventCatalog.find(event.typeId());
        if (hit.isEmpty()) {
            // 三方事件不在目录里 —— 这是正常处境, 不是异常, 所以不告警。
            // 但它必须能走完这条路: 目录查不到就不检查, 而**不是**把类别留空
            return;
        }
        CoreEventCatalog.EventSpec spec = hit.get();
        if (spec.category() == null) {
            return;
        }
        String declared = spec.category().name();
        if (!derived.contains(declared)) {
            log.warn("[Persistence] 事件 {} 的目录登记是 {}, 但它的实现类只实现了 {} —— "
                            + "接口说了算, 但因为两者不一致, '它会不会进账本/队列'这件事"
                            + "在目录里看是错误的。请让这一类实现对应的标记接口",
                    spec.typeId(), declared, derived.isEmpty() ? "WorldEvent" : derived);
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 崩溃恢复的第一问: <b>还没投进她意识的事件</b>, 按发生时刻正序。
     *
     * <p>返回 {@link ReadResult} 而不是 {@code List}: 一批未投递的事件里
     * 完全可能有一条的类型已经不认识了（三方应用被卸载）。把它塞进返回值会让
     * 调用方在 {@code for} 循环里遇到一个 {@code null}; 单独列出来则让
     * "这次恢复漏了几条"成为一个可以计数、可以告警的数字 —— 而不是一段静默的缺失。
     */
    public ReadResult<WorldEvent> pending(String worldId) {
        return decode(pendingRows(worldId), WorldEvent.class);
    }

    /**
     * 未投递事件的<b>行</b> —— 给那些"我要先看列再决定读不读"的调用方。
     *
     * <p>存在的理由很具体: {@code StimulusReplayStore} 需要先按
     * {@code category} 过滤, 再把剩下的反序列化成 {@code SensoryEvent}。
     * 若它只能拿到已解码的 {@code WorldEvent}, 那么"一条读不回来的 STATE_EFFECT
     * 事件"会让整次刺激重放失败 —— 而它本来就不在这个查询的范围内。
     * <b>先按列筛, 再按类型解码</b>, 这一层的顺序决定了"一个坏掉的插件"
     * 能不能影响"另一条完全无关的路径"。
     *
     * <p>走 {@code idx_world_event_unpublished}（见 {@link WorldEventRecordRepository}）。
     */
    public List<WorldEventRecord> pendingRows(String worldId) {
        Objects.requireNonNull(worldId, "按世界查未投递事件必须给出世界 id");
        return repository.findByWorldIdAndPublishedAtIsNullOrderByOccurredAtAsc(worldId);
    }

    /**
     * 一批行 → 一批事件, <b>读不回来的单独列出来</b>。
     *
     * <p>用宽容读（{@code DomainPayloadCodec.tryRead}）而不是会抛的 {@code read}:
     * 回放历史时"某个三方类型不再注册"是正常处境, 不是故障 ——
     * 让整次恢复因为其中一条读不出来而崩掉, 等于让"卸载一个插件"变成"这个 agent 起不来"。
     * 而需要严格的场景（恢复"她此刻正在做的事"）走 {@code ActivityStore}, 那里用会抛的读法。
     */
    public ReadResult<WorldEvent> decode(List<WorldEventRecord> rows, Class<? extends WorldEvent> expected) {
        Objects.requireNonNull(rows, "要解码的行不能为空");
        List<WorldEvent> events = new ArrayList<>(rows.size());
        List<WorldEventRecord> unreadable = new ArrayList<>();
        for (WorldEventRecord row : rows) {
            Optional<Object> value = codec.tryRead(row.getTypeNamespace(), row.getTypeName(),
                    row.getMajorVersion(), row.getPayloadJson());
            if (value.isPresent() && expected.isInstance(value.get())) {
                events.add(expected.cast(value.get()));
            } else {
                if (value.isPresent()) {
                    log.warn("[Persistence] 事件行 {} 的类型 {} 读出来是 {} 而不是 {} —— "
                                    + "它会被跳过, 因为把它当成期望的类型用会以一个远得多的"
                                    + "ClassCastException 出现",
                            row.getId(), describeType(row), value.get().getClass().getName(),
                            expected.getName());
                }
                unreadable.add(row);
            }
        }
        return new ReadResult<>(List.copyOf(events), List.copyOf(unreadable));
    }

    // ─────────────────────────── 投递 ───────────────────────────

    /**
     * 标记这几条事件已经投进她的意识了。
     *
     * <h2>为什么"已投递"要写进库, 而不是只活在内存队列里</h2>
     * 因为 {@code RealtimeEventQueue} <b>会丢东西</b>（它有容量上限, 溢出时按
     * {@code urgency} 淘汰最低的那条, 见它的类注释）。若"投递过"只由队列的消费位置表示,
     * 那么一次"投递了但没来得及记录消费位置"的崩溃会让同一批刺激<b>再投一次</b>——
     * 她会第二次被同一个门铃惊到。写进库之后,
     * "这条事件被投过吗"这个问题有了一个不受内存状态影响的答案。
     *
     * <h2>为什么只在 {@code published_at} 为 null 时写</h2>
     * 因为它是<b>第一次投递的时刻</b>, 不是"最后一次被触碰的时刻"。
     * 覆盖它的后果是: 分析"她隔了多久才注意到那条消息"会得到一个总是等于
     * 上次重启时间的答案 —— 一个看起来完全合理的数字, 但没有意义。
     *
     * @return 本次真正被标记的条数。<b>已投递的不计入</b> ——
     *         调用方据此判断"这次恢复到底补投了几条", 而重复标记不计入
     *         正是"重放不会重复投递"这条保证的可观测形式
     */
    public int markPublished(List<String> eventIds, Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "投递时刻必须由调用方给出 —— 本层不读时钟");
        if (eventIds == null || eventIds.isEmpty()) {
            return 0;
        }

        List<WorldEventRecord> rows = repository.findAllById(eventIds);
        int marked = 0;
        for (WorldEventRecord row : rows) {
            if (row.getPublishedAt() == null) {
                row.setPublishedAt(publishedAt);
                marked++;
            }
        }
        if (marked > 0) {
            repository.saveAll(rows);
        }

        if (rows.size() != eventIds.size()) {
            // 请求了 N 条、只找到 M 条 —— 这不是"已经投过了", 而是"那几行不存在"。
            // 两者都不该崩, 但混淆它们会让一次数据损坏看起来像一次正常重放
            log.warn("[Persistence] 请求标记 {} 条事件为已投递, 但只找到 {} 条 —— "
                            + "少掉的那几条不在库里。若你刚清过库, 这是预期; 否则这是一处数据损坏",
                    eventIds.size(), rows.size());
        }
        return marked;
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 这一行的类别里有某一类吗。
     *
     * <p>刻意用<b>逐段相等</b>而不是 {@code String.contains}:
     * {@code contains} 会把 {@code "SENSORY_DROPPED"} 当成 {@code "SENSORY"},
     * 而类别字符串是**会**长长长出来的（一个事件可以同时属于几类, 将来的名字
     * 完全可能是 {@code SENSORY} 的某个变体）。一个把"包含"当"相等"的判定,
     * 它的错误方向是<b>多投</b> —— 而多投的刺激会让她的行为偏向那个通道,
     * 且没有任何日志指向原因。
     *
     * @param categoryCell 行里的那一格（可能是 {@code null} —— 本层落地之前的历史行）
     */
    public static boolean hasCategory(String categoryCell, CoreEventCatalog.Category category) {
        Objects.requireNonNull(category, "要判的类别不能为空");
        if (categoryCell == null || categoryCell.isBlank()) {
            return false;
        }
        String wanted = category.name();
        int from = 0;
        while (from <= categoryCell.length()) {
            int at = categoryCell.indexOf(CATEGORY_SEPARATOR, from);
            int end = at < 0 ? categoryCell.length() : at;
            if (categoryCell.substring(from, end).trim().equals(wanted)) {
                return true;
            }
            if (at < 0) {
                return false;
            }
            from = at + 1;
        }
        return false;
    }

    /** 诊断用的一行字: 这一行是什么类型、什么类别、投没投。 */
    public static String describe(WorldEventRecord row) {
        Objects.requireNonNull(row, "要描述的行不能为空");
        return describeType(row) + " [" + row.getId() + "] " + row.getOccurredAt()
                + " 类别=" + (row.getCategory() == null || row.getCategory().isBlank()
                ? "(无)" : row.getCategory())
                + (row.published() ? " 已投递" : " 待投递");
    }

    private static String describeType(WorldEventRecord row) {
        if (row.getTypeNamespace() == null || row.getTypeNamespace().isBlank()) {
            return "(没有类型)";
        }
        return row.getTypeNamespace() + "." + row.getTypeName() + ".v" + row.getMajorVersion();
    }

    /**
     * 一批读出来的结果: <b>成功的</b>与<b>读不回来的</b>。
     *
     * <p>为什么不合成一个 {@code List<Optional<T>>}: 那会让调用方每次都要问
     * "这个空是'没有'还是'读不懂'", 而这两个答案的处理方式完全不同
     * （前者跳过就好, 后者要告警、要计数、要人来处理）。分成两个列表让
     * "这次恢复吃掉了多少条"变成一个可以直接比较的数字。
     *
     * @param values     读回来的对象, 顺序与输入的行一致（跳过的那些除外）
     * @param unreadable 读不回来的行 —— 保留整行而不是只保留 id: 诊断要看的是
     *                   {@code payload_json} 里那个 {@code _untyped} 标记,
     *                   只留 id 的话人就只能去翻数据库了
     */
    public record ReadResult<T>(List<T> values, List<WorldEventRecord> unreadable) {

        public ReadResult {
            values = values == null ? List.of() : List.copyOf(values);
            unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
        }

        /** 全都读回来了吗。 */
        public boolean complete() {
            return unreadable.isEmpty();
        }

        /** 一共碰了几行（读回来的 + 没读回来的）。 */
        public int total() {
            return values.size() + unreadable.size();
        }

        /** 没读回来的那几行的 id —— 给日志与告警用。 */
        public List<String> unreadableIds() {
            List<String> ids = new ArrayList<>(unreadable.size());
            for (WorldEventRecord row : unreadable) {
                ids.add(row.getId());
            }
            return List.copyOf(ids);
        }
    }
}
