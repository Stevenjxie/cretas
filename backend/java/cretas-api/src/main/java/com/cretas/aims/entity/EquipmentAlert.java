package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.DeviceAlertLevel;
import com.cretas.aims.entity.enums.AlertStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 设备告警实体类
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-19
 * @updated 2025-12-22 - 继承 BaseEntity 添加软删除和乐观锁支持
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"equipment", "factory"})
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "equipment_alerts",
       indexes = {
           @Index(name = "idx_alert_equipment", columnList = "equipment_id"),
           @Index(name = "idx_alert_factory", columnList = "factory_id"),
           @Index(name = "idx_alert_status", columnList = "status"),
           @Index(name = "idx_alert_triggered_at", columnList = "triggered_at")
       }
)
@Where(clause = "deleted_at IS NULL")
public class EquipmentAlert extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    // 乐观锁版本号 (必须初始化为0，否则Hibernate更新时NPE)
    @Version
    @Column(name = "version")
    @Builder.Default
    private Integer version = 0;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;  // 修改为Long，与FactoryEquipment.id一致

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;  // 告警类型：维护提醒、保修即将到期等

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    @Builder.Default
    private DeviceAlertLevel level = DeviceAlertLevel.INFO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AlertStatus status = AlertStatus.ACTIVE;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;  // 确认人用户ID

    @Column(name = "acknowledged_by_name", length = 100)
    private String acknowledgedByName;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;  // 解决人用户ID

    @Column(name = "resolved_by_name", length = 100)
    private String resolvedByName;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;  // 解决方案备注

    @Column(name = "ignored_at")
    private LocalDateTime ignoredAt;

    @Column(name = "ignored_by")
    private Long ignoredBy;  // 忽略人用户ID

    @Column(name = "ignored_by_name", length = 100)
    private String ignoredByName;

    @Column(name = "ignore_reason", columnDefinition = "TEXT")
    private String ignoreReason;  // 忽略原因

    // 关联关系 - 使用 FactoryEquipment（Equipment 已废弃）
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id", referencedColumnName = "id", insertable = false, updatable = false)
    private FactoryEquipment equipment;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
}
