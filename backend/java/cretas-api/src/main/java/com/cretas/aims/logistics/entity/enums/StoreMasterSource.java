package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_store_master.source} — 门店主数据坐标的来源, 用于审计/展示 (V20261028_58)。
 */
public enum StoreMasterSource {
    /** 通过 {@link com.cretas.aims.logistics.service.routing.AmapClient#geocode} 自动解析。 */
    GEOCODED,
    /** 调度员在门店主数据管理页手工录入/修正 (最终事实来源, 后续导入不再覆盖)。 */
    MANUAL,
    /** 导入行本身已携带经纬度 (文件列提供 / 手动录入表单填写), 直接采信落地。 */
    IMPORT
}
