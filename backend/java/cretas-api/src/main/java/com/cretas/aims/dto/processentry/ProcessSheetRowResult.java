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

    /**
     * 2B.2 多产出: 本次报工分解出的各产出批次明细 (单产出时为 null)。FE 重载过程单会拿到 N 行,
     * 此处仅为即时反馈。顶层 batchId/batchNumber 取首产出为代表。
     */
    private List<OutputResult> outputs;

    /** 单个产出的物化结果 (多产出分解)。 */
    @Data
    public static class OutputResult {
        private String clientRowId;
        private String productTypeId;
        private Long batchId;
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        /** 该产出行物化成本 (existing 机制副产, 不涉工序成本分摊配置; 诚实 null)。 */
        private BigDecimal rowTotalCost;
    }
}
