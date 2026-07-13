package com.cretas.aims.logistics.dto.plan;

import com.cretas.aims.logistics.entity.enums.PlanDistanceSource;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 镜像前端 {@code web-admin/src/api/logistics.ts} {@code LogisticsPlan}（不含车次/未分配门店，
 * 用于 GET /plans 列表；完整快照见 {@link PlanSnapshotDto}）。
 *
 * <p>{@code status}/{@code distanceSource} 直接用 Java 枚举（UPPERCASE 与前端契约一致，
 * 与 trip 的 lowercase status 不同 — 见 {@link TripDto} 注释）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDto {
    private String id;
    private String factoryId;
    private String orderBatchId;
    private LocalDate planDate;
    private String planNumber;
    private BigDecimal targetLoadPct;
    private PlanStatus status;
    private PlanDistanceSource distanceSource;
    /** 排线优化模式（TIME/DISTANCE）— 旧数据 null 按 DISTANCE 展示。 */
    private RouteOptimizeMode optimizeBy;
    private Integer totalStores;
    private Integer totalTrips;
    private BigDecimal totalDistanceKm;
    private String createdBy;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private Long version;

    // ---- 执行进度(仅已确认/已导出计划有值; 其余为 null) ----
    /** 执行态: NOT_STARTED 未开始 / IN_PROGRESS 执行中 / COMPLETED 已完成; 未确认计划为 null。 */
    private String executionStatus;
    private Integer deliveredCount;
    private Integer exceptionCount;
    private Integer pendingCount;
    private Integer totalOrders;
}
