package com.cretas.aims.dto.laborefficiency;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP3 P1 #39: 成本组/公单级人工双口径聚合 DTO.
 *
 * <p>将同一销售订单 (sourceOrderId) 下的多批次人工成本归拢到订单级,
 * 并行展示研发预估 vs 实际人工，支持研发/销售超支推送决策。</p>
 *
 * <p>聚合规则:</p>
 * <ul>
 *   <li>totalQuotedLaborCost = Σ(quotedLaborCostPerKg × goodQuantityKg) per batch</li>
 *   <li>totalActualLaborCost = Σ(batch.laborCost) per batch</li>
 *   <li>varianceRate = (actual - quoted) / quoted × 100%; null = quoted 未配</li>
 * </ul>
 *
 * <p><b>RBAC 成本脱敏</b>: 所有绝对金额字段标记 {@link PriceSensitive},
 * 由 {@link com.cretas.aims.security.PriceFieldResponseAdvice} 对无
 * {@code procurement:price:view} 权限角色 strip 为 null。
 * 偏差率 % / 偏差状态标签 / 批次数量是相对指标, 不脱敏。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborEfficiencyOrderAggregateDTO {

    // ─────────────────────────── 订单维度 ───────────────────────────

    /** 销售订单 ID (sourceOrderId) */
    private String salesOrderId;

    /** 销售订单编号 (冗余快照, 前端展示用) */
    private String salesOrderNumber;

    /** 产品名称 (如多产品取第一个或 "混合") */
    private String productName;

    /** 产品类型 ID (如多产品则 null) */
    private String productTypeId;

    /** 归属生产计划 ID */
    private String productionPlanId;

    /** 关联批次数量 */
    private int batchCount;

    // ─────────────────────────── 产量维度 ───────────────────────────

    /** 合计好品产量(kg); null = 所有批次 goodQuantity 都为 null */
    private BigDecimal totalGoodQuantityKg;

    /** 标准克重(g/份); null = 未配或多产品不一致, 折盒口径不可算 */
    private BigDecimal gramsPerUnit;

    /** 折算产量(盒) = totalGoodQuantityKg × 1000 / gramsPerUnit; null = 无法折算 */
    private BigDecimal totalGoodQuantityBoxes;

    // ─────────────────────────── 双口径人工成本 ───────────────────────────

    /** 研发预估人工成本合计(元) = Σ(quotedLaborCostPerKg × goodQuantityKg); null = 预估未配 */
    @PriceSensitive
    private BigDecimal totalQuotedLaborCost;

    /** 实际人工成本合计(元) = Σ(batch.laborCost); null = 所有批次报工均无人工数据 */
    @PriceSensitive
    private BigDecimal totalActualLaborCost;

    /** 人工成本偏差(元) = actual - quoted; null = 任一为 null */
    @PriceSensitive
    private BigDecimal laborCostVarianceAbsolute;

    /**
     * 人工成本偏差率 = (actual - quoted) / quoted × 100%; null = quoted 为 null/零.
     * <p>正数 = 超预算; 负数 = 低于预算. 相对指标, 不脱敏。</p>
     */
    private BigDecimal varianceRate;

    /**
     * 偏差状态: WARNING (|variance| ≥ 10%) / CRITICAL (|variance| ≥ 20%) / OK / null.
     * 不脱敏 — 状态标签可见。
     */
    private String varianceStatus;

    // ─────────────────────────── 批次明细 ───────────────────────────

    /** 批次级明细列表 (展开用) */
    private List<LaborEfficiencyCompareDTO> batches;
}
