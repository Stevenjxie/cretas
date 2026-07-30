package com.cretas.aims.entity.iot;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * IoT设备数据实体类
 * 对应数据库表 iot_device_data
 *
 * 注意: 此表为时序数据表，不继承BaseEntity，不使用软删除
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-04
 */
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "iot_device_data",
       indexes = {
           @Index(name = "idx_iot_data_device", columnList = "device_id"),
           @Index(name = "idx_iot_data_factory", columnList = "factory_id"),
           @Index(name = "idx_iot_data_collected", columnList = "collected_at"),
           @Index(name = "idx_iot_data_processed", columnList = "processed"),
           @Index(name = "idx_iot_data_batch", columnList = "production_batch_id")
       }
)
public class IotDeviceData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 36)
    private String deviceId;  // 关联 iot_devices.id

    @Column(name = "device_code", nullable = false, length = 100)
    private String deviceCode;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;  // WEIGHT, TEMPERATURE, HUMIDITY, IMAGE

    @Column(name = "data_value", nullable = false, columnDefinition = "TEXT")
    private String dataValue;  // JSON格式的数据值

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;  // 设备采集时间

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;  // 服务器接收时间

    @Column(name = "processed", nullable = false)
    private Boolean processed = false;  // 是否已处理

    @Column(name = "production_batch_id")
    private Long productionBatchId;  // 关联生产批次，可为空
}
