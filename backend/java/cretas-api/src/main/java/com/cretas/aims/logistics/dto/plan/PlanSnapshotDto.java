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
import java.util.List;

/**
 * 完整计划快照 — plan + trips + 未分配门店。前端地图/线路卡/人工调整/导出均使用同一份
 * {@code PlanSnapshot}（handoff §12.3），避免多接口拼装出不一致视图。每次写操作
 * （generate/regenerate/reorder/move/setVehicle/setDriver/confirmTrip/confirmPlan）
 * 都返回本 DTO 的最新版本，让前端一次性拿到全部新 version。
 *
 * <p>镜像前端 {@code web-admin/src/api/logistics.ts} {@code PlanSnapshot extends LogisticsPlan}
 * （字段展开为扁平结构，Lombok 侧不用继承，JSON 输出形状一致）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanSnapshotDto {
    // ---- LogisticsPlan 字段 ----
    private String id;
    private String factoryId;
    private String orderBatchId;
    private LocalDate planDate;
    private String planNumber;
    private BigDecimal targetLoadPct;
    private PlanStatus status;
    private PlanDistanceSource distanceSource;
    /** 排线优化模式（TIME=时间最快 / DISTANCE=路程最短）— 旧数据 null 按 DISTANCE 展示。 */
    private RouteOptimizeMode optimizeBy;
    private Integer totalStores;
    private Integer totalTrips;
    private BigDecimal totalDistanceKm;
    private String createdBy;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private Long version;

    // ---- PlanSnapshot 扩展字段 ----
    private List<TripDto> trips;
    private List<String> unassignedStoreIds;
    private Integer assignedVehicleCount;
    private Integer additionalVehicleCount;
}
