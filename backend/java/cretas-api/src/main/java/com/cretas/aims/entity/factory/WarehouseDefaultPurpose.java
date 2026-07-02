package com.cretas.aims.entity.factory;

/**
 * 工厂默认仓路由用途 (purpose) — 对应 {@link WarehouseCodes} 的三类硬编码默认仓。
 *
 * <p>{@link FactoryWarehouseDefault} 用此枚举把某工厂的某个 purpose 覆盖到自选仓库;
 * 未配置时 {@link com.cretas.aims.service.factory.WarehouseResolver} 回退到对应的
 * 硬编码 code (向后兼容)。
 */
public enum WarehouseDefaultPurpose {

    /**
     * 物流仓默认 — 调拨 / 退货 / 原料持久库存默认等「其余」语义 (对应 WH-LOG)。
     *
     * <p><b>用途拆分 (2026-07-02, warehouse-purpose-split Part A)</b>: 原本本 purpose 同时服务
     * 采购入库 / 销售出货 / 生产报工三个<b>冲突</b>语义 (配成原料仓会同时把销售 FIFO 和报工指错仓)。
     * 现已按调用点语义拆出 {@link #PURCHASE_INBOUND_DEFAULT} / {@link #SALES_OUTBOUND_DEFAULT} /
     * {@link #PRODUCTION_RAW_DEFAULT} 三个独立 purpose。本 purpose 仅保留给调拨/退货等其余 resolver
     * ({@code resolveLogisticsId}) 使用。
     */
    LOGISTICS_DEFAULT,

    /** 车间仓默认 — 生产成品 / 报工消耗 / 结算 (对应 WH-WKS)。 */
    WORKSHOP_DEFAULT,

    /** 研发/中试库默认 — 试制批次 (is_trial=true) 产出 (对应 WH-RD)。 */
    RD_DEFAULT,

    /**
     * 采购入库默认仓 — 采购收货物化 raw MaterialBatch 的落点
     * ({@code resolvePurchaseInboundWh}, 对应 {@code PurchaseServiceImpl})。未配置回退 WH-LOG。
     *
     * <p>让「采购进主仓/原料仓」可单独配置, 不影响销售出货 / 生产报工。
     */
    PURCHASE_INBOUND_DEFAULT,

    /**
     * 销售出货默认仓 — 成品发货 FIFO / 可用批次默认来源仓
     * ({@code resolveSalesOutboundWh}, 对应销售发货/批次分配)。未配置回退 WH-LOG。
     */
    SALES_OUTBOUND_DEFAULT,

    /**
     * 生产领料/报工原料默认仓 — 逐道报工消耗 raw 的来源仓
     * ({@code resolveProductionRawWh}, 对应 {@code ProcessSheetServiceImpl.ensureRawMaterialWarehouse})。
     * 未配置回退 WH-LOG。
     */
    PRODUCTION_RAW_DEFAULT
}
