package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一车多司机绑定项 (PRIMARY/BACKUP + 班次) — 镜像前端 {@code web-admin/src/api/logistics.ts}
 * {@code LogisticsVehicleDriverBinding}. 用于 {@code PUT /vehicles/{vehicleId}/drivers}
 * 的请求体元素和 {@code LogisticsVehicle.drivers} 响应元素（复用同一形状）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleDriverBindingDto {
    private String driverId;
    private String driverName;
    private DriverRole role;
    private String shiftStart;
    private String shiftEnd;
    private Integer priority;
}
