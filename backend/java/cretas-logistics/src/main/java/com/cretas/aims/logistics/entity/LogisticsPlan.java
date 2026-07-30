package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.PlanDistanceSource;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 排线计划 — 一批订单 (见 {@link LogisticsOrderBatch}) → 一计划 → 多车次
 * (见 {@link LogisticsTrip})。
 *
 * <p>Maps to table {@code logistics_plans} (V20261028_01)。
 */
@Entity
@Table(name = "logistics_plans",
        indexes = {
                @Index(name = "idx_lp_factory_date", columnList = "factory_id, plan_date"),
                @Index(name = "idx_lp_batch", columnList = "order_batch_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsPlan extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (targetLoadPct == null) {
            targetLoadPct = new BigDecimal("88");
        }
        if (status == null) {
            status = PlanStatus.DRAFT;
        }
        if (distanceSource == null) {
            distanceSource = PlanDistanceSource.MAINTAINED_MATRIX;
        }
        if (optimizeBy == null) {
            optimizeBy = RouteOptimizeMode.DISTANCE;
        }
        if (totalStores == null) {
            totalStores = 0;
        }
        if (totalTrips == null) {
            totalTrips = 0;
        }
        if (totalDistanceKm == null) {
            totalDistanceKm = BigDecimal.ZERO;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@link LogisticsOrderBatch#getId()} (plain String, no JPA relation). */
    @Column(name = "order_batch_id", length = 36, nullable = false)
    private String orderBatchId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "plan_number", length = 64, nullable = false)
    private String planNumber;

    @Column(name = "target_load_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetLoadPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private PlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "distance_source", length = 24, nullable = false)
    private PlanDistanceSource distanceSource;

    /**
     * 排线优化模式 (时间最快/路程最短) — 生成时由调度员选择, {@code regeneratePlan} 复用本值
     * 保证口径一致 (V20261028_54, 档1-B)。列 nullable (旧数据无值), 读侧 null 按 DISTANCE 处理。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "optimize_by", length = 16)
    private RouteOptimizeMode optimizeBy;

    @Column(name = "total_stores", nullable = false)
    private Integer totalStores;

    @Column(name = "total_trips", nullable = false)
    private Integer totalTrips;

    @Column(name = "total_distance_km", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDistanceKm;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
