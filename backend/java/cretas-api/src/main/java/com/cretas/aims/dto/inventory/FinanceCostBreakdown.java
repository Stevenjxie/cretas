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

    /**
     * 订单总额 — 未税净额 (SalesOrder.totalAmount).
     *
     * <p>SP3 口径注记: total_amount 按全系统未税存储约定存未税净额。
     * 毛利口径 = 未税收入 − 未税成本 (actualCost), 口径自洽。
     * 含税总额 = totalAmount + taxAmount (SP4 新增字段)。
     */
    @PriceSensitive
    private BigDecimal totalAmount;

    /**
     * 订单税额 — SP4 补齐: SalesOrder.taxAmount (= Σ item 税额).
     *
     * <p>含税总额 = totalAmount + taxAmount。
     * SP11 凭证使用此字段做价税分离 (贷 2221.01 销项税)。
     * 无税率订单此字段为 0。
     */
    @PriceSensitive
    private BigDecimal taxAmount;

    /**
     * 含税收入总额 — 派生值, 不落库 (= totalAmount + taxAmount).
     *
     * <p>SP4 双值暴露: UI/导出层需要含税总额时直接读此字段, 无需客户端自行加法。
     * 对应 SalesOrder.getTotalWithTax() 语义 (含运费+税但不含折扣调整)。
     * 此处为含税收入净额 = totalAmount + taxAmount (不含运费等 SO 头额外费用)。
     * null 当 totalAmount 为 null。
     */
    @PriceSensitive
    private BigDecimal totalAmountWithTax;

    /** BOM 标准成本聚合 (按 items 的 productId 查询 BomRecipe.totalCost). 产品无 ACTIVE BOM 时为 null. */
    @PriceSensitive
    private BigDecimal bomStandardCost;

    /** SalesOrder.estimatedCost — 财务录入的预估成本 (未税口径, 可能 null). */
    @PriceSensitive
    private BigDecimal currentEstimatedCost;

    /** SalesOrder.estimatedProfit — 预估利润 (totalAmount[未税] - currentEstimatedCost[未税]). */
    @PriceSensitive
    private BigDecimal currentEstimatedProfit;

    /**
     * 实际成本 (未税) — Σ (SalesOrderItem.costUnitPrice[未税] × quantity).
     *
     * <p>SP3 口径注记: costUnitPrice 已统一为未税净价 (SP3 修正)。
     * 此聚合与 totalAmount[未税] 口径一致 → actualProfit = 未税收入 − 未税成本 = 真实毛利。
     * 订单未产生成本数据 (任一行 costUnitPrice=null/0) 时为 null。
     */
    @PriceSensitive
    private BigDecimal actualCost;

    /**
     * 实际利润 (未税口径) — totalAmount[未税] − actualCost[未税].
     *
     * <p>SP3 口径注记: 收入未税、成本未税, 毛利口径自洽。
     * 与金蝶 6001 主营业务收入 (未税净额) 口径一致。
     */
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

    // ========== SP12 实际成本拆分 (材料/人工/制费三分 + 逐料明细) ==========

    /**
     * 实际成本拆分 — 把 {@link #actualCost} (单一总额) 拆解成 材料 / 人工 / 制费 三分,
     * 材料部分进一步拆到逐原料/辅料/包材 (名称 + 用量 + 移动均价单价 + 金额).
     *
     * <p>客户原话 (六扇门 2026-06-09 [12:36]): "财务根据前面领料的批次去核算最终的实际成本"。
     * 财务需要看到 "实际成本 = Σ各原料 + 人工 + 制费" 的拆分, 而不只是一个总数。
     *
     * <p><b>数据源</b> (真实生产数据, 非 BOM 标准):
     * <ul>
     *   <li>材料明细: {@code MaterialConsumption} 领料消耗记录 (订单 → ProductionPlan
     *       → ProductionBatch → MaterialConsumption), 按 materialTypeId 聚合实际用量与金额。
     *   <li>人工: {@code ProductionBatch.laborCost} (报工 BatchWorkSession 工时×时薪 rollup)。
     *   <li>制费: {@code ProductionBatch.equipmentCost + otherCost}。
     * </ul>
     *
     * <p><b>诚实 null</b>: 订单未关联生产计划 / 批次未领料 / 未报工时该对象为 null,
     * 各组分 (materialCost/laborCost/overheadCost) 各自独立诚实 null —— 不伪造数字。
     *
     * <p>与现有聚合字段的关系 (不破坏): {@code actualCost} 仍按 SalesOrderItem.costUnitPrice
     * 聚合 (口径不变); 本对象是**独立新增**的"按生产真实领料/报工"视角的拆分,
     * 二者可能因口径不同而总额略有差异 (本对象给出 {@link ActualCostSplit#materialCost}
     * 等真实组分, actualCost 给出销售行回填的成本)。
     */
    private ActualCostSplit actualCostSplit;

    /** 行级成本明细. */
    private List<LineCostBreakdown> lines;

    /**
     * 实际成本三分拆分 (材料 / 人工 / 制费) + 逐料明细.
     *
     * <p>所有金额字段 @PriceSensitive — 非财务/管理角色脱敏为 null。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActualCostSplit {

        /** 材料成本合计 (= Σ materials[].amount). 无领料记录时 null. */
        @PriceSensitive
        private BigDecimal materialCost;

        /** 人工成本合计 (= Σ 关联批次 ProductionBatch.laborCost). 无报工记录时 null. */
        @PriceSensitive
        private BigDecimal laborCost;

        /** 制费合计 (= Σ 关联批次 equipmentCost + otherCost). 无数据时 null. */
        @PriceSensitive
        private BigDecimal overheadCost;

        /**
         * 实际成本合计 (= 非 null 组分之和). 三者全 null 时为 null.
         * 注: 与外层 {@link FinanceCostBreakdown#actualCost} 口径不同
         * (此处来自生产真实领料/报工, 外层来自销售行回填)。
         */
        @PriceSensitive
        private BigDecimal totalActualCost;

        /** 关联到的生产批次数 (供财务判断数据完整度; 0 表示订单未投产). */
        private Integer batchCount;

        /** 逐原料/辅料/包材实际领料明细. 无领料时空列表. */
        private List<MaterialCostLine> materials;

        /** 提示: 数据缺失场景的友好说明 (无关联计划 / 未领料 / 未报工). null 表示数据完整. */
        private String dataSourceHint;
    }

    /**
     * 逐料实际领料明细 (mirror MaterialConsumption 按物料聚合).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialCostLine {

        /** 物料类型 ID. */
        private String materialTypeId;

        /** 物料名称 (原料/辅料/包材). materialTypeId 查不到时回退为 ID. */
        private String materialName;

        /** 物料分类 (原料 / 辅料 / 包材 / ...). RawMaterialType.category, 可能 null. */
        private String category;

        /** 实际领料用量合计 (Σ MaterialConsumption.quantity). */
        private BigDecimal actualQuantity;

        /** 计量单位 (RawMaterialType.unit), 可能 null. */
        private String unit;

        /** 移动均价单价 (= amount / actualQuantity, 加权平均). */
        @PriceSensitive
        private BigDecimal unitPrice;

        /** 该物料实际金额合计 (Σ MaterialConsumption.totalCost). */
        @PriceSensitive
        private BigDecimal amount;
    }

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
