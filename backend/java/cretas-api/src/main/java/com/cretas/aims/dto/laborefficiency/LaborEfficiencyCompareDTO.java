package com.cretas.aims.dto.laborefficiency;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP9-M3: 人工双口径对比结果 (单批次).
 *
 * <p>对比维度:</p>
 * <ul>
 *   <li>quotedLaborCostPerKg: 研发预估(元/kg) — 来自 ProductType.quotedLaborCostPerKg</li>
 *   <li>actualLaborCostPerKg: 实际(元/kg) = batchLaborCost / goodQuantityKg — 来自报工汇总</li>
 *   <li>折盒口径: quotedLaborCostPerBox / actualLaborCostPerBox = 元/kg × gramsPerUnit / 1000</li>
 *   <li>varianceRate: (actual - quoted) / quoted × 100%; null = quoted 未填无法计算</li>
 * </ul>
 *
 * <p><b>RBAC 成本脱敏 (六扇门审计 Tier0 #05, 2026-06-11)</b>: 人工成本绝对值
 * (quotedLaborCostPerKg / actualLaborCostPerKg / quotedLaborCostPerBox /
 * actualLaborCostPerBox, 单位 元/kg / 元/盒) 标记 {@link PriceSensitive},
 * 由 {@link com.cretas.aims.security.PriceFieldResponseAdvice} 对无
 * {@code procurement:price:view} 权限角色 (warehouse_manager / warehouse_worker /
 * quality_inspector / operator / viewer 等运营角色) strip 为 null。
 * 偏差率 % (varianceRate) / 偏差状态标签 (varianceStatus) / 工时人次 / 标准克重
 * 是相对指标/工作量/物理规格, 不脱敏。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborEfficiencyCompareDTO {

    /** 批次 ID */
    private Long batchId;

    /** 批次号 */
    private String batchNumber;

    /** 产品名称 */
    private String productName;

    /** 产品类型 ID */
    private String productTypeId;

    /** 标准克重(g/份); null = 未配, 折盒口径不可算 (物理规格, 非金额, 不脱敏) */
    private BigDecimal gramsPerUnit;

    /** 研发预估人工成本(元/kg); null = 未填 */
    @PriceSensitive
    private BigDecimal quotedLaborCostPerKg;

    /** 实际人工成本(元/kg); null = 报工未采集人工成本 */
    @PriceSensitive
    private BigDecimal actualLaborCostPerKg;

    /** 研发预估人工成本折盒(元/盒) = quotedLaborCostPerKg × gramsPerUnit / 1000; null = gramsPerUnit 未配 */
    @PriceSensitive
    private BigDecimal quotedLaborCostPerBox;

    /** 实际人工成本折盒(元/盒) = actualLaborCostPerKg × gramsPerUnit / 1000; null = gramsPerUnit 未配 */
    @PriceSensitive
    private BigDecimal actualLaborCostPerBox;

    /**
     * 偏差率 = (actual - quoted) / quoted × 100%; null = quoted 未配无法计算.
     * <p>正数 = 超预算; 负数 = 低于预算.</p>
     */
    private BigDecimal varianceRate;

    /**
     * 偏差状态: WARNING (|variance| ≥ 10%) / CRITICAL (|variance| ≥ 20%) / OK / null.
     */
    private String varianceStatus;

    /** 逐工序人工成本分解 (来自 BatchYield.steps; null = 未采集) */
    private List<LaborVarianceItemDTO> stepDetails;
}
