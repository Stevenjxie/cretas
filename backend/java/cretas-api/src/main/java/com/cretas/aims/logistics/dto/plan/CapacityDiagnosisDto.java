package com.cretas.aims.logistics.dto.plan;

import com.cretas.aims.logistics.entity.enums.CapacityVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 运力诊断 — 计划生成/查看时现算现返，回答「当前车队单轮运力是否够送完本批订单」
 * （fool-proof-design Rule 1：预先显示边界 + 具体数字 + next action，不是提交后才报错）。
 *
 * <p>挂在 {@link PlanSnapshotDto#getCapacityDiagnosis()}，nullable —
 * 计算逻辑见 {@link com.cretas.aims.logistics.util.CapacityDiagnosis}（独立于
 * {@code service/routing} 排线算法包，不影响其既有测试/逻辑）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapacityDiagnosisDto {
    /** SUFFICIENT / INSUFFICIENT / UNSERVABLE。 */
    private CapacityVerdict verdict;
    /** 本批（非取消）订单体积需求合计 (m³, 1位小数)。 */
    private BigDecimal totalDemandCbm;
    /** 本批（非取消）订单重量需求合计 (kg, 1位小数)。 */
    private BigDecimal totalDemandKg;
    /** 该工厂在册活跃车辆单轮运力合计 (m³, 1位小数) — 与本计划是否用到无关，是"整支车队"口径。 */
    private BigDecimal fleetSingleRoundCbm;
    /** 该工厂在册活跃车辆单轮载重合计 (kg, 1位小数)。 */
    private BigDecimal fleetSingleRoundKg;
    /** 本计划实际用到的车辆数（distinct vehicleId，仅统计已分配车次）。 */
    private Integer vehicleCount;
    /** 本计划已分配车辆的车次数（可能 > vehicleCount，即有车跑多趟）。 */
    private Integer usedTripCount;
    /** 需跑 2 趟及以上（回仓补货再出发）的车辆数。 */
    private Integer multiTripVehicleCount;
    /** 未能排入任何车次的订单数（UNSERVABLE 判据）。 */
    private Integer unassignedCount;
    /** 建议增补的运力估算 (m³, 向上取整) — SUFFICIENT 时为 0，诚实估算不精确到具体车型。 */
    private BigDecimal suggestedAddCbm;
    /** 面向调度员的中文提示 — 具体数字 + next action，禁止「失败」类模糊文案。 */
    private String message;
}
