package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单批物化上下文 (SP-F Task 1.3) —— 所有依赖由 <b>调用方</b>预先解析。
 *
 * <p>{@code materializeBatch(ctx, steps, edges)} 的写逻辑只读 ctx, 不再做任何
 * resolution (上游/仓库/工时单价 全部 hoisted out 由 caller 传入)。
 */
@Data
@AllArgsConstructor
public class MaterializeContext {
    private String factoryId;
    /** null 表示 WIP(非成品)批次 —— 不挂 plan, 避免 OrderCostBreakdownService 双计。 */
    private String planId;
    private String productTypeId;
    /** 可空: createProductionBatch 为 null/blank 时自动生成 (CLK-W-/CLK-B- 前缀)。 */
    private String batchNumber;
    private boolean finished;
    /** 预解析 (resolveLaborRate) —— hoisted out。 */
    private BigDecimal laborRate;
    /** 预解析 (resolveWarehouseId) —— hoisted out, WIP 产出落此仓。 */
    private String warehouseId;
    /**
     * WIP 产出 MaterialBatch 的 material_type_id FK —— 必须指向 raw_material_types
     * (SP-E bug class: 曾误用 product_types id 造成无效 FK)。可空(无 raw lineage)。
     */
    private String rawMaterialTypeId;
    private Long userId;
}
