package com.cretas.aims.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.cretas.aims.entity.enums.DeviceCategory;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Where;
/**
 * 工厂设备实体类
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-01-09
 * @updated 2025-12-22 - 主键改为 BIGINT 自增，提升 JOIN 性能
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "equipmentUsages", "maintenanceRecords"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "factory_equipment",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "code"})
       },
       indexes = {
           @Index(name = "idx_equipment_factory", columnList = "factory_id"),
           @Index(name = "idx_equipment_status", columnList = "status")
       }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryEquipment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;  // 改为 Long 自增主键，提升 JOIN 性能
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "code", nullable = false, length = 50)
    private String code;
    @Column(name = "equipment_code", nullable = false, length = 50)
    private String equipmentCode;
    @Column(name = "equipment_name", nullable = false, length = 191)
    private String equipmentName;
    @Column(name = "type", length = 50)
    private String type;
    @Column(name = "model", length = 100)
    private String model;
    @Column(name = "manufacturer", length = 100)
    private String manufacturer;
    @Column(name = "purchase_date")
    private LocalDate purchaseDate;
    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;
    @Column(name = "depreciation_years")
    private Integer depreciationYears;
    @Column(name = "hourly_cost", precision = 10, scale = 2)
    private BigDecimal hourlyCost;
    @Column(name = "power_consumption_kw", precision = 10, scale = 2)
    private BigDecimal powerConsumptionKw;
    @Column(name = "status", nullable = false, length = 20)
    private String status = "idle"; // idle, running, maintenance, scrapped
    @Column(name = "location", length = 100)
    private String location;
    @Column(name = "total_running_hours")
    private Integer totalRunningHours = 0;
    @Column(name = "maintenance_interval_hours")
    private Integer maintenanceIntervalHours;
    @Column(name = "last_maintenance_date")
    private LocalDate lastMaintenanceDate;
    @Column(name = "next_maintenance_date")
    private LocalDate nextMaintenanceDate;
    @Column(name = "warranty_expiry_date")
    private LocalDate warrantyExpiryDate;
    @Column(name = "serial_number", length = 100)
    private String serialNumber;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * 操作员姓名 (从User表关联查询，由Service层填充)
     */
    @Transient
    private String operatorName;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========== IoT 相关字段 ==========

    /**
     * IoT设备编码
     */
    @Column(name = "iot_device_code", length = 100)
    private String iotDeviceCode;

    /**
     * 设备类别: TRADITIONAL / IOT_SCALE / IOT_CAMERA / IOT_SENSOR
     * @deprecated 使用 {@link #getUnifiedDeviceType()} 获取统一设备类型
     */
    @Deprecated
    @Enumerated(EnumType.STRING)
    @Column(name = "device_category", length = 20)
    private DeviceCategory deviceCategory;

    /**
     * 获取统一设备类型
     * 将旧的 deviceCategory 映射到新的 UnifiedDeviceType
     *
     * @return 统一设备类型
     */
    @Transient
    public UnifiedDeviceType getUnifiedDeviceType() {
        return UnifiedDeviceType.fromDeviceCategory(this.deviceCategory);
    }

    /**
     * 设置统一设备类型
     * 自动转换为旧的 deviceCategory 以保持兼容
     *
     * @param unifiedType 统一设备类型
     */
    public void setUnifiedDeviceType(UnifiedDeviceType unifiedType) {
        this.deviceCategory = unifiedType != null ? unifiedType.toDeviceCategory() : null;
    }

    /**
     * 关联秤协议配置 scale_protocol_configs.id
     */
    @Column(name = "scale_protocol_id", length = 50)
    private String scaleProtocolId;

    /**
     * 关联秤品牌型号 scale_brand_models.id
     */
    @Column(name = "scale_brand_model_id", length = 50)
    private String scaleBrandModelId;

    /**
     * MQTT订阅主题
     */
    @Column(name = "mqtt_topic", length = 255)
    private String mqttTopic;

    /**
     * 秤连接参数 (JSON格式)
     * 示例: {"comPort":"COM3", "baudRate":9600}
     */
    @Column(name = "scale_connection_params", columnDefinition = "TEXT")
    private String scaleConnectionParams;

    /**
     * 最后一次称重值
     */
    @Column(name = "last_weight_reading", precision = 12, scale = 4)
    private BigDecimal lastWeightReading;

    /**
     * 最后称重时间
     */
    @Column(name = "last_weight_time")
    private LocalDateTime lastWeightTime;

    /**
     * 最后数据接收时间
     */
    @Column(name = "last_data_received")
    private LocalDateTime lastDataReceived;

    // ========== 单位转换方法 ==========

    /**
     * 获取维护间隔（天）- 供前端使用
     * 后端存储为小时，前端期望天数
     */
    @JsonProperty("maintenanceInterval")
    public Integer getMaintenanceIntervalDays() {
        return maintenanceIntervalHours != null ? maintenanceIntervalHours / 24 : null;
    }

    /**
     * 设置维护间隔（天）- 接收前端传入
     * 自动转换为小时存储
     */
    @JsonProperty("maintenanceInterval")
    public void setMaintenanceIntervalDays(Integer days) {
        this.maintenanceIntervalHours = days != null ? days * 24 : null;
    }

    // 乐观锁版本号
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    // Hibernate's VersionGeneration.generate() NPEs when @Version field is null,
    // which happens for rows inserted before the column was added (14/22 rows on
    // 2026-04-17). DB-side default + backfill fixes existing data; this getter
    // keeps already-loaded-as-null entities safe until the EntityManager reloads.
    public Integer getVersion() {
        return version != null ? version : 1;
    }

    // 关联关系 (使用 @JsonIgnore 防止循环引用)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "equipment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BatchEquipmentUsage> equipmentUsages = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "equipment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<EquipmentMaintenance> maintenanceRecords = new ArrayList<>();
}
