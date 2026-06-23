package com.cretas.aims.dto.processentry;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP-F 逐工序电子表格 — 单行增量录入响应 (spec §4.3)。
 */
@Data
public class ProcessSheetRowResult {

    private String clientRowId;

    /** 物化的 ProductionBatch.id (未物化时 null)。 */
    private Long batchId;

    /** 系统生成/确认的批次号 (下游下拉用它; 未物化时 null)。 */
    private String batchNumber;

    /** outputQty/inputQty ×100 (inputQty<=0 时 null)。 */
    private BigDecimal yieldRate;

    /** 该行物化成本 (kg 级)。 */
    private BigDecimal rowTotalCost;

    /** rowTotalCost / outputQty (= WIP 批单价; 未物化时 null)。 */
    private BigDecimal unitPrice;

    /** true=覆盖已有行 (update-in-place); false=新建。 */
    private boolean updated;

    /** false = outputQty<=0, 未生成 WIP 批。 */
    private boolean materialized;

    /** 调料配方缺失 / 超量软预警 / labor rate fallback 等。 */
    private List<String> warnings;
}
