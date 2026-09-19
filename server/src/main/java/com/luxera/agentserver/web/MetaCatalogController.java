package com.luxera.agentserver.web;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.registry.CoreEventCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>这套仿真的词汇表</b> —— 世界与身体能发出哪些事件、影响作用在哪些通道上、
 * 人靠哪几种感官接收刺激。
 *
 * <h2>它为什么存在</h2>
 *
 * 用户对 V2.1 的批评里有一条直接指向这个类:
 * <blockquote>
 *   你定义了 event 类结构, 给了 eventtype 字段, 但是没定义 eventtype 有哪些枚举值,
 *   这是很不好的, 不了解的人看完完全不知道都有哪些 event
 * </blockquote>
 *
 * V2.2 §5.4 的答复是 {@link CoreEventCatalog} —— 那份目录在<b>服务端</b>回答了这个问题。
 * 而控制台(Being Studio)在<b>前端</b>, 它看不见服务端的常量。于是前端自己抄了一份
 * 译表({@code lib/events.ts} 的 {@code CLASS_OF_TYPE} / {@code LABEL_OF_TYPE}),
 * 后果是每一次往目录里加一条事件, 界面上就多一条"未登记"的记录; 每一次给某个事件
 * 换一个类别, 界面就继续按旧的画, <b>而且不会有任何报错</b> ——
 * 只有一条画错了颜色的记录, 没有人会去报告它。
 *
 * <p>这个类就是那份目录的出口。有了它, 前端那张译表就<b>该被删掉</b>, 而不是"两边各自
 * 维护、偶尔对齐"。
 *
 * <h2>为什么是一个端点, 而不是三个</h2>
 *
 * 目录里除了 47 条事件, 还有两张短表: 持续影响的 {@link CoreEventCatalog.Channels 通道}
 * 与感官的 {@link CoreEventCatalog.Modalities 通道}。它们回答的是同一个问题 ——
 * "这个世界有哪些词汇" —— 而且它们<b>都住在同一个对象里</b>。拆成
 * {@code /event-types}、{@code /effect-channels}、{@code /modalities} 三条路由, 会让
 * "目录是数据"这件事在传输层被重新拆成三个各自演化的东西; 而它们今天一致, 是因为
 * 它们本来就是一份数据。
 *
 * <p>所以这个端点返回的是<b>目录本身</b>, 而不是目录的一个投影。
 *
 * <h2>{@code category} 用的是机器名, 不是中文标签 —— 这一条是有讲究的</h2>
 *
 * {@code category} 的值是 {@code STATE_EFFECT} / {@code SENSORY} / {@code SCHEDULED},
 * 与 {@code world_event} 表那个 {@code category} 列<b>同一套词汇</b>(§7.2)。
 * 中文标签放在并排的 {@code categoryLabel} 里。
 *
 * <p>反过来的写法(直接给中文)会逼前端再造一张"中文 → 类别"的反查表 —— 也就是
 * 我们要删掉的那种镜像, 换了个方向又长回来。而前端的 {@code classifyFromServer()}
 * 已经写成"服务端给了 category 就采信", 它要的正是这三个大写词。
 *
 * <h2>为什么还报 {@code unclaimed}</h2>
 *
 * 一条有类型、有语义、却没有消费者的目录项, 要么是留给将来的(可以, 但要知道),
 * 要么是某个 handler 的订阅键写错了(必须查)。这两种情况在界面上长得一模一样,
 * 所以它必须由服务端算出来 —— 让前端拿 47 条去逐条比对是在把一件机械的事交给人。
 * 这与 {@code StartupSummary.hasOpaqueEntries()} 是同一种设计: <b>把"需要人注意的
 * 那个子集"做成一个有名字的字段</b>, 而不是让每个读者自己筛。
 *
 * <h2>鉴权</h2>
 *
 * 落在 {@code /api/**}, 由 {@code ServerSecurityConfig} 的
 * {@code anyRequest().authenticated()} 要求用户 JWT —— 与其余内省接口同门。
 * 目录是平台级的(不属于某一个 agent), 所以它没有 companionId 参数 —— 与
 * {@link LapCatalogController} 同一个形状。
 */
@Slf4j
@RestController
@RequestMapping("/api/meta")
public class MetaCatalogController {

    /**
     * 词汇表全量。
     *
     * <p>一次取全, 不写分页 —— 47 条事件的完整说明(含语义、载荷、生产者、消费者)在
     * 序列化之后是几十 KB, 而"这一页想显示哪几条"是<b>界面</b>的问题, 不是传输的问题。
     * 分页会让前端为了画一个"共 47 条, 有 3 条从没发生过"的角标去打 1+N 次请求。
     *
     * <p>顺序是<b>确定的</b>: 类型按 {@link EventTypeId} 的
     * {@code namespace → name → version} 排(它实现了 {@code Comparable}),
     * 命名空间按字典序。这一条不是洁癖: 目录的迭代顺序若随 JVM 而变,
     * "文档里的清单"与"界面上的清单"就会悄悄分叉, 而两边都没错。
     *
     * <h2>这个端点<b>不</b>回答的那个问题</h2>
     *
     * "线上真的发生过哪些类型" —— 那要查 {@code world_event} 表(§7.2), 是另一条路由的事。
     * 它与这一条的关系是<b>目录 × 实际</b>, 而把这个笛卡尔积塞进词汇表本身, 会让
     * "这个世界能发生什么"与"今天发生了什么"这两个问题在一份返回体里互相污染:
     * 前者随代码变, 后者随数据变, 而它们的失效方式完全不同。
     *
     * <p>前端的用法是各取一份、在内存里对一次; 那个对账的结果("哪几类从来没发生过")
     * 才是运维要的答案。
     *
     * <p>{@code channels} 与 {@code modalities} 之所以在这里而那个问题不在这里:
     * 它们<b>不是</b>另一个问题, 它们就是词汇表的一部分, 只是不住在 {@code CORE}
     * 那个列表里。
     */
    @GetMapping("/event-types")
    public CatalogView eventTypes() {
        List<CoreEventCatalog.EventSpec> all = CoreEventCatalog.all();

        // 按命名空间分组 —— TreeMap 而不是 CoreEventCatalog 自己的 BY_NAMESPACE:
        // 那一份是 Map.copyOf(LinkedHashMap), 迭代顺序未定义。这里要的是稳定输出。
        Map<String, List<TypeEntry>> byNamespace = new TreeMap<>();
        List<String> unclaimed = new ArrayList<>();
        for (CoreEventCatalog.EventSpec spec : all) {
            EventTypeId id = spec.typeId();
            byNamespace.computeIfAbsent(id.namespace(), k -> new ArrayList<>()).add(TypeEntry.of(spec));
            if (!spec.claimed()) {
                unclaimed.add(id.toString());
            }
        }
        // 组内也排一次: 同一命名空间下 name 的先后由目录的书写顺序决定, 而书写顺序
        // 是给读目录的人看的, 不是给机器看的。两者不一致时, 以机器可复现的那个为准。
        byNamespace.values().forEach(list -> list.sort(Comparator.comparing(TypeEntry::name)));

        List<NamespaceEntry> namespaces = new ArrayList<>(byNamespace.size());
        byNamespace.forEach((ns, types) -> namespaces.add(new NamespaceEntry(ns, types.size(), types)));

        List<TypeEntry> flat = new ArrayList<>(all.size());
        namespaces.forEach(ns -> flat.addAll(ns.types()));

        List<CategoryEntry> categories = new ArrayList<>();
        Map<CoreEventCatalog.Category, Long> counts = CoreEventCatalog.countByCategory();
        for (CoreEventCatalog.Category c : CoreEventCatalog.Category.values()) {
            categories.add(new CategoryEntry(
                    c.name(), c.label(), c.note(), counts.getOrDefault(c, 0L)));
        }

        return new CatalogView(
                all.size(),
                categories,
                namespaces,
                List.copyOf(CoreEventCatalog.Channels.ALL),
                List.copyOf(CoreEventCatalog.Modalities.ALL),
                List.copyOf(unclaimed),
                flat);
    }

    // ─────────────────────────── 返回体 ───────────────────────────

    /**
     * @param count        目录里一共多少条
     * @param categories   三类机制, <b>带各自的中文名与说明</b> —— 前端的图例直接用这一份
     * @param namespaces   按命名空间分好组的类型, 顺序稳定
     * @param channels     标准持续影响通道({@code body.warmth} 等)
     * @param modalities   五种感官通道 —— 封闭集合, 因为人只有这五种
     * @param unclaimed    没有消费者的类型标识 —— <b>健康状态是空</b>
     * @param types        全部类型, 扁平一份, 供"查一条"的界面用
     */
    public record CatalogView(
            int count,
            List<CategoryEntry> categories,
            List<NamespaceEntry> namespaces,
            List<String> channels,
            List<String> modalities,
            List<String> unclaimed,
            List<TypeEntry> types) {
    }

    /**
     * 一类机制。
     *
     * <p>{@code category} 是与 {@code world_event.category} 列同词的那一个 ——
     * 界面上按类别过滤事件时, 用的就是它。
     */
    public record CategoryEntry(String category, String label, String note, long count) {
    }

    /** 一个命名空间, 以及它名下的类型。{@code namespace} 是点分路径(如 {@code device.phone})。 */
    public record NamespaceEntry(String namespace, int count, List<TypeEntry> types) {
    }

    /**
     * 一条事件类型。
     *
     * <h2>为什么 {@code type} 与 {@code subscriptionKey} 都要给</h2>
     *
     * 它们是同一个类型的两个合法名字, 而<b>用在哪一处是有区别的</b>:
     * <ul>
     *   <li>{@code type} = {@code namespace.name.vN}, 完整标识。数据库里那一行是
     *       <b>三列</b>({@code type_namespace} / {@code type_name} / {@code major_version}),
     *       而这是它们的单行形式, 也是 {@code EventTypeId.parse} 的逆运算 ——
     *       界面要"跳回那条记录"时用它;</li>
     *   <li>{@code subscriptionKey} = {@code namespace.name}, <b>不含版本</b>。
     *       按类型订阅 handler 时用它, 因为一个只想在温度变化时算保暖值的 handler
     *       不该因为载荷升到 v2 就静默地收不到事件。</li>
     * </ul>
     * 只给其中一个, 前端就得自己切字符串 —— 而"从 {@code .v1} 前面切掉"这种代码
     * 在 {@code name} 里含点的那一天会错得很安静。
     *
     * @param category      与 {@code world_event.category} 同词
     * @param categoryLabel 中文名, 由服务端给 —— 这正是前端那张译表该被删掉的理由
     * @param channel       若是 A 类, 作用在哪个通道上; 否则 null
     * @param modality      若是 B 类, 走哪个感官; 否则 null
     * @param claimed       有没有消费者。<b>为假不一定是错</b> —— 但一定是需要人看一眼的事
     */
    public record TypeEntry(
            String type,
            String subscriptionKey,
            String namespace,
            String name,
            int version,
            String category,
            String categoryLabel,
            String channel,
            String modality,
            String payload,
            String semantics,
            String producer,
            String consumer,
            boolean claimed) {

        static TypeEntry of(CoreEventCatalog.EventSpec spec) {
            EventTypeId id = spec.typeId();
            return new TypeEntry(
                    id.toString(),
                    id.subscriptionKey(),
                    id.namespace(),
                    id.name(),
                    id.majorVersion(),
                    spec.category().name(),
                    spec.category().label(),
                    spec.channel(),
                    spec.modality(),
                    spec.payload(),
                    spec.semantics(),
                    spec.producer(),
                    spec.consumer(),
                    spec.claimed());
        }
    }

}
