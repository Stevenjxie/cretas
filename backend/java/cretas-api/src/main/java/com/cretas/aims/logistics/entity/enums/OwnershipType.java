package com.cretas.aims.logistics.entity.enums;

/**
 * 自有 / 外协 — shared by {@code logistics_vehicle_profiles.source} (CHECK
 * {@code ck_lvp_source}) and {@code logistics_drivers.employment_type} (CHECK
 * {@code ck_ld_emp}). Both CHECK lists are {@code ('OWNED','OUTSOURCED')} — identical
 * value sets, so one enum is reused across the two columns (V20261028_01).
 */
public enum OwnershipType {
    OWNED,
    OUTSOURCED
}
