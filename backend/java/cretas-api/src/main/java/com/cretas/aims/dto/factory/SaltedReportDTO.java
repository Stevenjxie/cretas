package com.cretas.aims.dto.factory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 盐化仓报表 DTO (SP7 F11).
 *
 * <p>报表口径独立，不混入正常销售报表。
 * 期中出库盘账勾稽: totalQuantity 对 + totalAmount (单价×量) 对 = 账平。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaltedReportDTO {

    private String factoryId;
    private String warehouseId;
    private String warehouseName;
    private String startDate;
    private String endDate;

    /** 期间内盐化总出量 */
    private BigDecimal totalQuantity;

    /** 期间内盐化总金额 (unitPrice×quantity 汇总; 含 null unitPrice 行则为部分合计) */
    @PriceSensitive
    private BigDecimal totalAmount;

    /** 扣量记录行 */
    private List<LineItem> lines;

    /** 行摘要计数 */
    private int lineCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineItem {
        private String id;
        private String orderNumber;
        private String deductionDate;
        private BigDecimal quantity;
        private String quantityUnit;
        @PriceSensitive
        private BigDecimal unitPrice;
        @PriceSensitive
        private BigDecimal totalAmount;
        private String customerName;
        private String materialBatchId;
        /** 原料批次号 (enriched) */
        private String batchNumber;
        /** 原料名称 (enriched) */
        private String materialName;
        private String remarks;
    }
}
