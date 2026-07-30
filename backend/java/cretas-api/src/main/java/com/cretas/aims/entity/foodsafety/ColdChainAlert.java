package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链温控偏离报警事件 (Cold Chain Alert) — Sprint 9 P2.D.
 *
 * <p>当 {@link ColdChainTempReading} 持续偏离超过设备的 {@code toleranceMinutes}
 * 阈值时触发. 由 {@code ColdChainMonitoringScheduler} 或 IoT push 端创建.
 *
 * <p>核心字段:
 * <ul>
 *   <li>{@code alertTime} 触发时刻 (= 偏离开始时间 + toleranceMinutes)</li>
 *   <li>{@code severity} WARNING (持续 < 30 min) / CRITICAL (持续 ≥ 30 min)</li>
 *   <li>{@code minTemp / maxTemp} 偏离期内极值</li>
 *   <li>{@code durationMinutes} 偏离持续时长</li>
 *   <li>{@code status} OPEN → ACKNOWLEDGED → RESOLVED 状态机</li>
 * </ul>
 *
 * <p>状态机:
 * <pre>
 *   OPEN ── ack_reason ──→ ACKNOWLEDGED ── resolve ──→ RESOLVED
 * </pre>
 *
 * <p>OPEN/ACKNOWLEDGED 报警计入 KPI dashboard, RESOLVED 归档.
 *
 * <p>HJ 0 偏离报警 → Cretas 食品垂直 V2 差异化护城河 +1.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "cold_chain_alerts",
        indexes = {
                @Index(name = "idx_cold_chain_alerts_factory_status",
                        columnList = "factory_id,status,alert_time"),
                @Index(name = "idx_cold_chain_alerts_equipment",
                        columnList = "equipment_id,alert_time"),
                @Index(name = "idx_cold_chain_alerts_severity",
                        columnList = "factory_id,severity,status")
        })
@Where(clause = "deleted_at IS NULL")
public class ColdChainAlert extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 关联 ColdChainEquipment.id (Long FK). */
    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** 报警触发时刻 (偏离持续超 toleranceMinutes 后). */
    @Column(name = "alert_time", nullable = false)
    private LocalDateTime alertTime;

    /** 严重等级: WARNING (< 30 min) / CRITICAL (>= 30 min). */
    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private String severity = "WARNING";

    /** 偏离期内最低温度 (℃). */
    @Column(name = "min_temp", nullable = false, precision = 5, scale = 2)
    private BigDecimal minTemp;

    /** 偏离期内最高温度 (℃). */
    @Column(name = "max_temp", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxTemp;

    /** 偏离持续时长 (min). */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    /** 状态: OPEN / ACKNOWLEDGED / RESOLVED. */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "OPEN";

    /** 确认时刻. */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** 确认操作员 user_id. */
    @Column(name = "acknowledged_by_user_id")
    private Long acknowledgedByUserId;

    /**
     * R3 防呆 enum: TEMPORARY_DOOR_OPEN (开门导致) / POWER_FLUCTUATION (电压波动) /
     * MAINTENANCE (设备维修) / SENSOR_FAULT (探头故障) / SPOILED (产品已变质) / OTHER (其他).
     */
    @Column(name = "ack_reason", length = 50)
    private String ackReason;

    /** 确认备注 (选 "其他" 时必填). */
    @Column(name = "ack_remark", columnDefinition = "TEXT")
    private String ackRemark;

    /** 解决时刻. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 解决操作员 user_id. */
    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;
}
