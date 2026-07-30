package com.cretas.aims.logistics.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    /** 车次几何轨迹（前端 SVG 兜底地图 {x,y} 像素点，{@code MapPoint[]} 语义）— 后端不产出，恒为空数组，不伪造点位。道路折线见 {@link #roadPath}。 */
    private List<Object> geometry;
    /**
     * 车次道路折线（每点 {@code {"lng":..,"lat":..}}，<b>GCJ-02</b>）— 档1-B (2026-07-11) 从
     * {@code logistics_trips.road_path} 持久化列直读（生成/重生成/人工调整时地图 provider
     * 计算一次并缓存，查看零地图 API 调用）；无缓存路线 → 空数组（诚实），前端回落既有画法。
     */
    private List<Map<String, Object>> roadPath;
    private List<BigDecimal> segmentDistances;
    private BigDecimal totalDistanceKm;
    /** 全程预计时长（分钟）— 地图 provider 路线成功才有值；边距离回落路径为 null（诚实无时长）。 */
    private BigDecimal totalDurationMin;
    /** 产出 roadPath 的地图 provider（AMAP/TENCENT/BAIDU）；无缓存路线为 null。 */
    private String routeProvider;
    private BigDecimal totalVolumeCbm;
    private BigDecimal totalWeightKg;
    private BigDecimal loadRate;
    private BigDecimal weightLoadRate;
    /** lowercase：draft / needs_vehicle / needs_driver / needs_route_data / confirmed。 */
    private String status;
    private List<StopDto> stops;
    private Long version;
    /**
     * 档4 多趟排班 (车回仓补货再出发) 计划时刻 — 全 nullable (缺坐标/无车/人工调整后为 null,
     * 诚实不伪造时刻)。当日分钟数, e.g. 480=08:00。
     */
    private Integer plannedDepartMin;
    /** 计划回仓时刻 (当日分钟) — 多趟链下一趟出发 = 本值 + 装货 RELOAD 时间 (20min)。 */
    private Integer returnToDepotMin;
    /** 迟回仓 — 计划回仓晚于 min(司机班次结束, 车辆可用截止); null=无时刻/无约束 (区分未知与不迟)。 */
    private Boolean lateReturn;
    /** 该车当日第几趟 (1-based); 2+ = 回仓补货再出发的后续趟 (UI 显示 第1趟/第2趟)。 */
    private Integer vehicleTripSeq;
}
