package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SP11: 进销存台账单物料明细行.
 *
 * <p>金额字段标 {@code @PriceSensitive} — 仓管/操作员只可见数量, 财务/超管可见金额.
 * 红线 R1: openingAmount / closingAmount / movingAvgUnitPrice 必须标注 (≥3 hits).
 *
 * <p>SP11 W8: 盘盈/盘损分列 — {@code adjustQty}(已废弃混合符号字段)拆分为:
 * <ul>
 *   <li>{@code stocktakeProfitQty} — 盘盈数量 (正调整, ≥0)</li>
 *   <li>{@code stocktakeLossQty}   — 盘损数量 (负调整取绝对值, ≥0, 便于金蝶对账)</li>
 * </ul>
 * {@code adjustQty} 保留向后兼容 (= profitQty - lossQty); 新消费方应使用分列字段.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerLineDTO {

    private String materialTypeId;
    private String materialCode;
    private String materialName;
    private String unit;

    // === 数量 (全角色可见) ===
    private BigDecimal openingQty;
    private BigDecimal inboundQty;
    private BigDecimal outboundProductionQty;  // 生产领用
    private BigDecimal outboundSalesQty;        // 销售出货
    private BigDecimal transferInQty;           // 调拨入
    private BigDecimal transferOutQty;          // 调拨出

    /**
     * 盘点净调整数量 = profitQty - lossQty (保留向后兼容; 新代码用分列字段).
     * 正 = 净盈, 负 = 净损.
     */
    private BigDecimal adjustQty;

    /** 盘盈数量 (period 内正调整合计, ≥0). */
    private BigDecimal stocktakeProfitQty;

    /** 盘损数量 (period 内负调整取绝对值, ≥0). */
    private BigDecimal stocktakeLossQty;

    private BigDecimal closingQty;

    // === 金额 (财务角色可见 - @PriceSensitive) ===
    @PriceSensitive
    private BigDecimal openingAmount;

    @PriceSensitive
    private BigDecimal inboundAmount;

    @PriceSensitive
    private BigDecimal outboundAmount;

    @PriceSensitive
    private BigDecimal adjustAmount;

    /** 盘盈金额 (= stocktakeProfitQty × 批次均价, 财务角色可见). */
    @PriceSensitive
    private BigDecimal stocktakeProfitAmount;

    /** 盘损金额 (= stocktakeLossQty × 批次均价, 取绝对值, 财务角色可见). */
    @PriceSensitive
    private BigDecimal stocktakeLossAmount;

    @PriceSensitive
    private BigDecimal closingAmount;

    /** 移动均价 (period 末) */
    @PriceSensitive
    private BigDecimal movingAvgUnitPrice;
}
