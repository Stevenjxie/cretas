package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.MaterialBusinessCodeBackfillReportDTO;

/** Controlled, factory-scoped mapping of legacy 16-digit material codes to business codes. */
public interface MaterialBusinessCodeBackfillService {

    /** Build a read-only report using the same L3/prefix resolver as material creation. */
    MaterialBusinessCodeBackfillReportDTO preview(String factoryId);

    /**
     * Assign missing codes only. Existing business codes and legacy codes are never overwritten.
     * Repeating the operation is a no-op for rows already mapped.
     */
    MaterialBusinessCodeBackfillReportDTO backfill(String factoryId, String idempotencyKey);
}
