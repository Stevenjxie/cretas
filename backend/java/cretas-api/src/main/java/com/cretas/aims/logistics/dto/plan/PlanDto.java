package com.cretas.aims.logistics.dto.plan;

import com.cretas.aims.logistics.entity.enums.PlanDistanceSource;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
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
    private Integer totalStores;
    private Integer totalTrips;
    private BigDecimal totalDistanceKm;
    private String createdBy;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private Long version;
}
