package com.luxera.companion.phone;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 手机状态(§五/§三十五): 手机不是总是响的 —— 静音/勿扰/手机位置/电量决定"消息能否到她"。
 * 由作息+时间确定性派生(不常变), 不建独立表则用派生即可, 这里落库以便前端展示。
 */
@Entity
@Table(name = "companion_phone_states")
@Getter
@Setter
public class PhoneState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    /** sound | vibrate | silent | dnd */
    @Column(name = "notification_mode", nullable = false, length = 16)
    private String notificationMode = "vibrate";

    @Column(name = "sound_enabled", nullable = false)
    private boolean soundEnabled = false;

    @Column(name = "vibration_enabled", nullable = false)
    private boolean vibrationEnabled = true;

    /** hand | desk | bag | other_room */
    @Column(name = "phone_location", nullable = false, length = 16)
    private String phoneLocation = "hand";

    @Column(nullable = false)
    private double battery = 0.85;

    @Column(name = "screen_on", nullable = false)
    private boolean screenOn = false;

    /** 勿扰模式(会议/睡觉) */
    @Column(name = "do_not_disturb", nullable = false)
    private boolean doNotDisturb = false;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt = LocalDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
