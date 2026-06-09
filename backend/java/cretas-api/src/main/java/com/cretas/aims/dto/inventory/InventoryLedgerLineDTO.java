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
    private BigDecimal adjustQty;               // 盘盈(正) / 盘损(负)
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

    @PriceSensitive
    private BigDecimal closingAmount;

    /** 移动均价 (period 末) */
    @PriceSensitive
    private BigDecimal movingAvgUnitPrice;
}
