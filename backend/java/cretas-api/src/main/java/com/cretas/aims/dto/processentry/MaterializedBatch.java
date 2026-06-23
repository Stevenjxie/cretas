package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * {@code materializeBatch} 返回值 (SP-F Task 1.3)。
 *
 * <p>{@code wipMaterialBatchId} 为 null 当且仅当 批次 finished 或 无产出(outputQuantity ≤ 0)
 * —— 即未物化 WIP MaterialBatch。caller 用它回填 {@code wipMbIdByKey} 给下游混锅。
 */
@Data
@AllArgsConstructor
public class MaterializedBatch {
    /** 新建 ProductionBatch.id。 */
    private Long productionBatchId;
    /** 新建 ProductionBatch.batchNumber (可能由系统生成)。 */
    private String batchNumber;
    /** WIP 产出 MaterialBatch.id; finished 或无产出时为 null。 */
    private String wipMaterialBatchId;
    /** 该批总成本 = Σ edge.totalCost + seasoningCost + laborCost。 */
    private BigDecimal rowTotalCost;
    /** 写入的 MaterialConsumption 行数 (供 caller 累加统计)。 */
    private int consumptionsWritten;
}
