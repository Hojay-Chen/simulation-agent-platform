package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §7.2 —— {@code application}: <b>装在设备上的一个应用, 以及它自己的状态</b>。
 *
 * <h2>为什么应用需要状态, 而设备不需要</h2>
 * 这是这张表存在的唯一理由, 也是它容易被误做成"设备表的一个字段"的地方:
 * <ul>
 *   <li><b>设备</b>没有"应用状态" —— 它有的是音量、电量、屏幕, 那些是
 *       {@code Device} 自己的状态;</li>
 *   <li><b>应用</b>有。§7.2 自己举了两个例子: <b>登录会话</b>与<b>未读数</b>。
 *       而这两个恰恰是 §9 验收标准 E（聊天正文不得绕过进 Mind 的工作记忆）
 *       与 "免打扰时仍然 unreadCount + 1" 这两条测试直接依赖的量 ——
 *       {@code ChatApplication} 必须能回答"我有几条没读", 而那个数字
 *       <b>不能</b>从聊天平台重新拉一次（那就成了"她每次开机都同步全量"）。</li>
 * </ul>
 * 换句话说: 未读数是<b>她的</b>状态, 不是聊天平台的。它必须活在她这一侧,
 * 而且必须跨重启。
 *
 * <h2>{@code application_key} 为什么必须单独存在, 而不只是 {@code application_type_name}</h2>
 * {@code DeviceApplication.id()} 的 javadoc 明写:
 * <blockquote>
 * 它会被写进 {@code NotificationRequest.applicationKey}, 因此它同时是<b>通知的标签</b>。
 * 要求跨重启不变: 设备按它记未读数, 改 id 等于换一个应用。
 * </blockquote>
 * 那句话里的"设备按它记未读数"指的<b>就是这张表</b>。于是 {@code application_key}
 * 是这张表真正的业务主键（"这台设备上的这个应用"), 而类型三元组是
 * §7.2 要求的统一形状。
 *
 * <p>两者在本仓里<b>目前不重合</b>, 这一点必须写清楚:
 * {@code DeviceApplication} 接口<b>没有</b> {@code typeId()} 方法
 * （{@code WorldObject} 有, 应用没有 —— {@code Descriptor.applicationKey()} 才是
 * 它的稳定标识）。于是当实现类上没有 {@code @DomainType} 时,
 * {@code DomainPayloadCodec} 会写下一个 {@code _untyped} 标记 ——
 * 这正是本设计里"未注册类型不静默丢数据"那条测试覆盖的路径,
 * 而它在这里是<b>常见情况而不是异常</b>: 应用的状态通常是
 * "一堆标量"（未读数、会话 token 的引用、免打扰开关）, 用
 * {@code DomainTypeRegistry} 认领实现类并不总是必要。
 *
 * <p>因此这一层的纪律是: <b>{@code application_key} 是身份, 三元组是尽力而为的补充</b>。
 * 读的时候先按 key 找到类（{@code DeviceRegistry} 那一侧）, 再试着用三元组反序列化状态;
 * 认不出类型时状态以 {@code Map} 形式交回, 而不是丢成 {@code null}。
 *
 * <h2>表名为什么是 {@code device_application} 而不是 {@code application}</h2>
 *
 * 因为两个仓共用同一个 {@code companion} 库, 而<b>仓 1(chat-platform) 已经占用了
 * {@code application} 这个名字</b> —— 那是它的应用平台注册表
 * ({@code com.luxera.companion.application.domain.ApplicationRecord},
 * 列为 id/category/developer_id/latest_version/name/status/state_json),
 * 与本表没有一列重合。
 *
 * <p>撞名的后果不是"两边各写各的", 而是<b>这张表永远建不出来</b>。{@code ddl-auto: update}
 * 每次都试着补列, 而 PostgreSQL 拒绝往一张已有数据的表上加 NOT NULL 列:
 *
 * <pre>
 *   alter table if exists application add column application_key varchar(64) not null
 *   → ERROR: column "application_key" of relation "application" contains null values
 * </pre>
 *
 * 于是每一次启动都刷一遍这段报错, 两条索引 ({@code idx_application_device_key} /
 * {@code idx_application_type}) 也随之建不出来 —— 索引建在那些从来没被加上的列上。
 * 而这张表的列至今<b>一个都不存在</b>, 所以它到现在也从未写进过一行: 改成新表名
 * 不需要任何数据迁移。
 *
 * <p>更麻烦的是它<b>不会自愈</b>: 只要两仓共库、名字还撞着, 这一列就永远加不上。
 * 现在的症状只是启动日志噪声(与一个用不了的仓储类 —— 它还没有运行期调用方),
 * 但等世界模型那边接上第一个消费者, 报出来的会是
 * {@code column "device_id" does not exist}。那不像是"表名撞了", 像是"实体写错了"。
 *
 * <p>本仓的表名约定是单数 {@code <域>_<物>}（{@code action_command} / {@code person_object} /
 * {@code phone_notification}）, 所以 {@code device_application} 是与它一致的那个名字。
 */
@Entity
@Table(name = "device_application", indexes = {
        // ① "这台设备装了哪些应用" —— 设备装配与通知路由的第一个查询。
        //    它同时是"按 key 找应用"的索引: (device_id, application_key) 上
        //    设备内的 key 唯一, 所以这条索引与一条唯一约束的代价相同、收益相同。
        @Index(name = "idx_application_device_key", columnList = "device_id,application_key"),
        // ② "她手机上装了聊天软件吗" —— 按类型跨设备查。
        @Index(name = "idx_application_type",
                columnList = "application_type_namespace,application_type_name")
})
@Getter
@Setter
public class DeviceApplicationRecord {

    /**
     * 行的 id。
     *
     * <p>为什么不把主键做成 {@code (device_id, application_key)} 的复合键:
     * 本仓的既有约定是<b>每一张表都有一个单列 String 主键</b>
     * （{@code grep -rn "@EmbeddedId\|@IdClass"} 零命中）, 而 JPA 的
     * {@code JpaRepository<Entity, String>} 也要求这样。破一次例的代价是
     * 这个实体成为唯一一个 {@code JpaRepository<Entity, CompositeKey>},
     * 于是所有按 id 操作的通用代码（诊断面板、导出、清库脚本）都要为它分支。
     *
     * <p>"设备内不重名"这条约束因此落在 {@code idx_application_device_key} 那条索引上
     * —— 它现在<b>不是</b>唯一索引, 因为 §7.2 只要求索引而 §8.3 的迁移路径要求
     * "直接改, 不留旧路径", 而安装流程本身可能因为重启而重放。
     * 若将来发现重复行, 把那条索引改成 unique 即可, 不影响本类的形状。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 装在哪台设备上 —— {@code Device.id()} 的值。
     *
     * <p>设备 id 也是 {@code ObjectId} 形状（{@code device.phone:home-1}）,
     * 所以这一列与 {@link WorldObjectRecord#getId()} 一样是 192 ——
     * 两张表之间要能对得上。
     */
    @Column(name = "device_id", nullable = false, length = 192)
    private String deviceId;

    /**
     * 应用的稳定标识 —— {@code DeviceApplication.id()}。
     *
     * <p>见类注释: 这是这张表真正的身份。长度 64: {@code id()} 的语法约束是
     * "小写、短横线", 而它还会成为能力 key 的命名空间
     * （{@code <id>.something}）—— 能力 key 的语法校验会把过长的 id 挡在注册阶段,
     * 不会拖到落库。64 与 {@code HumanRecord} 的 id 同宽, 是"标识符"这一类的统一宽度。
     */
    @Column(name = "application_key", nullable = false, length = 64)
    private String applicationKey;

    /** 类型三元组 —— 见类注释"目前不重合"那一段。 */
    @Column(name = "application_type_namespace", nullable = false, length = 96)
    private String applicationTypeNamespace;

    @Column(name = "application_type_name", nullable = false, length = 96)
    private String applicationTypeName;

    @Column(name = "application_type_version", nullable = false)
    private int applicationTypeVersion;

    /**
     * 应用自己的状态 —— 登录会话、未读数、免打扰开关。
     *
     * <p><b>这一列里绝不能出现聊天正文。</b> §9 验收标准 E 的原文是
     * "聊天消息正文不得绕过 {@code Mind} 的工作记忆直接落进领域模型", 而这张表
     * 是正文最容易漏进来的地方 —— {@code ChatApplication} 手里确实拿着消息,
     * 而"顺手把最近几条存下来, 免得重启后要重新拉"是一个极其自然的想法。
     * 它的后果是: 正文一旦落在这里, 任何读到这一列的地方（诊断面板、导出、
     * 未来的某个 LLM 拼 context 的工具）都能看见它,
     * <b>而 {@code Mind} 得到它的唯一合法路径（{@code ReadMessagesAction}）就被绕过了</b>。
     *
     * <p>允许存在的是"指向正文的引用"（会话 id、消息 id）与计数, 不是正文本身。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "state_json", columnDefinition = "text")
    private Map<String, Object> stateJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String describe() {
        return applicationKey + "[" + id + "] on " + deviceId;
    }
}
