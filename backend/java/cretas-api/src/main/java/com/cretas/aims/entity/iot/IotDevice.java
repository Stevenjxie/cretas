package com.cretas.aims.entity.iot;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * IoT设备实体类
 * 对应数据库表 iot_devices
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-04
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "iot_devices",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "device_code"})
       },
       indexes = {
           @Index(name = "idx_iot_device_factory", columnList = "factory_id"),
           @Index(name = "idx_iot_device_status", columnList = "status"),
           @Index(name = "idx_iot_device_type", columnList = "device_type")
       }
)
@Where(clause = "deleted_at IS NULL")
public class IotDevice extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;  // UUID

    @Column(name = "device_code", nullable = false, length = 100)
    private String deviceCode;

    /**
     * 设备类型
     * @deprecated 使用 {@link #getUnifiedDeviceType()} 获取统一设备类型
     */
    @Deprecated
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 20)
    private DeviceType deviceType;

    /**
     * 获取统一设备类型
     * 将旧的 deviceType 映射到新的 UnifiedDeviceType
     *
     * @return 统一设备类型
     */
    @Transient
    public UnifiedDeviceType getUnifiedDeviceType() {
        return UnifiedDeviceType.fromIotDeviceType(this.deviceType);
    }

    /**
     * 设置统一设备类型
     * 自动转换为旧的 deviceType 以保持兼容
     *
     * @param unifiedType 统一设备类型
     */
    public void setUnifiedDeviceType(UnifiedDeviceType unifiedType) {
        this.deviceType = unifiedType != null ? unifiedType.toIotDeviceType() : null;
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "equipment_id")
    private Long equipmentId;  // 关联 factory_equipment.id，可为空

    @Column(name = "protocol_id", length = 50)
    private String protocolId;  // 关联协议配置，可为空

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceStatus status = DeviceStatus.OFFLINE;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "last_data_time")
    private LocalDateTime lastDataTime;

    // 乐观锁版本号
    @Version
    @Column(name = "version")
    private Integer version;
}
