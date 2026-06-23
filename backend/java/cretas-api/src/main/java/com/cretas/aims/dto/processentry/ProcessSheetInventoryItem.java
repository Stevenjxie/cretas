package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SP-F Task 2.1: 逐工序电子表格 WIP 在制品库存视图 (单行, 对应一道工序的一个批次)。
 *
 * <p>produced  = WIP MaterialBatch.receiptQuantity (本道产出)</p>
 * <p>used       = Σ 下游 MaterialConsumption.quantity (消耗 WIP 的下游工序)</p>
 * <p>remaining  = produced - used (当前剩余)</p>
 * <p>status     = remaining &le; 0 ? "DEPLETED" : "ACTIVE"</p>
 */
@Data
@AllArgsConstructor
public class ProcessSheetInventoryItem {

    /** WIP 批次号 (batchNumber) */
    private String batchNumber;

    /** 本道产出量 (WIP MaterialBatch.receiptQuantity) */
    private BigDecimal produced;

    /** 下游消耗合计 (Σ MaterialConsumption.quantity, factory-scoped 🔒) */
    private BigDecimal used;

    /** 剩余量 = produced - used */
    private BigDecimal remaining;

    /** "ACTIVE" (remaining > 0) | "DEPLETED" (remaining <= 0) */
    private String status;

    /** WIP 批次单价 (MaterialBatch.unitPrice) */
    private BigDecimal unitPrice;
}
