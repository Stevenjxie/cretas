package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

/** {@code PUT /plans/{planId}/trips/{tripId}/vehicle} 请求体。 */
@Data
public class SetVehicleRequest {
    /** null = 解除车辆分配（车次退回 NEEDS_VEHICLE）。 */
    private String vehicleId;
    private Long version;
}
