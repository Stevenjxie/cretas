package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 六扇门 D1 延伸: 每 SKU 一行的「三价对比」看板行 — 标准BOM成本 vs 销售价 vs 实际成本。
 *
 * <p><b>复用</b>: 超支报警引擎 ({@code OrderCostAlarmListener}) 同款
 * {@code StandardCostService.resolveStandardUnitCost} (同口径标准=料+研发预估人工) +
 * {@code CostVarianceService} (超支阈值 + 方差计算)。此 DTO 只是把已在生产中运行的
 * 报警口径以「看板」形式暴露给财审/销售主管按 SKU 逐一查看, 不重复造轮子。
 *
 * <p><b>价格脱敏</b>: 与 {@link OrderCostSummaryRowDTO} / {@link OrderCostBreakdownDTO} 同一
 * 显式 maskPrice 模式 (由 {@code PriceMaskResolver.shouldMaskPrice} 在 controller 解析,
 * service 内按需将金额字段置 null) — 不用 {@code @PriceSensitive} 反射注解 (这些是聚合派生值,
 * 不是直接回显的 entity 字段, 与本包其余 cost DTO 手法一致)。
 *
 * <p><b>诚实 null</b>: 标准成本口径不全 (无 BOM / 无研发预估人工) → standardCost null,
 * caliberHint 说明缺什么; 从无完工批次算出 unitCost → actualCost null (不臆造 0)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreePriceSkuDTO {

    private String productTypeId;
    private String productName;
    private String productCode;
    private String productCategory;
    /** 产品计量单位 (kg/盒/份/...); 三个价格字段均以此单位口径比较. */
    private String unit;

    // ---- 三价 (masked 时 null) ----

    /** 标准BOM成本 = 料(BomRecipe.totalMaterialCost) + 研发预估标准人工, 同口径可比. */
    private BigDecimal standardCost;
    /** 销售价 (未税, ProductType.unitPrice). */
    private BigDecimal salesPrice;
    /** 含税销售价 (ProductType.taxIncludedUnitPrice). */
    private BigDecimal taxIncludedSalesPrice;
    /** 实际成本 = 最近一条有 unitCost 的完工批次单位成本. */
    private BigDecimal actualCost;

    // ---- 派生指标 (masked 时 null) ----

    /** 超支百分比 = (actualCost - standardCost) / standardCost × 100; 任一价缺失 → null. */
    private BigDecimal variancePct;
    /** 该 SKU 生效的超支预警阈值 (%, 产品专属 → 工厂全局 → 系统默认 10%). */
    private BigDecimal threshold;
    /** 毛利率 % = (salesPrice - actualCost) / salesPrice × 100; 任一价缺失/salesPrice<=0 → null. */
    private BigDecimal grossMargin;

    /** variancePct > threshold. 标准成本口径不全 (variancePct=null) 时恒 false (诚实不报警, 与推送引擎一致). */
    private Boolean overBudget;

    /** 标准成本口径说明 (含人工/缺什么), 直接透传 StandardCostService.caliberHint. */
    private String caliberHint;

    /** 实际成本取数来源批次号 (透明度: 让财审知道这条"实际"来自哪一批). */
    private String actualCostAsOfBatchNumber;
    /** 实际成本取数批次的完成/创建时间. */
    private LocalDateTime actualCostAsOf;
}
