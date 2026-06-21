package com.cretas.aims.dto.bom;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * BOM 成本汇总 DTO
 * 包含产品的完整成本计算结果
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomCostSummaryDTO {

    /**
     * 产品类型ID
     */
    private String productTypeId;

    /**
     * 产品名称
     */
    private String productName;

    // ============ 原辅料成本 ============

    /**
     * 原辅料成本明细
     */
    private List<MaterialCostItem> materialCosts;

    /**
     * 原辅料成本合计
     */
    @PriceSensitive
    private BigDecimal materialCostTotal;

    // ============ 人工成本 ============

    /**
     * 人工成本明细
     */
    private List<LaborCostItem> laborCosts;

    /**
     * 人工成本合计
     */
    @PriceSensitive
    private BigDecimal laborCostTotal;

    // ============ 均摊费用 ============

    /**
     * 均摊费用明细
     */
    private List<OverheadCostItem> overheadCosts;

    /**
     * 均摊费用合计
     */
    @PriceSensitive
    private BigDecimal overheadCostTotal;

    // ============ 总成本 ============

    /**
     * 总成本 = 原辅料成本 + 人工成本 + 均摊费用
     */
    @PriceSensitive
    private BigDecimal totalCost;

    private String costCaliber;

    private String caliberHint;

    // ============ B-BUG-1: 缺价完整性标记 (2026-06-21 transcript-e2e R1) ============

    /**
     * 是否存在缺单价的原辅料行。
     *
     * <p>背景: 缺单价的行无法计入成本 (单价未知)。之前静默当 ¥0 并入 {@code materialCostTotal},
     * 导致总成本被低估却"看起来完整" (违反"禁止降级"铁律)。现显式标记, 让前端展示
     * "成本不完整 / 缺 N 行价格", 而非假装完整。
     *
     * <p>{@code true} 时 {@code materialCostTotal}/{@code totalCost} 仅含已知价格行的累加,
     * 不代表完整成本; 前端应据此提示用户补全单价后重新计算。
     */
    @Builder.Default
    private boolean hasMissingPrice = false;

    /**
     * 缺单价的原辅料行数 (hasMissingPrice=true 时 &gt; 0)。
     */
    @Builder.Default
    private int missingPriceCount = 0;

    /**
     * 缺单价的原辅料名称列表, 供前端直接展示"缺价: XXX, YYY"。
     */
    private List<String> missingPriceMaterials;

    /**
     * 计算时间戳
     */
    private String calculatedAt;

    /**
     * 原辅料成本项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialCostItem {
        /**
         * 原辅料名称
         */
        private String materialName;

        /**
         * 原辅料类型ID
         */
        private String materialTypeId;

        /**
         * 成品含量/标准用量
         */
        private BigDecimal standardQuantity;

        /**
         * 出成率 (%)
         */
        private BigDecimal yieldRate;

        /**
         * 实际用量 (考虑出成率后)
         */
        private BigDecimal actualQuantity;

        /**
         * 计量单位
         */
        private String unit;

        /**
         * 单价
         */
        @PriceSensitive
        private BigDecimal unitPrice;

        private String unitPriceCaliber;

        private String caliberHint;

        /**
         * 税率 (%)
         */
        private BigDecimal taxRate;

        /**
         * 小计 = 实际用量 * 单价
         */
        @PriceSensitive
        private BigDecimal subtotal;

        /**
         * B-BUG-1: 此行是否缺单价。{@code true} 时 {@code subtotal} 不可信 (单价未知,
         * 无法计入成本)。前端应高亮该行并提示"缺单价"。
         */
        @Builder.Default
        private boolean missingPrice = false;
    }

    /**
     * 人工成本项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaborCostItem {
        /**
         * 工序名称
         */
        private String processName;

        /**
         * 工序类别
         */
        private String processCategory;

        /**
         * 单价
         */
        @PriceSensitive
        private BigDecimal unitPrice;

        /**
         * 计价单位
         */
        private String priceUnit;

        /**
         * 操作量/工作量
         */
        private BigDecimal quantity;

        /**
         * 小计 = 单价 * 操作量
         */
        @PriceSensitive
        private BigDecimal subtotal;
    }

    /**
     * 均摊费用项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverheadCostItem {
        /**
         * 费用名称
         */
        private String name;

        /**
         * 费用类别
         */
        private String category;

        /**
         * 单价/费率
         */
        @PriceSensitive
        private BigDecimal unitPrice;

        /**
         * 计价单位
         */
        private String priceUnit;

        /**
         * 分摊比例/数量
         */
        private BigDecimal allocationRate;

        /**
         * 小计 = 单价 * 分摊比例
         */
        @PriceSensitive
        private BigDecimal subtotal;
    }
}
