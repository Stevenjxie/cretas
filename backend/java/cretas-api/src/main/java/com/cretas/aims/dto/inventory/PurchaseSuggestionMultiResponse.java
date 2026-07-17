package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多销售订单合并生成采购建议的响应 DTO.
 *
 * <p>客户原话 (t2b 行3650): 多个销售订单逐个用"加号"追加合并成一张采购单 —
 * "我可能要把好几个订单的料合在一起一次买"。
 *
 * <p>与单 SO 的 {@link PurchaseSuggestionResponse} 不同之处:
 * <ul>
 *   <li>顶层携带 {@code salesOrderIds} / {@code salesOrderNumbers} 列表 (合并自哪几张 SO)。</li>
 *   <li>每个 {@link SuggestionItem} 标注 {@code sourceSalesOrderNumbers} — 该原料的需求合并自哪些 SO。</li>
 *   <li>{@code soSummaries} 逐 SO 列出是否有 BOM (诚实暴露"某张 SO 没配方"，不静默)。</li>
 * </ul>
 *
 * <p>聚合口径: 跨所有 SO 按 materialTypeId 累加需求量，统一扣减一次当前库存
 * (库存只有一份，合并后净需求 = Σrequired − 当前库存)，避免每 SO 各扣一次库存导致重复计算。
 *
 * @author Cretas Team
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSuggestionMultiResponse {

    /** 参与合并的销售订单 ID 列表 (与 salesOrderNumbers 同序) */
    private List<String> salesOrderIds;

    /** 参与合并的销售订单号列表 (如 [SO-20260611-001, SO-20260612-003]) */
    private List<String> salesOrderNumbers;

    /**
     * 客户名称列表 (去重，与 salesOrderIds 顺序无关).
     * 采购合并不要求多 SO 同客户; 列出全部客户供采购负责人核对。
     */
    private List<String> customerNames;

    /** 是否至少有一张 SO 展开出 BOM 明细 (false = 全部无配置，items 为空) */
    private boolean hasBom;

    /** 合并后的采购建议行列表 (按 materialTypeId 跨 SO 聚合) */
    private List<SuggestionItem> items;

    /** 逐 SO 的展开摘要 (诚实暴露每张 SO 是否有 BOM) */
    private List<SoSummary> soSummaries;

    /**
     * 单张 SO 的展开摘要.
     * 让采购负责人看到"某张 SO 没配方所以没贡献料" — 不静默跳过。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SoSummary {
        /** 销售订单 ID */
        private String salesOrderId;
        /** 销售订单号 */
        private String salesOrderNumber;
        /** 客户名称 */
        private String customerName;
        /** 该 SO 是否展开出 BOM 明细 (false = 该 SO 无配方或无行项目，未贡献需求) */
        private boolean hasBom;
    }

    /**
     * 合并后的采购建议行.
     * 字段与单 SO 的 {@link PurchaseSuggestionResponse.SuggestionItem} 对齐,
     * 额外携带 {@code sourceSalesOrderNumbers} 标注该原料合并自哪些 SO。
     */
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

        /** 来源产品名 (第一次展开出此原料时的产品名，仅供参考) */
        private String sourceProductName;

        /** 该原料需求合并自哪些销售订单号 (去重，按首次出现顺序) */
        private List<String> sourceSalesOrderNumbers;

        /** 需要数量 (跨所有 SO 的 BOM 展开累加，考虑出成率) */
        private BigDecimal requiredQuantity;

        /** 计量单位 */
        private String unit;

        /** Canonical quantity unit; explicit replacement for the legacy unit alias. */
        private String quantityUnit;

        /** 当前可用库存 (来自 MaterialBatch 汇总，全 SO 共享一份库存) */
        private BigDecimal currentStock;

        /**
         * 净需求 = max(requiredQuantity − currentStock, 0).
         * 这是建议采购数量。0 表示库存充足无需采购。
         */
        private BigDecimal netRequired;

        /** 是否库存充足 (netRequired == 0) */
        private boolean stockSufficient;

        /** 参考单价 (来自 BOM.unitPrice，null = 未配置) */
        private BigDecimal referenceUnitPrice;

        /** Canonical denominator unit of referenceUnitPrice, e.g. kg. */
        private String referencePriceUnit;
    }
}
