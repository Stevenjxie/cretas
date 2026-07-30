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
     * 全程预计时长 (分钟, scale=2)。Nullable — 只有地图 provider 路线规划成功才有值
     * (V20261028_54, 档1-B); 边距离回落路径没有时长数据, 保持 null (诚实, 不伪造)。
     */
    @Column(name = "total_duration_min", precision = 10, scale = 2)
    private BigDecimal totalDurationMin;

    /**
     * 产出 {@link #geometry} 路线的地图 provider (AMAP/TENCENT/BAIDU)。Nullable —
     * 与 {@code totalDurationMin} 同生命周期, 供排查"这条线是谁画的" (V20261028_54, 档1-B)。
     */
    @Column(name = "route_provider", length = 16)
    private String routeProvider;

    /**
     * 车次几何轨迹 (JSONB, e.g. polyline / waypoints). Nullable — Phase 3 排线算法写入。
     * H2 PG-compat test 下置 null (同项目 jsonb round-trip 既有限制, 见
     * {@code SemiFinishedInventoryRepositoryTest} 注释)。
     *
     * <p>⚠️ 语义是前端 SVG 兜底地图的 {x,y} 像素点 ({@code MapPoint[]}), <b>不是</b>经纬度 —
     * 道路折线 (GCJ-02 lng/lat) 在独立的 {@link #roadPath} 列, 别混用 (档1-B 2026-07-11 决策)。
     * 类型必须保持 {@code List<Map<String,Object>>} (JSONB 数组): 曾因映射成 Map 触发 500。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private List<Map<String, Object>> geometry;

    /**
     * 道路折线缓存 (JSONB 数组, 每点 {@code {"lng":..,"lat":..}}, <b>GCJ-02</b>) —
     * 档1-B (V20261028_54): 生成/重生成/人工调整时调用地图 provider (多提供商链) 计算一次并
     * 持久化于此; 查看计划直读本列, 零地图 API 调用。Nullable — 路线规划失败/未启用时为 null
     * (诚实降级, 前端回落既有画法)。与 {@link #geometry} (SVG {x,y}) 语义不同, 不得混用。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "road_path", columnDefinition = "jsonb")
    private List<Map<String, Object>> roadPath;

    /**
     * 计划出发时刻 (当日分钟, e.g. 480=08:00) — 档4 多趟排班 (V20261028_56)。
     * Nullable — 仅该车全部车次坐标齐全时由排线算法推演填充; 人工调整后置 null (时刻已失真,
     * 诚实不显示过期时刻, regenerate 重算)。
     */
    @Column(name = "planned_depart_min")
    private Integer plannedDepartMin;

    /**
     * 计划回仓时刻 (当日分钟) — 末站卸货 + 回程行驶后回到 DEPOT。与 {@link #plannedDepartMin}
     * 同生命周期 (档4, V20261028_56)。多趟链上, 下一趟出发 = 本值 + 装货 RELOAD 时间。
     */
    @Column(name = "return_to_depot_min")
    private Integer returnToDepotMin;

    /**
     * 迟回仓标记 — 计划回仓晚于 min(司机班次结束, 车辆可用截止)。Nullable — 无时刻/无约束时为
     * null (不是 false — 诚实区分"未知"与"不迟") (档4, V20261028_56)。
     */
    @Column(name = "late_return")
    private Boolean lateReturn;

    /**
     * 该车当日第几趟 (1-based) — 多趟排班时 2+ 表示回仓补货再出发的后续趟 (档4, V20261028_56)。
     * Nullable — 与时刻字段同生命周期。
     */
    @Column(name = "vehicle_trip_seq")
    private Integer vehicleTripSeq;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
