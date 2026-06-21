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
}
