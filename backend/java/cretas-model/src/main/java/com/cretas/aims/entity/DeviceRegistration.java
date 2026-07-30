package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 设备注册实体类
 * 用于存储用户设备的推送通知 Token
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "device_registrations",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"device_id", "factory_id"}),
           @UniqueConstraint(columnNames = {"push_token"})
       },
       indexes = {
           @Index(name = "idx_user_id", columnList = "user_id"),
           @Index(name = "idx_factory_id", columnList = "factory_id"),
           @Index(name = "idx_push_token", columnList = "push_token"),
           @Index(name = "idx_last_active", columnList = "last_active_at")
       }
)
@Where(clause = "deleted_at IS NULL")
public class DeviceRegistration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 工厂 ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * Expo Push Token
     */
    @Column(name = "push_token", nullable = false, unique = true, length = 255)
    private String pushToken;

    /**
     * 设备唯一标识符
     */
    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    /**
     * 平台类型 (ios/android)
     */
    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /**
     * 设备名称
     */
    @Column(name = "device_name", length = 100)
    private String deviceName;

    /**
     * 设备型号
     */
    @Column(name = "device_model", length = 100)
    private String deviceModel;

    /**
     * 操作系统版本
     */
    @Column(name = "os_version", length = 50)
    private String osVersion;

    /**
     * 应用版本
     */
    @Column(name = "app_version", length = 50)
    private String appVersion;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * 是否启用推送
     */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /**
     * 用户关联（Lazy加载）
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    /**
     * 更新最后活跃时间
     */
    public void updateLastActive() {
        this.lastActiveAt = LocalDateTime.now();
    }

    /**
     * 检查设备是否长时间未活跃（超过30天）
     */
    public boolean isInactive() {
        if (this.lastActiveAt == null) {
            return true;
        }
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        return this.lastActiveAt.isBefore(thirtyDaysAgo);
    }

    /**
     * 启用推送
     */
    public void enable() {
        this.isEnabled = true;
        updateLastActive();
    }

    /**
     * 禁用推送
     */
    public void disable() {
        this.isEnabled = false;
    }

    /**
     * 更新 Token
     */
    public void updateToken(String newToken) {
        this.pushToken = newToken;
        updateLastActive();
    }
}
