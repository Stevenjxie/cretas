package com.cretas.aims.dto.yield;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单元 F (F006 REQ-21 张权 "以订单的模式呈现…分订单分产品分工序"):
 * 一张销售订单下全部生产批次的出成率聚合视图。
 *
 * <p>数据链: salesOrderId → ProductionPlan(source_order_id=orderId) → ProductionBatch(production_plan_id IN 计划ids)
 * → 每个批次复用 {@link BatchYieldDTO} (逐道工序链 + 累计出成率)。</p>
 *
 * <p><b>诚实聚合</b>: 总投入/总产出/整体出成率 仅在所有批次单位一致时计算; 单位不可比 (kg vs 份混合)
 * → 三者均为 null (不混算不可比单位)。成本 null-safe 累加 (全 null → null, 绝不默认 0)。
 * 订单无计划/批次 → 空批次列表 + batchCount 0 (诚实空态, 非异常)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderYieldSummaryDTO {

    /** 销售订单 ID (source_order_id)。 */
    private String orderId;

    /** 该订单下每个生产批次的出成率明细 (逐道工序链 + 累计出成率)。空订单 → 空 list。 */
    private List<BatchYieldDTO> batches;

    /** Σ 各批次首道总投入 (仅所有批次 firstStepInputUnit 一致时; 否则 null)。 */
    private BigDecimal totalFirstInput;

    /** Σ 各批次末道总产出 (仅所有批次 lastStepOutputUnit 一致时; 否则 null)。 */
    private BigDecimal totalLastOutput;

    /**
     * 订单整体出成率 = totalLastOutput / totalFirstInput (scale 4, HALF_UP)。
     * <p>单位不可比 或 totalFirstInput ≤ 0 → null (诚实, 不混算)。</p>
     */
    private BigDecimal overallYieldRate;

    /** 所有批次共用的首道投入单位 (一致时); 否则 null。 */
    private String firstInputUnit;

    /** 所有批次共用的末道产出单位 (一致时); 否则 null。 */
    private String lastOutputUnit;

    /** Σ 各批次人工成本 (null-safe; 全 null → null)。 */
    @PriceSensitive
    private BigDecimal totalLaborCost;

    /** Σ 各批次材料成本 (null-safe; 全 null → null)。 */
    @PriceSensitive
    private BigDecimal totalMaterialCost;

    /** Σ 各批次总成本 (null-safe; 全 null → null)。 */
    @PriceSensitive
    private BigDecimal totalCost;

    /** 该订单下生产批次数量。 */
    private int batchCount;
}
