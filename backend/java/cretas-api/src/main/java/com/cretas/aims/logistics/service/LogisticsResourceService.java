package com.cretas.aims.logistics.service;

import com.cretas.aims.logistics.dto.resource.DriverInputRequest;
import com.cretas.aims.logistics.dto.resource.LogisticsDriverDto;
import com.cretas.aims.logistics.dto.resource.LogisticsVehicleDto;
import com.cretas.aims.logistics.dto.resource.VehicleDriverBindingDto;
import com.cretas.aims.logistics.dto.resource.VehicleProfileUpdateRequest;

import java.util.List;

/**
 * 物流资源管理服务 (handoff §11.2) — 车辆物流 profile / 独立司机资料 / 车辆-司机绑定。
 *
 * <p>不改现有 {@link com.cretas.aims.entity.Vehicle}(kg 载重语义)，物流方数/来源/区域/班次
 * 落 {@link com.cretas.aims.logistics.entity.LogisticsVehicleProfile} 1:1 关联表 (spec §2 决策 5)。
 */
public interface LogisticsResourceService {

    /** GET /vehicles — 车辆基础字段 + 物流 profile 合并读模型 + 司机绑定。 */
    List<LogisticsVehicleDto> listVehicles(String factoryId);

    /** PUT /vehicles/{vehicleId}/profile — upsert 物流 profile（partial update + 乐观锁）。 */
    LogisticsVehicleDto updateVehicleProfile(String factoryId, String vehicleId, VehicleProfileUpdateRequest request);

    /** GET /drivers — 含 inactive（管理员维护列表需要看到全部）。 */
    List<LogisticsDriverDto> listDrivers(String factoryId);

    /** POST /drivers。 */
    LogisticsDriverDto createDriver(String factoryId, DriverInputRequest request);

    /** PUT /drivers/{driverId} — partial update + 乐观锁。 */
    LogisticsDriverDto updateDriver(String factoryId, String driverId, DriverInputRequest request);

    /** PUT /vehicles/{vehicleId}/drivers — 整体替换绑定集合（支持一车 2-3 个司机）。 */
    LogisticsVehicleDto setVehicleDrivers(String factoryId, String vehicleId, List<VehicleDriverBindingDto> bindings);
}
