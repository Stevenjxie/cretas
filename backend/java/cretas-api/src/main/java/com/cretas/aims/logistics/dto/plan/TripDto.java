package com.cretas.aims.logistics.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单个车次 — 字段名对齐前端既有 {@code web-admin/src/api/logistics.ts} {@code LogisticsTrip}：
 * storeIds / segmentKeys / geometry / segmentDistances / totalDistanceKm / totalVolumeCbm /
 * loadRate / status。
 *
 * <p>⚠️ {@code status} 是 lowercase 字符串（{@code draft/needs_vehicle/needs_driver/
 * needs_route_data/confirmed}），不是 Java {@link com.cretas.aims.logistics.entity.enums.TripStatus}
 * 枚举名（UPPERCASE）— 前端契约 {@code TripStatus} type 和现有 Vue 组件
 * （{@code RouteCards.vue}/{@code workbench/index.vue}/{@code useLogisticsDemoState.ts} 等）
 * 全部用小写字符串判断状态，源自最初的前端 mock（{@code routeEngine.ts}）。
 * 大小写转换在 {@code LogisticsPlanMapper} 完成，绝不在这里直接塞 Java 枚举对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripDto {
    private String id;
    private String planId;
    private Integer tripNo;
    private String vehicleId;
    private String driverId;
    private String vehiclePlate;
    private String driverName;
    private List<String> storeIds;
    private List<String> segmentKeys;
    /** 车次几何轨迹（地图坐标点）— Phase 4 未接真实地图 provider，恒为空数组，不伪造点位。 */
    private List<Object> geometry;
    private List<BigDecimal> segmentDistances;
    private BigDecimal totalDistanceKm;
    private BigDecimal totalVolumeCbm;
    private BigDecimal totalWeightKg;
    private BigDecimal loadRate;
    private BigDecimal weightLoadRate;
    /** lowercase：draft / needs_vehicle / needs_driver / needs_route_data / confirmed。 */
    private String status;
    private List<StopDto> stops;
    private Long version;
}
