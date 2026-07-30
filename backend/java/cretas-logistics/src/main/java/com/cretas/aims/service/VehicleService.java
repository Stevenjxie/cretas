package com.cretas.aims.service;

import com.cretas.aims.dto.warehouse.VehicleDTO;

import java.util.List;

/**
 * 车辆管理服务接口
 */
public interface VehicleService {

    /**
     * 获取工厂所有车辆
     */
    List<VehicleDTO> getVehicles(String factoryId);

    /**
     * 获取可用车辆列表
     */
    List<VehicleDTO> getAvailableVehicles(String factoryId);

    /**
     * 根据状态获取车辆
     */
    List<VehicleDTO> getVehiclesByStatus(String factoryId, String status);

    /**
     * 获取单个车辆详情
     */
    VehicleDTO getVehicle(String factoryId, String vehicleId);

    /**
     * 创建车辆
     */
    VehicleDTO createVehicle(String factoryId, VehicleDTO request);

    /**
     * 更新车辆
     */
    VehicleDTO updateVehicle(String factoryId, String vehicleId, VehicleDTO request);

    /**
     * 更新车辆状态
     */
    VehicleDTO updateVehicleStatus(String factoryId, String vehicleId, String status);

    /**
     * 更新装载量
     */
    VehicleDTO updateCurrentLoad(String factoryId, String vehicleId, java.math.BigDecimal load);

    /**
     * 删除车辆（软删除）
     */
    void deleteVehicle(String factoryId, String vehicleId);
}
