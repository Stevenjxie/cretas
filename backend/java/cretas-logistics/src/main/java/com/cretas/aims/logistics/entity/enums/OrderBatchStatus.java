package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_order_batches.status} — mirrors DB CHECK {@code ck_lob_status}
 * (V20261028_01). 值必须与 CHECK 列表逐字一致 (per feedback_findall_bad_enum_data_startup_crash)。
 */
public enum OrderBatchStatus {
    PREVIEWED,
    COMMITTED,
    PLANNED,
    CANCELLED
}
