package com.cretas.aims.dto.processentry;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** DRAFT / SUBMITTED；旧 saveRow 路径未显式提交时保持原有语义。 */
    private String submissionStatus;

    /** 正式提交时由生产库自动生成的批次分摊明细。 */
    private List<InputAllocation> inputAllocations;

    @Data
    public static class InputAllocation {
        private String materialTypeId;
        private String materialBatchId;
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        private Integer allocationOrder;
    }

    /**
     * 2B.2 多产出: 本次报工分解出的各产出批次明细 (单产出时为 null)。FE 重载过程单会拿到 N 行,
     * 此处仅为即时反馈。顶层 batchId/batchNumber 取首产出为代表。
     */
    private List<OutputResult> outputs;

    /** 单个产出的物化结果 (多产出分解)。 */
    @Data
    public static class OutputResult {
        private String clientRowId;
        /** 端口身份: 对应 workflow 产出端口 id。 */
        private String workflowPortId;
        /** 端口身份: 对应 workflow 产出物料 Cell (节点) id。 */
        private String materialNodeId;
        /** 产出 SKU (= productTypeId)。 */
        private String productTypeId;
        /** 生成的产出批次 id (generatedBatchId)。 */
        private Long batchId;
        /** 生成的产出批次号。 */
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        /** 该产出行物化成本 (existing 机制副产, Workflow 不干预成本字段)。 */
        private BigDecimal rowTotalCost;
        /** 该产出相对报工组总投入的出成率。 */
        private BigDecimal yieldRate;
        /** 报工组生产日期。 */
        private LocalDate processDate;
        /** 该产出的工时时段。 */
        private List<LaborSegment> laborSegments;
        /** Σ(时段小时×人数)。 */
        private BigDecimal totalLaborHours;
        /** 该产出的副产及回收单价。 */
        private List<ProcessChainEntryRequest.Byproduct> byproducts;
        /** 成本分摊百分比。 */
        private BigDecimal costAllocationRatio;
        /** QUANTITY / MASS / EXPLICIT_RATIO。 */
        private String costAllocationBasis;
        /** 分摊后的该产出总成本；成本未知时为 null。 */
        private BigDecimal allocatedCost;
    }
}
