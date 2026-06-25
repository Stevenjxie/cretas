package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成品出厂核算 — 已完工批次摘要 (供前端下拉选择).
 *
 * <p>返回字段不含成本金额 (需 finance:read 权限), 仅列出批次身份信息.
 * settled = true 表示已结转成本 (totalCost 非空且 > 0).</p>
 *
 * @see com.cretas.aims.controller.ProductionBatchCostController#listFinishedBatches
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinishedBatchSummaryDTO {

    /** 批次号 (如 PB-20260622-XXXXX), 唯一标识 */
    private String batchNumber;

    /** 关联销售订单 ID (来自 ProductionPlan.sourceOrderId; 存货生产时为 null) */
    private String orderId;

    /** 品名 */
    private String productName;

    /** 计划数量 */
    private BigDecimal plannedQty;

    /** 实际产量 */
    private BigDecimal actualQty;

    /** 数量单位 */
    private String unit;

    /** 完工时间 (ProductionBatch.endTime) */
    private LocalDateTime completedAt;

    /**
     * 是否已结转成本.
     * true = totalCost 已被计算写入 (非 null 且 > 0).
     */
    private boolean settled;
}
