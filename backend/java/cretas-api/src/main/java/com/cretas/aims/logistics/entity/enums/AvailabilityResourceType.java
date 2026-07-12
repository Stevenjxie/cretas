package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_daily_availability.resource_type} — 按日可用性覆盖记录指向的资源种类
 * (V20261028_57)。
 */
public enum AvailabilityResourceType {
    /** 指向 {@link com.cretas.aims.logistics.entity.LogisticsDriver#getId()}。 */
    DRIVER,
    /** 指向 {@code vehicles.id} (与 {@link com.cretas.aims.logistics.entity.LogisticsVehicleProfile#getVehicleId()} 同语义)。 */
    VEHICLE
}
