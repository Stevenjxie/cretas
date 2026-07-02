package com.cretas.aims.entity.factory;

/**
 * 工厂默认仓路由用途 (purpose) — 对应 {@link WarehouseCodes} 的三类硬编码默认仓。
 *
 * <p>{@link FactoryWarehouseDefault} 用此枚举把某工厂的某个 purpose 覆盖到自选仓库;
 * 未配置时 {@link com.cretas.aims.service.factory.WarehouseResolver} 回退到对应的
 * 硬编码 code (向后兼容)。
 */
public enum WarehouseDefaultPurpose {

    /** 物流仓默认 — 采购入库 / 销售出货 / 原料持久库存默认 / 调拨 / 退货 / 领料 (对应 WH-LOG)。 */
    LOGISTICS_DEFAULT,

    /** 车间仓默认 — 生产成品 / 报工消耗 / 结算 (对应 WH-WKS)。 */
    WORKSHOP_DEFAULT,

    /** 研发/中试库默认 — 试制批次 (is_trial=true) 产出 (对应 WH-RD)。 */
    RD_DEFAULT
}
