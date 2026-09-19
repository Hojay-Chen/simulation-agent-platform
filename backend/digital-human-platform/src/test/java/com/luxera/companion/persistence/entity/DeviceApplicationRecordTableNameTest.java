package com.luxera.companion.persistence.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.persistence.Index;
import javax.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeviceApplicationRecord} 的表名 —— <b>它不能叫 {@code application}</b>。
 *
 * <h2>为什么这件事值得一条测试, 而不是只写在注释里</h2>
 *
 * 因为改回 {@code application} 的代价<b>不会立刻显形</b>, 而且症状与原因相距很远。
 * 两个仓共用同一个 {@code companion} 库, 而仓 1(chat-platform) 的应用平台注册表已经
 * 占用了 {@code application} 这个名字(列: id/category/developer_id/latest_version/
 * name/status/state_json)。撞名之后 {@code ddl-auto: update} 每次都试着补列, 而
 * PostgreSQL 拒绝往一张有数据的表上加 NOT NULL 列:
 *
 * <pre>
 *   alter table if exists application add column application_key varchar(64) not null
 *   → ERROR: column "application_key" of relation "application" contains null values
 * </pre>
 *
 * 于是本表<b>一列都建不出来, 一行也写不进去</b>, 两条索引建在从来不曾存在的列上。
 * 而这一切在运行期的第一个消费者接上之前, 只是一段启动日志噪声 —— 等它接上, 报出来的
 * 是 {@code column "device_id" does not exist}, 那读起来像"实体写错了", 不像
 * "表名撞了"。所以在这里钉住: 断言表名, 并在失败信息里直接写明该改回什么。
 *
 * <p>顺带钉住两条索引 —— 它们不是装饰: {@code idx_application_device_key} 同时承担
 * "这台设备装了哪些应用"与"设备内的 key 唯一"两件事(见实体的类注释), 少了它
 * {@code findByDeviceIdAndApplicationKey} 会退化成全表扫描。
 */
class DeviceApplicationRecordTableNameTest {

    private static Table table() {
        Table t = DeviceApplicationRecord.class.getAnnotation(Table.class);
        assertNotNull(t, "DeviceApplicationRecord 必须有 @Table —— 否则表名会由命名策略推导, 那更不可控");
        return t;
    }

    @Test
    @DisplayName("★ 表名是 device_application, 不是 application —— 后者被仓 1 占用, 撞名的表永远建不出来")
    void tableNameDoesNotCollideWithRepoOne() {
        assertEquals("device_application", table().name(),
                "表名改回 application 会让本表在共享库上一列都建不出来"
                        + "(PostgreSQL 拒绝给已有数据的表加 NOT NULL 列), 而症状要到第一个"
                        + "运行期消费者接上时才出现, 报的还是 column \"device_id\" does not exist。");
    }

    @Test
    @DisplayName("两条索引都在 —— 少了任何一条, 对应的那个查询就退化成全表扫描")
    void declaresBothQueryIndexes() {
        List<String> names = Arrays.stream(table().indexes())
                .map(Index::name)
                .collect(Collectors.toList());

        assertEquals(2, names.size(), "索引数量变了就要回来看这里: " + names);
        assertTrue(names.contains("idx_application_device_key"),
                "缺 idx_application_device_key: '这台设备装了哪些应用'与'按 key 找应用'都没索引了");
        assertTrue(names.contains("idx_application_type"),
                "缺 idx_application_type: '她名下有哪几台设备装了聊天软件'(通知路由)没索引了");
    }

    @Test
    @DisplayName("索引建在表自己的列上 —— 列名写错时 ddl-auto 只会报错, 不会告诉你哪条索引写错了")
    void indexColumnsBelongToThisTable() {
        List<String> columns = Arrays.stream(table().indexes())
                .flatMap(i -> Arrays.stream(i.columnList().split(",")))
                .map(String::trim)
                .collect(Collectors.toList());

        assertEquals(List.of("device_id", "application_key",
                        "application_type_namespace", "application_type_name"),
                columns,
                "索引列必须与实体的 @Column(name=...) 对得上");
    }
}
