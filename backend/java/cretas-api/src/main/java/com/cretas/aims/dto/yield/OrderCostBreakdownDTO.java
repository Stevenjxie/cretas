package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单级单盒成本拆分 — 单一权威服务输出 (T1 批次谱系遍历 + T2 上游成本回溯).
 *
 * <p>原料成本 = 沿 {@link com.cretas.aims.entity.MaterialConsumption} 边递归回溯到上游各批次的
 * 实测成本之和 (混批按实测投料量×各自单价精确归集, 非按重量糊平均); 人工/调料/包装来自该批
 * {@code ProductionReport} 逐道报工。单盒成本 = 总成本 ÷ 盒数。
 *
 * <p>价格脱敏: 无 {@code procurement:price:view} 权限时所有金额字段返回 null (masked=true),
 * 仅保留投料量/占比结构 (与 MaterialConsumptionController 一致的红线策略)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCostBreakdownDTO {

    private String orderId;
    /** 产出盒数 (Σ 成品批次 quantity)。 */
    private Integer boxCount;

    // ---- 金额 (masked 时为 null) ----
    /** 原料成本 = 上游各批 traced 成本之和 (混批闭环)。 */
    private BigDecimal rawMaterialCost;
    private BigDecimal laborCost;
    private BigDecimal seasoningCost;
    private BigDecimal packagingCost;
    private BigDecimal totalCost;
    private BigDecimal perBoxCost;

    // ---- 副产回收 (肥油/料头等可变现副产物; masked 时为 null) ----
    /** 副产回收价值 = Σ 各道报工副产物(quantity×单价); 无单价的副产物按 0 (诚实, 不臆造). */
    private BigDecimal byproductCredit;
    /** 净成本 = 总成本 − 副产回收 (副产物变现冲减主产品成本)。 */
    private BigDecimal netTotalCost;
    /** 单盒净成本 = 净成本 ÷ 盒数。 */
    private BigDecimal netPerBoxCost;
    /** 副产物明细 (跨批次跨道按 名称+单位 归集)。 */
    private List<ByproductLine> byproducts;

    // ---- 留样/料头 (AUDIT-006) ----
    /** 留样数 (产出但不可售; Σ各道, 通常仅末道)。物理量, 不脱敏。 */
    private Integer sampleRetainCount;
    /** 损耗/料头量 (仅展示; 已体现在出成率, 不二次扣成本; 无则 null)。物理量, 不脱敏。 */
    private BigDecimal wasteQuantity;
    /** 可售盒数 = 产出盒数 − 留样数。物理量, 不脱敏。 */
    private Integer sellableBoxCount;
    /** 单盒可售成本 = 净成本 ÷ 可售盒数 (留样成本由售出盒承担; masked 时 null)。 */
    private BigDecimal sellablePerBoxCost;

    /** 包装明细 (AUDIT-002: 膜/气体/标签/其他, 按名称归集; 各项 cost 之和=packagingCost; masked 时 cost null)。 */
    private List<PackagingItem> packagingDetail;

    /** 辅料按锅分摊 (AUDIT-004: 一锅辅料被多批共用时按产出量分摊到本批; masked 时金额 null)。 */
    private List<AuxiliaryAllocation> auxiliaryAllocations;

    /** 价格是否被脱敏 (无价格查看权限)。 */
    private boolean priceMasked;
    /** 遍历是否完整 (无该订单批次/无消耗边时 false, 诚实空)。 */
    private boolean hasData;

    /** 上游来源明细 (混批各批次)。 */
    private List<SourceCost> sources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceCost {
        private String batchId;
        private String batchName;
        private BigDecimal quantity;
        private String unit;
        /** masked 时 null。 */
        private BigDecimal unitPrice;
        /** masked 时 null (= 该上游链 traced 累计成本)。 */
        private BigDecimal cost;
        /** 按投料量占比 % (scale 1)。 */
        private BigDecimal weightSharePct;
        /** 按成本占比 % (scale 1); masked 时 null。异质单价下 ≠ weightSharePct。 */
        private BigDecimal costSharePct;
        /** 谱系深度 (1=直接上游; >1=多级回溯)。 */
        private Integer depth;
    }

    /** 副产物归集行 (名称+单位 维度跨批次汇总)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ByproductLine {
        private String name;
        private BigDecimal quantity;
        private String unit;
        /** 单价 (元/单位); 报工未录则 null; masked 时 null。 */
        private BigDecimal unitPrice;
        /** 变现价值 = quantity × unitPrice (报工未录单价则 null/0); masked 时 null。 */
        private BigDecimal value;
    }

    /** 包装明细项 (AUDIT-002: 膜/气体/标签/其他, 按名称跨批次归集)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagingItem {
        private String name;
        /** 该包材项成本 (元); masked 时 null。 */
        private BigDecimal cost;
    }

    /** 辅料按锅分摊 (AUDIT-004)。本批 share = potTotalCost × batchOutput ÷ potTotalOutput。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuxiliaryAllocation {
        private String potNo;
        /** 分摊方式 BY_OUTPUT/FIXED_RATIO。 */
        private String method;
        /** 该锅辅料总成本 (元); masked 时 null。 */
        private BigDecimal potTotalCost;
        /** 该锅总产出量 (用于分摊基数); 物理量, 不脱敏。 */
        private BigDecimal potTotalOutput;
        /** 本批本道产出量; 物理量, 不脱敏。 */
        private BigDecimal batchOutput;
        /** 本批分摊到的辅料成本 (元); masked 时 null。 */
        private BigDecimal batchShare;
        /** 本批分摊占比 % (scale 1); 物理量, 不脱敏。 */
        private BigDecimal batchSharePct;
    }
}
