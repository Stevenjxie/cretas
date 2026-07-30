package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.warehouse.VehicleDTO;
import com.cretas.aims.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 车辆管理控制器
 * 用于仓库装车管理
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/vehicles")
@RequiredArgsConstructor
@Tag(name = "车辆管理", description = "车辆信息管理和装车状态跟踪")
public class VehicleController {

    private final VehicleService vehicleService;

    @RequirePermission({"warehouse:read"})
    @GetMapping
    @Operation(summary = "获取车辆列表", description = "获取工厂所有车辆，支持按状态筛选")
    public ApiResponse<List<VehicleDTO>> getVehicles(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆状态筛选（available/loading/dispatched/maintenance）")
            @RequestParam(required = false) String status) {
        log.info("获取车辆列表: factoryId={}, status={}", factoryId, status);
        List<VehicleDTO> vehicles;
        if (status != null && !status.isEmpty()) {
            vehicles = vehicleService.getVehiclesByStatus(factoryId, status);
        } else {
            vehicles = vehicleService.getVehicles(factoryId);
        }
        return ApiResponse.success("获取成功", vehicles);
    }

    @RequirePermission({"warehouse:read"})
    @GetMapping("/available")
    @Operation(summary = "获取可用车辆", description = "获取当前可用于装载的车辆列表")
    public ApiResponse<List<VehicleDTO>> getAvailableVehicles(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId) {
        log.info("获取可用车辆: factoryId={}", factoryId);
        List<VehicleDTO> vehicles = vehicleService.getAvailableVehicles(factoryId);
        return ApiResponse.success("获取成功", vehicles);
    }

    @RequirePermission({"warehouse:read"})
    @GetMapping("/{vehicleId}")
    @Operation(summary = "获取车辆详情", description = "根据ID获取单个车辆的详细信息")
    public ApiResponse<VehicleDTO> getVehicle(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆ID")
            @PathVariable String vehicleId) {
        log.info("获取车辆详情: factoryId={}, vehicleId={}", factoryId, vehicleId);
        VehicleDTO vehicle = vehicleService.getVehicle(factoryId, vehicleId);
        return ApiResponse.success("获取成功", vehicle);
    }

    @RequirePermission({"warehouse:read_write"})
    @PostMapping
    @Operation(summary = "创建车辆", description = "添加新车辆到系统")
    public ApiResponse<VehicleDTO> createVehicle(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @RequestBody VehicleDTO request) {
        log.info("创建车辆: factoryId={}, plateNumber={}", factoryId, request.getPlateNumber());
        VehicleDTO vehicle = vehicleService.createVehicle(factoryId, request);
        return ApiResponse.success("创建成功", vehicle);
    }

    @RequirePermission({"warehouse:read_write"})
    @PutMapping("/{vehicleId}")
    @Operation(summary = "更新车辆", description = "更新车辆的基本信息")
    public ApiResponse<VehicleDTO> updateVehicle(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆ID")
            @PathVariable String vehicleId,
            @RequestBody VehicleDTO request) {
        log.info("更新车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);
        VehicleDTO vehicle = vehicleService.updateVehicle(factoryId, vehicleId, request);
        return ApiResponse.success("更新成功", vehicle);
    }

    @RequirePermission({"warehouse:read_write"})
    @PatchMapping("/{vehicleId}/status")
    @Operation(summary = "更新车辆状态", description = "更新车辆的运行状态（可用/装载中/已发车/维护中）")
    public ApiResponse<VehicleDTO> updateVehicleStatus(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆ID")
            @PathVariable String vehicleId,
            @Parameter(description = "新状态")
            @RequestParam String status) {
        log.info("更新车辆状态: factoryId={}, vehicleId={}, status={}", factoryId, vehicleId, status);
        VehicleDTO vehicle = vehicleService.updateVehicleStatus(factoryId, vehicleId, status);
        return ApiResponse.success("状态更新成功", vehicle);
    }

    @RequirePermission({"warehouse:read_write"})
    @PatchMapping("/{vehicleId}/load")
    @Operation(summary = "更新装载量", description = "更新车辆当前的装载重量")
    public ApiResponse<VehicleDTO> updateCurrentLoad(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆ID")
            @PathVariable String vehicleId,
            @Parameter(description = "装载量（kg）")
            @RequestParam BigDecimal load) {
        log.info("更新装载量: factoryId={}, vehicleId={}, load={}", factoryId, vehicleId, load);
        VehicleDTO vehicle = vehicleService.updateCurrentLoad(factoryId, vehicleId, load);
        return ApiResponse.success("装载量更新成功", vehicle);
    }

    @RequirePermission({"warehouse:read_write"})
    @DeleteMapping("/{vehicleId}")
    @Operation(summary = "删除车辆", description = "软删除车辆")
    public ApiResponse<Void> deleteVehicle(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "车辆ID")
            @PathVariable String vehicleId) {
        log.info("删除车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);
        vehicleService.deleteVehicle(factoryId, vehicleId);
        return ApiResponse.success("删除成功", null);
    }
}
