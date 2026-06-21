package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 成本汇总页一行 = 一张订单 (对标客户 M67 Excel「汇总页」按订单/按天多行视图)。
 * 复用 OrderCostBreakdownService.compute (成本) + YieldReportService.getOrderYieldSummary (整批出成率)。
 * 价格按 procurement:price:view 脱敏 (金额字段 masked 时 null)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCostSummaryRowDTO {
    private String orderId;
    private String orderNumber;
    private LocalDate orderDate;
    private Integer boxCount;
    /** 整批出成率 0-1; 单位不可比时 null。 */
    private BigDecimal overallYieldRate;
    // ---- 金额 (masked 时 null) ----
    private BigDecimal rawMaterialCost;
    private BigDecimal laborCost;
    private BigDecimal seasoningCost;
    private BigDecimal packagingCost;
    private BigDecimal totalCost;
    private BigDecimal perBoxCost;
}
