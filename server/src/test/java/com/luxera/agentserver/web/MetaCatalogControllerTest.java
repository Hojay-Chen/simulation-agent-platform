package com.luxera.agentserver.web;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.registry.CoreEventCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词汇表出口 —— 钉住的是<b>契约</b>, 不是那 47 条的内容。
 *
 * <h2>为什么这一组测试值得写, 而它看起来只是在转发一个静态对象</h2>
 *
 * 因为这个类唯一的失效方式, 是<b>它转发得"稍微不一样"</b> —— 而那种失效不会有任何
 * 报错。三种具体的写法, 每一种都看起来很合理:
 *
 * <ol>
 *   <li><b>把 {@code category} 换成中文标签。</b>读代码的人会觉得"反正前端要显示中文,
 *       不如服务端直接给"。后果: 前端的 {@code classifyFromServer()} 拿到的三个词
 *       再也认不出来, 于是<b>每一条事件都落到前端那张旧的译表上</b> —— 界面照常渲染,
 *       只是分类从此由前端决定。这正是这个端点要消灭的那个镜像, 换个方向长回来。</li>
 *   <li><b>{@code type} 给 {@code subscriptionKey}(不带版本)。</b>少了 {@code .v1},
 *       界面按它回查 {@code world_event} 时匹配不上 —— 而 {@code type_namespace} /
 *       {@code type_name} / {@code major_version} 是<b>三列</b>, 缺一列就查不出那一条。</li>
 *   <li><b>分组与扁平两份各算各的。</b>两份列表来自同一个 {@code all()}, 但它们是
 *       两次遍历 —— 一次过滤或一次排序写在一边、忘了写在另一边, 得到的是一份
 *       "总数对得上、但某一条只在其中一个视图里"的返回体。前端两个界面因此显示
 *       不同的条数, 而两个数都是"从服务端来的"。</li>
 * </ol>
 *
 * <p>这三种错误都通过编译、都能过人工审查、都不会在日志里留下任何痕迹。
 *
 * <h2>为什么这一组是纯单测</h2>
 * 与 {@code LapCatalogControllerTest} 同一个理由: 这些断言不需要 Spring, 而
 * "这个控制器真的被 8091 扫到、路由真的是 {@code /api/meta/event-types}" 由部署后的
 * 实测覆盖 —— 那件事单测替代不了, 也不该假装能。这里测的是<b>装进返回体的是什么</b>。
 */
class MetaCatalogControllerTest {

    private final MetaCatalogController controller = new MetaCatalogController();

    // ─────────────────── 契约 1: category 是与 world_event 同词的机器名 ───────────────────

    /**
     * 三个类别值必须原样是 {@code STATE_EFFECT} / {@code SENSORY} / {@code SCHEDULED}。
     *
     * <p>这一条是<b>跨进程的契约</b>, 而不是本类的内部选择: {@code world_event.category}
     * 那一列存的就是这三个词, 前端的 {@code classifyFromServer()} 按它们分派。
     * 本地改一个更好听的词, 编译期与运行期都不会有任何反应 —— 只有界面上的分类
     * 悄悄回落到前端那张表。
     */
    @Test
    @DisplayName("类别值是与 world_event 同词的机器名, 中文名另给一列")
    void 类别值是与world_event同词的机器名() {
        MetaCatalogController.CatalogView view = controller.eventTypes();

        Set<String> wires = view.categories().stream()
                .map(MetaCatalogController.CategoryEntry::category)
                .collect(Collectors.toSet());

        assertEquals(Set.of("STATE_EFFECT", "SENSORY", "SCHEDULED"), wires,
                "类别值必须是世界那一列用的三个大写词。换成中文标签的后果不是报错, "
                        + "而是前端再也认不出它们 —— 于是每一条事件都回落到前端那张"
                        + "本该被删掉的译表上, 而界面看不出任何区别");

        for (MetaCatalogController.CategoryEntry c : view.categories()) {
            assertNotNull(c.label());
            assertFalse(c.label().isBlank(), c.category() + " 没有中文名 —— 那前端就得自己抄一份");
            assertFalse(c.note().isBlank(), c.category() + " 没有解释 —— 一个没有'要不要立刻管'说明的图例等于没有图例");
            assertTrue(c.label().codePoints().anyMatch(cp -> cp > 0x2E80),
                    c.category() + " 的 label 是 \"" + c.label() + "\", 看着不像中文 —— "
                            + "这一列存在的全部理由就是让前端不必自己抄一份中文名");
        }

        // 每一条类型身上的 category 也必须是那三个词之一, 且与它所属的类别对得上。
        Set<String> perType = view.types().stream()
                .map(MetaCatalogController.TypeEntry::category)
                .collect(Collectors.toSet());
        assertTrue(wires.containsAll(perType),
                "有些类型的 category 不在类别表里: " + perType + " vs " + wires
                        + " —— 前端按类别过滤时会漏掉它们, 且没有任何提示");
    }

    /** 每一条类型上的中文名, 必须与它所属类别的中文名一致 —— 两个来源, 一个事实。 */
    @Test
    @DisplayName("类型上的中文名跟着类别走")
    void 类型上的中文名跟着类别走() {
        MetaCatalogController.CatalogView view = controller.eventTypes();
        var labelOf = view.categories().stream().collect(Collectors.toMap(
                MetaCatalogController.CategoryEntry::category,
                MetaCatalogController.CategoryEntry::label));

        for (MetaCatalogController.TypeEntry t : view.types()) {
            assertEquals(labelOf.get(t.category()), t.categoryLabel(),
                    t.type() + " 上的 categoryLabel 与它类别的中文名不一致 —— "
                            + "两处各写一份的结果就是两处会漂, 而漂的那一处没人会去看");
        }
    }

    // ─────────────────── 契约 2: 两个名字都是合法的, 且各自有用 ───────────────────

    /**
     * {@code type} 必须<b>能解析回它自己</b> —— {@code EventTypeId.parse} 的逆运算。
     *
     * <p>这一条不是形式主义: {@code type} 会被界面拿去回查数据库, 而库里那一行是
     * 三列。{@code EventTypeId} 的类注释明说 "toString 与 parse 必须互为逆运算 ——
     * 任何一处不一致都会变成一个'重放时找不到 handler'的诡异故障"。这里把那句话
     * 在出口上再验一次: 服务端拼错了, 前端就得跟着一起错。
     */
    @Test
    @DisplayName("type 能解析回它自己, subscriptionKey 是去掉版本的那一个")
    void type与订阅键都是合法的() {
        MetaCatalogController.CatalogView view = controller.eventTypes();

        for (MetaCatalogController.TypeEntry t : view.types()) {
            EventTypeId parsed = EventTypeId.parse(t.type());
            assertEquals(t.namespace(), parsed.namespace(), t.type() + " 解析出来的 namespace 不同");
            assertEquals(t.name(), parsed.name(), t.type() + " 解析出来的 name 不同");
            assertEquals(t.version(), parsed.majorVersion(), t.type() + " 解析出来的版本不同");

            assertEquals(t.namespace() + "." + t.name(), t.subscriptionKey(),
                    t.type() + " 的 subscriptionKey 不是 namespace.name —— "
                            + "前端的 handler 按它订阅, 多一个 .v1 就静默地收不到事件");

            assertFalse(t.type().equals(t.subscriptionKey()),
                    t.type() + " 的 type 与 subscriptionKey 相同 —— 说明版本段丢了。"
                            + "界面按 type 回查 world_event 时要匹配 type_namespace + type_name + major_version 三列");
            assertTrue(t.version() >= 1, t.type() + " 的版本是 " + t.version() + " —— 版本号从 1 起算");
        }
    }

    // ─────────────────── 契约 3: 分组视图与扁平视图是同一次遍历 ───────────────────

    /**
     * {@code types} 必须<b>恰好等于</b> {@code namespaces} 里所有类型的顺次拼接。
     *
     * <p>这两份是同一个列表的两种看法: 分组那份给"按族浏览"的界面, 扁平那份给
     * "查一条"的界面。它们一旦分叉, 症状是"两个页面说的事件总数不一样",
     * 而两个数都是从服务端来的 —— 排查的人会先怀疑缓存、再怀疑部署, 最后才想到
     * 是同一个方法里两次遍历没对齐。
     */
    @Test
    @DisplayName("扁平列表恰好等于分组列表的拼接, 且总数与 count 一致")
    void 扁平与分组是同一份数据() {
        MetaCatalogController.CatalogView view = controller.eventTypes();

        List<MetaCatalogController.TypeEntry> regrouped = new ArrayList<>();
        int summed = 0;
        for (MetaCatalogController.NamespaceEntry ns : view.namespaces()) {
            assertEquals(ns.count(), ns.types().size(),
                    ns.namespace() + " 报的条数与它真的带出来的条数不一致");
            summed += ns.count();
            regrouped.addAll(ns.types());
        }

        assertEquals(view.count(), summed,
                "各组条数之和(" + summed + ")与 count(" + view.count() + ")不一致");
        assertEquals(view.count(), view.types().size(),
                "count 与扁平列表的长度不一致 —— 前端画角标用 count, 画列表用 types");
        assertEquals(regrouped, view.types(),
                "扁平列表与分组视图的顺序或内容不一致 —— 它们是同一个列表的两种看法");
    }

    /** 目录本身一份都不该漏 —— 这条把出口与 {@code CoreEventCatalog} 钉在一起。 */
    @Test
    @DisplayName("出口覆盖了目录里的每一条, 且顺序可复现")
    void 覆盖目录的每一条且顺序稳定() {
        MetaCatalogController.CatalogView first = controller.eventTypes();
        MetaCatalogController.CatalogView second = controller.eventTypes();

        assertTrue(first.count() >= 40,
                "目录里只有 " + first.count() + " 条 —— 太少, 多半是 CoreEventCatalog 被改坏了, "
                        + "而它不会报错, 只会让界面上少掉一整族事件");

        Set<String> exported = first.types().stream()
                .map(MetaCatalogController.TypeEntry::type)
                .collect(Collectors.toSet());
        Set<String> inCatalog = CoreEventCatalog.all().stream()
                .map(s -> s.typeId().toString())
                .collect(Collectors.toSet());
        assertEquals(inCatalog, exported, "出口与目录的内容对不上");

        // 两次调用的顺序必须逐条相同。底层的 BY_NAMESPACE 是 Map.copyOf, 迭代顺序
        // 未定义; 这个类显式排序正是为了不受它影响 —— 而"排序写了但没生效"这件事
        // (比如排在分组之前、排在拷贝之后)只有对比两次调用才看得出来。
        assertEquals(first.types().stream().map(MetaCatalogController.TypeEntry::type).toList(),
                second.types().stream().map(MetaCatalogController.TypeEntry::type).toList(),
                "两次调用的顺序不同 —— 迭代顺序未定义的地图泄漏到了返回体里, "
                        + "于是文档里的清单与界面上的清单会悄悄分叉, 而两边都没错");
        assertEquals(first.namespaces().stream().map(MetaCatalogController.NamespaceEntry::namespace).toList(),
                second.namespaces().stream().map(MetaCatalogController.NamespaceEntry::namespace).toList());
    }

    // ─────────────────── 契约 4: 通道与感官来自目录本身 ───────────────────

    /**
     * 通道名与感官名必须<b>逐字</b>来自 {@code CoreEventCatalog}。
     *
     * <p>理由与 {@code EventSpec} 注释里那条一样: 通道名拼错不会报错, 它会让两条本该
     * 相加的影响分成两笔互不相干的账 —— 表现是"羽绒服穿了但没用"。所以这一列不能是
     * 一份手抄的副本, 只能是那个常量集合本身。
     */
    @Test
    @DisplayName("通道与感官逐字来自目录, 且类型上的取值合法")
    void 通道与感官来自目录本身() {
        MetaCatalogController.CatalogView view = controller.eventTypes();

        assertEquals(CoreEventCatalog.Channels.ALL, Set.copyOf(view.channels()),
                "通道表与 CoreEventCatalog.Channels.ALL 不是同一份");
        assertTrue(view.channels().contains(CoreEventCatalog.Channels.WARMTH),
                "连 body.warmth 都不在 —— 保暖这条链是用户点名要的那条(§ D)");

        assertEquals(CoreEventCatalog.Modalities.ALL, Set.copyOf(view.modalities()),
                "感官表与 CoreEventCatalog.Modalities.ALL 不是同一份");
        assertEquals(5, view.modalities().size(),
                "人只有五种感官 —— 这个集合是封闭的, 多一个少一个都是错");

        // 类型上带的通道/感官名, 必须是标准名。这一条守的是目录的书写 ——
        // 一个把 modality 写成 "hearing" 的目录项, 症状是"她没听见", 且没有报错。
        for (MetaCatalogController.TypeEntry t : view.types()) {
            if (t.modality() != null) {
                assertTrue(CoreEventCatalog.Modalities.isStandard(t.modality()),
                        t.type() + " 的 modality 是 \"" + t.modality() + "\", 不在五种感官里 —— "
                                + "一个拼错的通道投出去是没有接收者的, 而症状是'她没听见'");
            }
            if (t.channel() != null) {
                assertTrue(CoreEventCatalog.Channels.ALL.contains(t.channel()),
                        t.type() + " 的 channel 是 \"" + t.channel() + "\", 不在标准通道里 —— "
                                + "两个名字各算一笔账, 表现是'羽绒服穿了但没用'");
            }
        }
    }

    // ─────────────────── 契约 5: 没有人认领的那几条被单独说出来 ───────────────────

    /**
     * {@code unclaimed} 必须<b>恰好</b>是 {@code claimed == false} 的那个子集。
     *
     * <p>它是这份返回体里唯一一个"为假就说明有事"的字段(与
     * {@code StartupSummary.Recovery#hasOpaqueEntries()} 同一种东西)。一个自己跟
     * 自己对不上的警告列表比没有这个列表更糟: 前端的角标说"有 3 条没人认领",
     * 而用户照着列表去查却只找得到 2 条 —— 那时他会怀疑的是自己数错了。
     */
    @Test
    @DisplayName("unclaimed 恰好是 claimed 为假的那个子集")
    void unclaimed恰好是claimed为假的那个子集() {
        MetaCatalogController.CatalogView view = controller.eventTypes();

        Set<String> fromFlag = view.types().stream()
                .filter(t -> !t.claimed())
                .map(MetaCatalogController.TypeEntry::type)
                .collect(Collectors.toSet());

        assertEquals(fromFlag, Set.copyOf(view.unclaimed()),
                "unclaimed 列表与每条 type 上的 claimed 字段对不上 —— "
                        + "前端依赖这两者一致: 一个自己跟自己不一致的告警比没有告警更糟");

        // 每条 type 的 consumer 与 claimed 也必须自洽 —— "(未认领)" 是目录用的哨兵值。
        for (MetaCatalogController.TypeEntry t : view.types()) {
            assertEquals(t.claimed(), !"(未认领)".equals(t.consumer()),
                    t.type() + ": claimed=" + t.claimed() + " 而 consumer=\"" + t.consumer() + "\"");
        }
    }
}
