package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 车次 — 一计划 (见 {@link LogisticsPlan}) 内多车次, 每车次多停靠点
 * (见 {@link LogisticsStop})。
 *
 * <p>{@code status=NEEDS_ROUTE_DATA} = 距离诚实降级 (spec §4/§6 决策 6):
 * {@link LogisticsDistanceEdge} 缺边时不伪造公里数, 车次落此态而非静默拼直线距离。
 *
 * <p>Maps to table {@code logistics_trips} (V20261028_01)。同计划内车次号唯一
 * (DB {@code uq_lt_plan_tripno}, 部分索引 WHERE deleted_at IS NULL — JPA 侧约束语义略严,
 * 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_trips",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lt_plan_tripno_jpa", columnNames = {"plan_id", "trip_no"})
        },
        indexes = {
                @Index(name = "idx_lt_plan", columnList = "plan_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsTrip extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = TripStatus.DRAFT;
        }
        if (totalVolumeCbm == null) {
            totalVolumeCbm = BigDecimal.ZERO;
        }
        if (totalWeightKg == null) {
            totalWeightKg = BigDecimal.ZERO;
        }
        if (loadRate == null) {
            loadRate = BigDecimal.ZERO;
        }
        if (weightLoadRate == null) {
            weightLoadRate = BigDecimal.ZERO;
        }
        if (totalDistanceKm == null) {
            totalDistanceKm = BigDecimal.ZERO;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@link LogisticsPlan#getId()} (plain String, no JPA relation). */
    @Column(name = "plan_id", length = 36, nullable = false)
    private String planId;

    @Column(name = "trip_no", nullable = false)
    private Integer tripNo;

    /** FK → {@code vehicles.id}; nullable — trip may await vehicle assignment (status=NEEDS_VEHICLE). */
    @Column(name = "vehicle_id", length = 36)
    private String vehicleId;

    /** FK → {@link LogisticsDriver#getId()}; nullable — trip may await driver assignment (status=NEEDS_DRIVER). */
    @Column(name = "driver_id", length = 36)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private TripStatus status;

    @Column(name = "total_volume_cbm", nullable = false, precision = 12, scale = 3)
    private BigDecimal totalVolumeCbm;

    @Column(name = "total_weight_kg", nullable = false, precision = 12, scale = 3)
    private BigDecimal totalWeightKg;

    @Column(name = "load_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal loadRate;

    @Column(name = "weight_load_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal weightLoadRate;

    @Column(name = "total_distance_km", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDistanceKm;

    /**
     * 车次几何轨迹 (JSONB, e.g. polyline / waypoints). Nullable — Phase 3 排线算法写入。
     * H2 PG-compat test 下置 null (同项目 jsonb round-trip 既有限制, 见
     * {@code SemiFinishedInventoryRepositoryTest} 注释)。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private List<Map<String, Object>> geometry;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
