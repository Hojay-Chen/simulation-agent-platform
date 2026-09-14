package com.luxera.companion.persona;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.UUID;

/**
 * 数字人格实例: 用户真正长期相处的 Companion。
 * 年龄永远通过 birth_date 动态计算。
 */
@Entity
@Table(name = "companions")
@Getter
@Setter
public class Companion {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Convert(converter = PlaceJsonConverter.class)
    @Column(name = "birth_place", columnDefinition = "text")
    private Place birthPlace;

    @Column(length = 64)
    private String nationality = "Chinese";

    @Column(length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(length = 500)
    private String greeting;

    @Column(length = 32)
    private String status = "active";

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 动态年龄 */
    public int age() {
        if (birthDate == null) return 0;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}
