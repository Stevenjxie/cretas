package com.cretas.aims.repository.inventory;

import java.math.BigDecimal;

/**
 * P3 多仓备货看板需求聚合投影 — 按产品 + 目的仓分组。
 *
 * <p>每行代表一种产品在一个目的仓的汇总需求量。
 * {@code destWarehouseCode} 为 COALESCE(null, '未分仓')，确保未设置目的仓的行
 * 不被丢弃，而是归入"未分仓"桶 (fool-proof: 防老数据漏算)。
 *
 * <p>{@code minUnit ≠ maxUnit} 表示该产品+仓组合存在单位不一致 (F2 flag)。
 */
public interface ProductWarehouseDemandProjection {
    String getProductTypeId();
    String getProductName();
    /** COALESCE(destWarehouseCode, '未分仓') — 永远非 null。 */
    String getDestWarehouseCode();
    /** 目的仓全名; MIN(destWarehouseName) 聚合结果, 展示用。可能为 null (旧行未填)。 */
    String getDestWarehouseName();
    /** MIN(unit) — 若等于 maxUnit 则单位一致。 */
    String getMinUnit();
    /** MAX(unit) — 若不等于 minUnit 则存在单位不一致 (F2)。 */
    String getMaxUnit();
    BigDecimal getDemand();
}
