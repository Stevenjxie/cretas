package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

/** {@code PUT /plans/{planId}/trips/{tripId}/driver} 请求体。 */
@Data
public class SetDriverRequest {
    /** null = 解除司机分配（车次退回 NEEDS_DRIVER，前提车辆已定）。 */
    private String driverId;
    private Long version;
}
