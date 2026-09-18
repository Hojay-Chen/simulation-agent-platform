package com.luxera.companion.boundary.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §8.2.1 —— <b>{@link EventTypeId} 的契约测试</b>。
 *
 * <h2>为什么这个类值得一整份测试</h2>
 * 它是整个事件系统的<b>主键</b>: 每一条事件落库时带它、订阅时按它匹配、重放时按它找 handler。
 * 它的 javadoc 里写着一句很强的话 ——
 * <blockquote>{@link EventTypeId#toString()} 与 {@link EventTypeId#parse} <b>必须互为逆运算</b></blockquote>
 * —— 而"必须"这种词如果没有一条会红的断言跟着, 它就只是一句祝福。
 *
 * <p>违反它的后果也不是崩溃, 而是更坏的东西: 一条被写成
 * {@code device.phone.battery-changed} 的事件, 重放时按 {@code parse} 解出来的
 * namespace 变成了别的东西, 于是<b>找不到 handler, 而事件被静默丢弃</b>。
 * 那种故障看起来像"她那天没反应", 而不像"代码坏了"。
 */
class EventTypeIdTest {

    // ─────────────────────── 一、往返: toString 与 parse 互为逆运算 ───────────────────────

    @Nested
    @DisplayName("toString 与 parse 互为逆运算")
    class RoundTrip {

        @Test
        @DisplayName("单节 namespace 的往返")
        void 单节命名空间往返() {
            EventTypeId id = EventTypeId.of("environment", "temperature-changed");
            assertEquals("environment.temperature-changed.v1", id.toString());
            assertEquals(id, EventTypeId.parse(id.toString()));
        }

        @Test
        @DisplayName("多节 namespace 的往返 —— 层级由 namespace 承担")
        void 多节命名空间往返() {
            EventTypeId id = EventTypeId.of("device.phone", "battery-changed");
            assertEquals("device.phone.battery-changed.v1", id.toString());
            assertEquals(id, EventTypeId.parse(id.toString()),
                    "带点的 namespace 走一趟 parse 之后必须原样回来 —— "
                            + "否则 CoreEventCatalog 里那批 device.* 事件在重放时会找不到 handler");
        }

        @Test
        @DisplayName("三层 namespace 的往返")
        void 三层命名空间往返() {
            EventTypeId id = EventTypeId.of("world.device.phone", "screen-woke", 3);
            assertEquals("world.device.phone.screen-woke.v3", id.toString());
            assertEquals(id, EventTypeId.parse(id.toString()));
        }

        @Test
        @DisplayName("非 v1 版本也要往返")
        void 非一版本往返() {
            EventTypeId v7 = EventTypeId.of("body", "warmth-changed", 7);
            assertEquals(7, EventTypeId.parse(v7.toString()).majorVersion());
            assertEquals(v7, EventTypeId.parse(v7.toString()));
        }
    }

    // ─────────────────────── 二、namespace 可以含点（回归测试） ───────────────────────

    /**
     * 这一组是<b>一个真实 bug 的回归测试</b>。
     *
     * <p>{@code requireSegment} 曾经拿 {@code [a-z][a-z0-9-]*} 去校验 namespace 和 name
     * 两样东西, 而类注释与 {@link EventTypeId#parse} 的 javadoc 都明写着 namespace 含点。
     * 于是 {@code EventTypeId.of("device.phone", ...)} 抛 {@code IllegalArgumentException},
     * 而 {@code CoreEventCatalog} 的静态初始化里有一整批这样的事件名 ——
     * 结果是 {@code ExceptionInInitializerError}, <b>整个平台起不来</b>。
     *
     * <p>它躲过了编译（静态初始化是运行期的事）, 也躲过了此前的全部审查
     * （没人会逐字核对一个正则与三段文字描述是否一致）。所以这里必须有一条断言钉住它。
     */
    @Nested
    @DisplayName("namespace 可以含点（回归: 一个让平台起不来的 bug）")
    class DottedNamespace {

        @Test
        @DisplayName("两节 namespace 合法")
        void 两节命名空间合法() {
            assertDoesNotThrow(() -> EventTypeId.of("device.phone", "battery-changed"));
        }

        @Test
        @DisplayName("三节 namespace 合法")
        void 三节命名空间合法() {
            assertDoesNotThrow(() -> EventTypeId.of("world.device.phone", "screen-woke"));
        }

        @Test
        @DisplayName("每一节都必须自己合法 —— 点不是万能通行证")
        void 每一节都要合法() {
            // 空的一节: "device..phone"
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("device..phone", "x"));
            // 下划线
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("device.phone_v2", "x"));
            // 数字开头
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("device.2phone", "x"));
            // 结尾的点: "device.phone."
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("device.phone.", "x"));
            // 开头的点: ".device"
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of(".device", "x"));
        }

        @Test
        @DisplayName("name 里不许有点 —— 否则 parse 的切分就歧义了")
        void 名称不许有点() {
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("device", "phone.battery-changed"),
                    "name 里含点会让 parse 无法判断哪一段是版本 —— "
                            + "层级表达是 namespace 的职责");
        }
    }

    // ─────────────────────── 三、parse 的切分规则 ───────────────────────

    @Nested
    @DisplayName("parse 的切分: 从右往左数两段")
    class Parsing {

        @Test
        @DisplayName("namespace 恰好以 v2 这样的片段结尾时也不会切错")
        void 版本样子的命名空间片段() {
            // 这正是"不用正则、从右往左数两段"要防的那个 case:
            // 若从左往右找第一个 v<数字>, 这里会切成 namespace="experiment"、name="2"
            EventTypeId id = EventTypeId.parse("experiment.v2.temperature-changed.v1");
            assertEquals("experiment.v2", id.namespace());
            assertEquals("temperature-changed", id.name());
            assertEquals(1, id.majorVersion());
        }

        @Test
        @DisplayName("少于三段直接拒绝")
        void 少于三段拒绝() {
            assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse("body.warmth"));
            assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse("warmth"));
            assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse(""));
            assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse("   "));
            assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse(null));
        }

        @Test
        @DisplayName("版本段必须是 v<数字>")
        void 版本段格式() {
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.parse("body.warmth.1"), "少了 v 前缀");
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.parse("body.warmth.v"), "只有前缀没有数字");
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.parse("body.warmth.vX"), "版本不是数字");
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.parse("body.warmth.v1.2"), "版本段带点");
        }

        @Test
        @DisplayName("tryParse 遇错返回空, 不抛")
        void tryParse不抛() {
            assertTrue(EventTypeId.tryParse("body.warmth").isEmpty());
            assertTrue(EventTypeId.tryParse("not a type id").isEmpty());
            assertTrue(EventTypeId.tryParse(null).isEmpty());
            assertEquals(EventTypeId.of("body", "warmth"),
                    EventTypeId.tryParse("body.warmth.v1").orElseThrow());
        }
    }

    // ─────────────────────── 四、规范性: 大小写与空白 ───────────────────────

    @Nested
    @DisplayName("规范性: 宽松的输入被归一, 而不是被拒绝")
    class Canonicalization {

        @Test
        @DisplayName("大写被归一成小写")
        void 大写归一() {
            EventTypeId id = EventTypeId.of("Body", "Warmth-Changed");
            assertEquals("body", id.namespace());
            assertEquals("warmth-changed", id.name(),
                    "不归一的话 TemperatureChanged / temperaturechanged / temperature-changed "
                            + "会同时存在, 而它们之间的失配是静默的");
        }

        @Test
        @DisplayName("前后空白被去掉")
        void 空白归一() {
            EventTypeId id = EventTypeId.of("  body  ", " warmth ");
            assertEquals(EventTypeId.of("body", "warmth"), id);
        }

        @Test
        @DisplayName("下划线与驼峰被拒绝, 不被悄悄转换")
        void 下划线与驼峰被拒() {
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("body", "warmth_changed"),
                    "悄悄把下划线换成连字符会让'写下的名字'与'存下的名字'不同 —— "
                            + "而那种不同在两条记录对不上时才会被发现");
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("body", "WarmthChanged"));
        }

        @Test
        @DisplayName("版本号从 1 起算")
        void 版本号从一起算() {
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("body", "warmth", 0),
                    "0 无法与'忘了设'区分开");
            assertThrows(IllegalArgumentException.class,
                    () -> EventTypeId.of("body", "warmth", -1));
            assertEquals(1, EventTypeId.of("body", "warmth").majorVersion(),
                    "of(namespace, name) 的默认版本应当是 1");
        }
    }

    // ─────────────────────── 五、订阅与版本的关系 ───────────────────────

    @Nested
    @DisplayName("订阅键不含版本")
    class Subscription {

        @Test
        @DisplayName("同名不同版本共享一个订阅键")
        void 订阅键不含版本() {
            EventTypeId v1 = EventTypeId.of("body", "warmth-changed", 1);
            EventTypeId v2 = EventTypeId.of("body", "warmth-changed", 2);

            assertEquals(v1.subscriptionKey(), v2.subscriptionKey());
            assertNotEquals(v1, v2, "但它们是两个不同的类型");
            assertTrue(v1.sameType(v2), "sameType 判定'同名不同版本'");
            assertFalse(v1.sameType(EventTypeId.of("body", "hunger-changed")));
        }

        @Test
        @DisplayName("订阅键是 namespace + name, 不带版本段")
        void 订阅键形状() {
            assertEquals("device.phone.battery-changed",
                    EventTypeId.of("device.phone", "battery-changed", 3).subscriptionKey());
        }

        @Test
        @DisplayName("withMajorVersion 造出同名的新版本")
        void 换版本() {
            EventTypeId v1 = EventTypeId.of("body", "warmth");
            EventTypeId v2 = v1.withMajorVersion(2);
            assertTrue(v1.sameType(v2));
            assertNotEquals(v1, v2);
            assertEquals("body.warmth.v2", v2.toString());
        }
    }

    // ─────────────────────── 六、命名空间归属 ───────────────────────

    @Nested
    @DisplayName("under: 含子命名空间")
    class NamespaceMembership {

        @Test
        @DisplayName("子命名空间算在内")
        void 子命名空间算在内() {
            EventTypeId id = EventTypeId.of("device.phone", "battery-changed");
            assertTrue(id.under("device.phone"), "自己算在自己之下");
            assertTrue(id.under("device"), "device.phone 在 device 之下");
        }

        @Test
        @DisplayName("前缀相同但不是子命名空间的不算")
        void 前缀相似不算() {
            EventTypeId id = EventTypeId.of("devicephone", "x");
            assertFalse(id.under("device"),
                    "devicephone 不是 device 的子命名空间 —— 少了那个点, "
                            + "它就只是另一个恰好共享前缀的名字");
            assertFalse(id.under(null));
        }
    }

    // ─────────────────────── 七、排序 ───────────────────────

    @Test
    @DisplayName("排序稳定: namespace → name → 版本")
    void 排序稳定() {
        // 目录(CoreEventCatalog)的输出顺序依赖它 —— 一个每次迭代顺序都不同的目录,
        // 会让"文档里的清单"与"运行时的清单"悄悄分叉
        java.util.List<EventTypeId> ids = new java.util.ArrayList<>(java.util.List.of(
                EventTypeId.of("body", "warmth", 2),
                EventTypeId.of("body", "hunger", 1),
                EventTypeId.of("body", "warmth", 1),
                EventTypeId.of("device.phone", "battery-changed", 1),
                EventTypeId.of("environment", "temperature-changed", 1)));

        java.util.Collections.sort(ids);

        assertEquals(java.util.List.of(
                        EventTypeId.of("body", "hunger", 1),
                        EventTypeId.of("body", "warmth", 1),
                        EventTypeId.of("body", "warmth", 2),
                        EventTypeId.of("device.phone", "battery-changed", 1),
                        EventTypeId.of("environment", "temperature-changed", 1)),
                ids);
    }
}
