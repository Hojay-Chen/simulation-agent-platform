package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.DeviceApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code application} 的读口。
 *
 * <h2>未读数是这张表上最热的一个数</h2>
 * 见 {@link DeviceApplicationRecord} 的类注释: {@code state_json} 里装着未读数与
 * 登录会话。而 {@link #findByDeviceIdAndApplicationKey} 是读它们的唯一入口 ——
 * 每收到一条消息（免打扰也要 +1, 见 §8.2.3 Case 1）都要问一次
 * "这台设备上这个应用的未读数现在是几"。
 *
 * <p>用 {@code Optional} 而不是 {@code List}: "设备内不重名"是本表的<b>意图</b>,
 * 而那层意图目前只由一条普通索引（{@code idx_application_device_key}）承载,
 * 没有唯一约束（理由见 {@link DeviceApplicationRecord#getId()} 的说明:
 * 安装流程可能因为重启而重放）。于是这个 {@code Optional} 同时充当一个断言 ——
 * 若哪天真的出现两行, 它会抛 {@code IncorrectResultSizeDataAccessException},
 * 把"重复安装"这件事<b>暴露出来</b>, 而不是静默取第一行让未读数少算一半。
 */
public interface DeviceApplicationRecordRepository extends JpaRepository<DeviceApplicationRecord, String> {

    /**
     * "这台设备上装了这个应用吗" —— 走 {@code idx_application_device_key}。
     *
     * <p>见类注释关于 {@code Optional} 的那一段。
     */
    Optional<DeviceApplicationRecord> findByDeviceIdAndApplicationKey(String deviceId, String applicationKey);

    /**
     * "这台设备装了哪些应用" —— 走 {@code idx_application_device_key} 的前缀
     * （{@code device_id} 是那条索引的第一列）。
     *
     * <p>设备装配时用。与 {@code WorldObjectRecordRepository} 的装配查询同一个形状:
     * 一次把该读的都读了, 而不是装配过程中一次次回表。
     */
    List<DeviceApplicationRecord> findByDeviceIdOrderByApplicationKeyAsc(String deviceId);

    /**
     * 跨设备按类型查: "她名下有哪几台设备装了聊天软件" —— 走 {@code idx_application_type}。
     *
     * <p>{@code DeviceApplication.id()} 的 javadoc 说应用 id 是"通知的标签",
     * 而通知路由需要先知道"哪些设备上的哪个应用该收到它"。这个方法服务于那一问。
     */
    List<DeviceApplicationRecord> findByApplicationTypeNamespaceAndApplicationTypeName(
            String applicationTypeNamespace, String applicationTypeName);

    /** 这台设备装了几个应用。 */
    long countByDeviceId(String deviceId);
}
