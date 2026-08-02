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
    /**
     * BOM / 调料配方归属的<b>成品</b> SKU。
     *
     * <p>BOM 只挂成品 —— 中间半成品按设计就不该有 BOM({@code BomWorkflowRevisionService}
     * 要求 SKU 是 Workflow 终端产出, 中间产出永远不满足)。而 WIP 批次的
     * {@link #productTypeId} 是该道<b>产出的半成品</b>, 拿它查 BOM 必然落空 ——
     * 曾因此让熟制道每次报工都提示「未设置当前 BOM 调料配方, 调料成本暂记 0」,
     * 并把用户指向一个系统根本不允许配置的位置。
     *
     * <p>成品批次两者相同; WIP 批次这里是所属计划的成品 SKU。解析不到计划时回落
     * {@link #productTypeId}(与旧行为一致)。
     */
    private String recipeProductTypeId;
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
