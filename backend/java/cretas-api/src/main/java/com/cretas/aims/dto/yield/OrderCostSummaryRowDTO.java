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
    /**
     * 成品名称 — 取 {@code product_types.name} 实时值, 而不是批次快照 {@code production_batches.product_name}。
     * 线上实测两者会漂移 (F006 SO-20260716-0002 快照写"牛排", 主数据是"羊排"), 与 T159-B「优先实时名」一致。
     * 一张订单跨多个产品时为 null (前端显示"—"), 不挑一个冒充全部。
     */
    private String productName;
    /**
     * 成品 SKU 编码 — 取 {@code product_types.code} (如 CPF0060020)。
     * ⚠️ 不是 {@code productTypeId}: 后者是 UUID, 直接展示对操作员毫无意义。
     */
    private String skuCode;
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
