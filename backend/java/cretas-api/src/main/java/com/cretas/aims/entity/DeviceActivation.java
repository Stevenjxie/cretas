package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 设备激活实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "device_activations",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"activation_code"}),
           @UniqueConstraint(columnNames = {"device_id", "factory_id"})
       },
       indexes = {
           @Index(name = "idx_activation_factory", columnList = "factory_id"),
           @Index(name = "idx_activation_status", columnList = "status"),
           @Index(name = "idx_activation_device", columnList = "device_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class DeviceActivation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;
    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    /**
     * 激活码
     */
    @Column(name = "activation_code", nullable = false, unique = true, length = 50)
    private String activationCode;

    /**
     * 设备ID
     */
    @Column(name = "device_id", length = 100)
    private String deviceId;

    /**
     * 设备名称
     */
    @Column(name = "device_name", length = 100)
    private String deviceName;

    /**
     * 设备类型
     */
    @Column(name = "device_type", length = 50)
    private String deviceType;

    /**
     * 设备型号
     */
    @Column(name = "device_model", length = 100)
    private String deviceModel;

    /**
     * 操作系统
     */
    @Column(name = "os_type", length = 50)
    private String osType;

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
     * IP地址
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /**
     * MAC地址
     */
    @Column(name = "mac_address", length = 50)
    private String macAddress;

    /**
     * 激活状态
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, ACTIVATED, EXPIRED, REVOKED

    /**
     * 激活时间
     */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /**
     * 激活人ID
     */
    @Column(name = "activated_by")
    private Long activatedBy;

    /**
     * 过期时间
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * 最大设备数限制
     */
    @Column(name = "max_devices")
    @Builder.Default
    private Integer maxDevices = 1;

    /**
     * 已使用设备数
     */
    @Column(name = "used_devices")
    @Builder.Default
    private Integer usedDevices = 0;

    /**
     * 是否允许多设备
     */
    @Column(name = "allow_multiple_devices")
    @Builder.Default
    private Boolean allowMultipleDevices = false;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    /**
     * 检查激活码是否可用
     */
    public boolean isValid() {
        if (!"PENDING".equals(status) && !"ACTIVATED".equals(status)) {
            return false;
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        if (usedDevices >= maxDevices && !allowMultipleDevices) {
            return false;
        }
        return true;
    }

    /**
     * 激活设备
     */
    public void activate(String deviceId, Long userId) {
        this.deviceId = deviceId;
        this.status = "ACTIVATED";
        this.activatedAt = LocalDateTime.now();
        this.activatedBy = userId;
        this.usedDevices = (usedDevices != null ? usedDevices : 0) + 1;
        this.lastActiveAt = LocalDateTime.now();
    }
}
