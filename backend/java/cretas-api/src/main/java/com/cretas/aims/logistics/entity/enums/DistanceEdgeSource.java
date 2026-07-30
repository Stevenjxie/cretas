package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_distance_edges.source} — mirrors DB CHECK {@code ck_lde_source}
 * (V20261028_01).
 *
 * <p>⚠️ Not the same value set as {@link PlanDistanceSource}
 * ({@code logistics_plans.distance_source}) — edge uses {@code CUSTOMER_MAINTAINED}
 * where plan uses {@code MAINTAINED_MATRIX}. Two separate enums, do not merge.
 */
public enum DistanceEdgeSource {
    CUSTOMER_MAINTAINED,
    MAP_PROVIDER,
    DEMO_FIXTURE
}
