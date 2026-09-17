package com.luxera.companion.person;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一次账号ID 修改的流水。
 *
 * <h2>为什么是流水表, 而不是 Person 上的两个计数器列</h2>
 *
 * "每年只能修改三次"最省事的实现是在 {@code Person} 上加 {@code handle_change_count} +
 * {@code handle_window_start}。那样做丢掉两样东西:
 *
 * <ol>
 *   <li><b>旧账号ID 是什么。</b> 账号ID 的用途之一就是"报给别人" —— 改完之后, 别人手里
 *       那个旧 ID 应当能解释成"她改过名", 而不是"查无此人"。流水表留得住这条线索。</li>
 *   <li><b>改的节奏。</b> 只有计数时, 用户问"我什么时候能再改"要靠一个反推出来的日期;
 *       有流水就是直接读出来的。界面上要显示这个日期(见 {@code HandleQuota}).</li>
 * </ol>
 *
 * <h2>为什么是滑动 365 天, 不是自然年</h2>
 *
 * "每年三次"字面上也可以理解成"自然年重置"。选滑动窗口是因为自然年有一个荒谬的后果:
 * 12 月 31 日改满三次, 1 月 1 日又是三次 —— 一天之内改六次, 而配额的意思恰恰是别这样。
 * 滑动窗口没有这个缝, 也不需要处理"按哪个时区算新年"。代价是用户读到的规则要从
 * "每年三次"理解成"任意 365 天内三次", 所以界面上给出**具体哪一天能再改**, 而不是让
 * 用户自己算。
 *
 * <p>这张表只增不改不删: 它是一条流水, 删掉一条就等于把配额还回去一次。
 */
@Entity
@Table(name = "person_handle_changes", indexes = {
        @Index(name = "idx_phc_person", columnList = "person_id")
})
@Getter
@Setter
public class PersonHandleChange {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** 被改的那个「人」: USER 时是 users.id, AGENT 时是 companions.id */
    @Column(name = "person_id", nullable = false, length = 36)
    private String personId;

    /** 改之前叫什么。首次由系统分配的那一个也算 —— 用户也会把它报给别人 */
    @Column(name = "old_handle", length = 32)
    private String oldHandle;

    @Column(name = "new_handle", nullable = false, length = 32)
    private String newHandle;

    /**
     * 手工赋值而不是 {@code @CreationTimestamp} —— 配额是按这个字段的滑动窗口算的,
     * 而滑动窗口的边界必须是**服务端判定的那一刻**, 不是数据库写入的那一刻。
     * 两者在正常情况下一致, 但测试要能把它设成"一年零一天前"来验证窗口真的会滑动。
     */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
