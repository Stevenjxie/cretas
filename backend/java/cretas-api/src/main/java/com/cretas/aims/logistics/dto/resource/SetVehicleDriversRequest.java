package com.cretas.aims.logistics.dto.resource;

import lombok.Data;

import java.util.List;

/** {@code PUT /vehicles/{vehicleId}/drivers} 请求体 — 整体替换该车辆的司机绑定集合。 */
@Data
public class SetVehicleDriversRequest {
    private List<VehicleDriverBindingDto> bindings;
}
