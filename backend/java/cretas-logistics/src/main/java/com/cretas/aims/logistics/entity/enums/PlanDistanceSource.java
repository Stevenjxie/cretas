package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_plans.distance_source} — mirrors DB CHECK {@code ck_lp_dist_src}
 * (V20261028_01).
 *
 * <p>⚠️ Not the same value set as {@link DistanceEdgeSource}
 * ({@code logistics_distance_edges.source}) — plan uses {@code MAINTAINED_MATRIX}
 * where edge uses {@code CUSTOMER_MAINTAINED}. Two separate enums, do not merge.
 */
public enum PlanDistanceSource {
    MAINTAINED_MATRIX,
    MAP_PROVIDER,
    DEMO_FIXTURE
}
