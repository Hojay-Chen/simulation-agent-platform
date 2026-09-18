package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.WorldObjectRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2.2 §7.2 —— {@code world_object} 的读口。
 *
 * <h2>"世界的形状"是启动时一次性装配出来的</h2>
 * {@link #findByOwnerWorldIdOrderByDisplayNameAsc} 是装配流程的全部内容:
 * 一个世界实例启动时把它名下的对象<b>一次</b>读进来, 之后对象图在内存里。
 * 这是 {@code WorldObject} 的 javadoc 所描述的那种用法（"世界知道有人在,
 * 不知道她是谁"）在持久化这一侧的对应形态。
 *
 * <p>因此这个接口里<b>没有任何"按 id 查单个对象"的方法被强调成热路径</b> ——
 * 一次一个地查对象是装配模式的退化, 而它退化的表现是"世界的对象一多,
 * 启动就变慢", 一个很容易被归因错的症状。
 */
public interface WorldObjectRecordRepository extends JpaRepository<WorldObjectRecord, String> {

    /**
     * 世界装配: 这个世界名下的全部对象, 按名字排 —— 走 {@code idx_world_object_world_name}。
     *
     * <p>排序在 SQL 里而不是在内存里: 名字是索引的第二列, 于是"按名字排"
     * 是一次索引扫描就出结果的事, 而内存排序要求先把结果集物化。
     * 对象数量在早期很小, 但这条查询的代价会随世界生长 —— 现在写对, 以后不用改。
     */
    List<WorldObjectRecord> findByOwnerWorldIdOrderByDisplayNameAsc(String ownerWorldId);

    /**
     * 按类型筛: "这个世界里的全部手机" / "全部灯" —— 走 {@code idx_world_object_type}。
     *
     * <p>三列全带, 理由与 {@code WorldEventRecordRepository} 的同名方法一样:
     * 少了 {@code objectTypeVersion}, 查询会跨 major 版本混读,
     * 而两个 major 版本之间的状态形状是不兼容的（那是 major 的定义）。
     */
    List<WorldObjectRecord> findByOwnerWorldIdAndObjectTypeNamespaceAndObjectTypeNameAndObjectTypeVersion(
            String ownerWorldId, String objectTypeNamespace, String objectTypeName,
            int objectTypeVersion);

    /** 这个世界装配了几个对象。 */
    long countByOwnerWorldId(String ownerWorldId);

    /** 对象存在性检查 —— 一条事件引用了一个对象 id 时, 判它是不是真的。 */
    boolean existsByOwnerWorldIdAndId(String ownerWorldId, String id);
}
