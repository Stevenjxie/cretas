package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * SP-F Task 2.1 / F006 双出成率: 逐工序电子表格 WIP 在制品库存视图 (单行, 对应一道工序的一个批次)。
 *
 * <p>produced  = WIP MaterialBatch.receiptQuantity (本道产出)</p>
 * <p>used       = Σ 下游 MaterialConsumption.quantity (消耗 WIP 的下游工序)</p>
 * <p>remaining  = produced - used (当前剩余)</p>
 * <p>status     = remaining &le; 0 ? "DEPLETED" : "ACTIVE"</p>
 * <p>stepYieldRate     = 本道产出 / 本道投入 × 100 (对上工序; null = 无投入数据或首道且无报工)</p>
 * <p>cumulativeYieldRate = 本道产出(折算首道单位) / 首道投入 × 100 (对原料; null = 跨单位无折算系数或首道投入≤0)</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSheetInventoryItem {

    /** WIP 批次号 (batchNumber / intermediateBatchNo) */
    private String batchNumber;

    /**
     * ② 批次下拉补品名: 产品类型名称 (从 row payload 的 productTypeId 反查; getInventory 填充,
     * getInventoryYieldCard 兼容留 null)。前端投料下拉标签用 {@code 品名 | 批号 | ...}。
     */
    private String productTypeName;

    /**
     * ② 批次下拉补生产日期: 该 WIP 批次生产日期 (取自 WIP MaterialBatch.productionDate; getInventory 填充)。
     */
    private LocalDate productionDate;

    /** 本道产出量 (WIP producedQuantity) */
    private BigDecimal produced;

    /** 下游消耗合计 (Σ consumedQuantity, factory-scoped) */
    private BigDecimal used;

    /** 剩余量 = produced - used */
    private BigDecimal remaining;

    /** "ACTIVE" (remaining > 0) | "DEPLETED" (remaining &le; 0) | SemiFinishedInventory.status */
    private String status;

    /** WIP 批次单价 (unitCost, 单位成本) */
    private BigDecimal unitPrice;

    private BigDecimal rowTotalCost;

    private BigDecimal inputQuantity;

    private String sourceBatchNumber;

    private BigDecimal feedQuantity;

    private BigDecimal sourceProducedQuantity;

    private BigDecimal sourceConsumedRatio;

    private BigDecimal inheritedRawEquivalentQuantity;

    private BigDecimal inheritedCost;

    private BigDecimal addedCost;

    private List<SourceBreakdown> sourceBreakdowns;

    // ── 双出成率扩展字段 (getInventoryYieldCard 填充; getInventory 兼容留 null) ──

    /** 链内工序序 (SemiFinishedInventory.processOrder) */
    private Integer processOrder;

    /** 工序名称 (从 WorkProcessTask → WorkProcess 回填; null = 查不到) */
    private String processName;

    /** 本道产出单位 (SemiFinishedInventory.unit) */
    private String unit;

    /**
     * 对上工序出成率 (%) = 本道产出 / 本道投入 × 100, scale 4 HALF_UP.
     * null 条件: 本道无 ProductionReport 投入数据 / 投入 = 0 / 首道且首步原料投入未录.
     */
    private BigDecimal stepYieldRate;

    /**
     * 对原料累计出成率 (%) = 本道产出(折算首道单位) / 首道原料投入 × 100, scale 4 HALF_UP.
     * null 条件: 跨单位且无 standardGramsPerUnit 折算系数 / 首道投入 &le; 0.
     */
    private BigDecimal cumulativeYieldRate;

    /** 成品重(kg) — 仅末道(finished)行有值; 来自该行 productWeight。 */
    private BigDecimal productWeight;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceBreakdown {
        private String sourceBatchNumber;
        private BigDecimal feedQuantity;
        private BigDecimal sourceProducedQuantity;
        private BigDecimal sourceConsumedRatio;
        private BigDecimal inheritedRawEquivalentQuantity;
        private BigDecimal inheritedCost;
    }
}
