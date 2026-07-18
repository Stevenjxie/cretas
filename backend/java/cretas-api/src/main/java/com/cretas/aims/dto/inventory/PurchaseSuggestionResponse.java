package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 从销售订单生成采购建议明细的响应 DTO.
 *
 * <p>客户原话 (t2b 行1867-1902 [61:17-62:02]):
 * "做个弹窗…我直接点开始采购…不然新增有点麻烦，全部手写没意义"。
 *
 * <p>每行对应一种原辅料/包材，包含产品来源信息、需要数量（BOM 展开）、
 * 当前库存以及净需求（需要量 - 库存），帮助采购负责人（Friday）快速判断
 * 实际需要采购多少，无需手写。
 *
 * @author Cretas Team
 * @since 2026-06-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSuggestionResponse {

    /** 销售订单 ID */
    private String salesOrderId;

    /** 销售订单号 (如 SO-20260611-001) */
    private String salesOrderNumber;

    /** 客户名称 */
    private String customerName;

    /** 是否有 BOM 配置 (false = 没有 BOM，明细来自 SO 行估算) */
    private boolean hasBom;

    /** 采购建议行列表 */
    private List<SuggestionItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestionItem {

        /** 原料类型 ID */
        private String materialTypeId;

        /** 原料名称 */
        private String materialName;

        /** 物料分类: RAW / AUXILIARY / PACKAGING */
        private String materialCategory;

        /** 来源产品名 (哪个 SO 行展开出此原料) */
        private String sourceProductName;

        /** 需要数量 (BOM 展开后，考虑出成率的实际用量 × SO 数量) */
        private BigDecimal requiredQuantity;

        /** 计量单位 */
        private String unit;

        /** Canonical quantity unit; explicit replacement for the legacy unit alias. */
        private String quantityUnit;

        /** 当前可用库存 (来自 MaterialBatch 汇总) */
        private BigDecimal currentStock;

        /**
         * 净需求 = max(requiredQuantity - currentStock, 0).
         * 这是建议采购数量。0 表示库存充足无需采购。
         */
        private BigDecimal netRequired;

        /** 是否库存充足 (netRequired == 0) */
        private boolean stockSufficient;

        /** 参考单价 (来自 BomRecipeItem.unitPrice，null = 未配置) */
        private BigDecimal referenceUnitPrice;

        /** Canonical denominator unit of referenceUnitPrice, e.g. kg. */
        private String referencePriceUnit;
    }
}
