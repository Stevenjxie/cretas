package com.cretas.aims.repository.inventory;

import java.math.BigDecimal;

/**
 * 备货看板需求聚合投影。
 *
 * <p>每行代表一种产品在指定交货日期的汇总需求量。
 * {@code minUnit ≠ maxUnit} 表示该产品存在跨行单位不一致 (F2 flag)，
 * 需在 Service 层标记 {@code unitInconsistent=true}。
 */
public interface ProductDemandProjection {
    String getProductTypeId();
    String getProductName();
    /** MIN(unit) — 若等于 maxUnit 则单位一致。 */
    String getMinUnit();
    /** MAX(unit) — 若不等于 minUnit 则存在单位不一致 (F2)。 */
    String getMaxUnit();
    BigDecimal getDemand();
}
