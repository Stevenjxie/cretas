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
 * 冷链温度读数实体 (Cold Chain Temperature Reading) — Sprint 9 P2.D.
 *
 * <p>高频时序数据 (典型每 5 min 1 个数据点). IoT 设备主动 push, 或 scheduler
 * polling fallback.
 *
 * <p>核心字段:
 * <ul>
 *   <li>{@code equipmentId} 关联 {@link ColdChainEquipment} (Long FK)</li>
 *   <li>{@code recordedAt} 读数时刻</li>
 *   <li>{@code temperature} 实测温度 ℃</li>
 *   <li>{@code isDeviation} 是否偏离目标区间 (写入时由 Tool / Scheduler 自动 mark)</li>
 *   <li>{@code alertSent} 是否已触发 ColdChainAlert (避免重复)</li>
 * </ul>
 *
 * <p>数据保留策略: 90 天后归档 (后续 Sprint 加 archive scheduler).
 * 软删除 via BaseEntity 沿用通用规范, 但实际 readings 不该删除.
 *
 * <p>HJ 0 温控读数追踪 → Cretas 食品垂直 V2 差异化护城河 +1.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "cold_chain_temp_readings",
        indexes = {
                @Index(name = "idx_cold_chain_readings_eq_time",
                        columnList = "equipment_id,recorded_at"),
                @Index(name = "idx_cold_chain_readings_factory_time",
                        columnList = "factory_id,recorded_at"),
                @Index(name = "idx_cold_chain_readings_deviation",
                        columnList = "equipment_id,is_deviation,alert_sent")
        })
@Where(clause = "deleted_at IS NULL")
public class ColdChainTempReading extends BaseEntity {

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

    /** 读数时刻 (典型每 5 min 1 个). */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /** 实测温度 (℃). */
    @Column(name = "temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal temperature;

    /** 是否偏离 (温度 < min OR > max). 写入时自动计算. */
    @Column(name = "is_deviation", nullable = false)
    @Builder.Default
    private boolean isDeviation = false;

    /** 是否已触发 ColdChainAlert (避免单点多次报警). */
    @Column(name = "alert_sent", nullable = false)
    @Builder.Default
    private boolean alertSent = false;
}
