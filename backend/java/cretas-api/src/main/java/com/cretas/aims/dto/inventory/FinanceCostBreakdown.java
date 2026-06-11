package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sprint4-H F-AR-1 销售订单财务成本核算 DTO.
 *
 * <p>财务审核时辅助决策的成本视图: 拉 BOM 标准成本 + 当前预估成本 +
 * (订单完成后) 实际生产成本, 并自动计算预估利润 vs 实际利润对比.
 *
 * <p>所有金额字段标记 @PriceSensitive — 后端 ResponseAdvice 对无 finance:read /
 * sales:read_write 权限的角色 strip 到 null. UI 应做 null 守卫.
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@link #totalAmount}: 订单总额 (销售方收入)
 *   <li>{@link #bomStandardCost}: BOM 标准成本聚合 — 按 items 的 productId 查询
 *       BomRecipe.totalCost. 产品无 ACTIVE BOM 时 null.
 *   <li>{@link #currentEstimatedCost}: SalesOrder.estimatedCost (财务审核前/后由
 *       财务录入或 BOM 默认值)
 *   <li>{@link #currentEstimatedProfit}: SalesOrder.estimatedProfit
 *       (= totalAmount - currentEstimatedCost)
 *   <li>{@link #actualCost}: 按 SalesOrderItem.costUnitPrice * quantity 聚合 —
 *       订单完成产生实际成本数据后非 null.
 *   <li>{@link #actualProfit}: totalAmount - actualCost (actualCost 为 null 时为 null)
 *   <li>{@link #profitMarginEstimated}: currentEstimatedProfit / totalAmount * 100%
 *   <li>{@link #profitMarginActual}: actualProfit / totalAmount * 100%
 *   <li>{@link #lines}: 行级明细 — 行级 BOM 标准成本 / 实际成本对比
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceCostBreakdown {

    /** 订单总额 (销售方收入) */
    @PriceSensitive
    private BigDecimal totalAmount;

    /** BOM 标准成本聚合 (按 items 的 productId 查询 BomRecipe.totalCost). 产品无 ACTIVE BOM 时为 null. */
    @PriceSensitive
    private BigDecimal bomStandardCost;

    /** SalesOrder.estimatedCost — 财务录入的预估成本 (可能 null). */
    @PriceSensitive
    private BigDecimal currentEstimatedCost;

    /** SalesOrder.estimatedProfit — 预估利润 (totalAmount - currentEstimatedCost). */
    @PriceSensitive
    private BigDecimal currentEstimatedProfit;

    /** 实际成本 (按 SalesOrderItem.costUnitPrice * quantity 聚合, 订单未产生成本数据时 null). */
    @PriceSensitive
    private BigDecimal actualCost;

    /** 实际利润 (totalAmount - actualCost). */
    @PriceSensitive
    private BigDecimal actualProfit;

    /** 预估利润率 (%): currentEstimatedProfit / totalAmount * 100, totalAmount=0 时 null. */
    @PriceSensitive
    private BigDecimal profitMarginEstimated;

    /** 实际利润率 (%): actualProfit / totalAmount * 100, totalAmount=0 时 null. */
    @PriceSensitive
    private BigDecimal profitMarginActual;

    /** 提示信息 — 当 BOM/实际成本缺失时给财务的友好说明. */
    private String dataSourceHint;

    // ========== SP3 三价对比新增字段 ==========

    /** SP3: 订单级成本超支绝对值 (actualCost - bomStandardCost). 两者有一为 null 则 null. */
    @PriceSensitive
    private BigDecimal varianceAbsolute;

    /** SP3: 订单级成本超支百分比 ((actualCost - bomStandardCost) / bomStandardCost * 100).
     * 正数=超支, 负数=节约. 任一为 null 或 bomStandardCost=0 则 null. */
    @PriceSensitive
    private BigDecimal variancePct;

    /** SP3: 实际成本是否低于超支阈值 (true=未超支, false=超支, null=数据不完整).
     * 不标 @PriceSensitive — 状态信息可见. */
    private Boolean belowThreshold;

    /** SP3: 超支告警文案. 未超支时 null. */
    private String alarmMessage;

    // ========== P1 #32 委外加工费独立科目 ==========

    /**
     * 委外加工费 (processingFee) — 独立成本科目.
     *
     * <p>六扇门有委外工序 (部分产品的某道工序外包给第三方加工厂完成).
     * 该字段用于单列委外加工成本, 区别于原料成本 (bomStandardCost) 和内部人工成本.
     *
     * <p><b>数据源现状 (诚实 null)</b>:
     * WorkProcess / WorkProcessTask 实体及 production_batch 数据模型目前
     * 尚无 "isOutsourced" 标记或 "outsourcedProcessingFee" 列.
     * 委外加工费未写入任何生产记录 → 当前恒为 null.
     *
     * <p><b>未来数据源计划</b> (待 WorkProcess 加 is_outsourced + outsourced_fee 列时接入):
     * {@code SUM(wpt.outsourcedFee) WHERE wpt.productionBatchId IN (批次关联订单)}.
     *
     * <p>标记 @PriceSensitive — 同其余成本科目, 非财务角色脱敏为 null.
     */
    @PriceSensitive
    private BigDecimal processingFee;

    /** 行级成本明细. */
    private List<LineCostBreakdown> lines;

    /**
     * 销售订单行级成本明细 (mirror SalesOrderItem + 推导的 BOM 标准行成本).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineCostBreakdown {
        /** SalesOrderItem.productId */
        private String productId;
        /** SalesOrderItem.productName (snapshot 字段) */
        private String productName;
        /** 数量 */
        private BigDecimal quantity;
        /** 销售单价 */
        @PriceSensitive
        private BigDecimal unitPrice;
        /** 销售小计 (qty * unitPrice). */
        @PriceSensitive
        private BigDecimal lineAmount;
        /** BOM 标准单位成本 (来自 BomRecipe.totalCost; 产品无 ACTIVE BOM 时 null). */
        @PriceSensitive
        private BigDecimal bomStandardUnitCost;
        /** BOM 标准行成本 (qty * bomStandardUnitCost). */
        @PriceSensitive
        private BigDecimal bomStandardLineCost;
        /** 实际行成本 (qty * SalesOrderItem.costUnitPrice). */
        @PriceSensitive
        private BigDecimal actualLineCost;

        // ========== SP3 行级三价对比新增字段 ==========

        /** SP3: 行级标准单位成本 (= bomStandardUnitCost 别名, 语义更明确). */
        @PriceSensitive
        private BigDecimal standardCostPerUnit;

        /** SP3: 行级实际单位成本 (= SalesOrderItem.costUnitPrice). */
        @PriceSensitive
        private BigDecimal actualCostPerUnit;

        /** SP3: 行级成本超支百分比 ((actualCostPerUnit - standardCostPerUnit) / standardCostPerUnit * 100).
         * 正=超支, 负=节约, null=数据不完整. */
        @PriceSensitive
        private BigDecimal variancePct;

        /** SP3: 行级实际成本是否低于超支阈值. null=数据不完整. */
        private Boolean belowThreshold;
    }
}
