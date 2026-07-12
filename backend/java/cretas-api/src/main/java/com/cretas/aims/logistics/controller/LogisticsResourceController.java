package com.cretas.aims.logistics.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.logistics.dto.resource.DailyAvailabilityDto;
import com.cretas.aims.logistics.dto.resource.DailyAvailabilityUpsertRequest;
import com.cretas.aims.logistics.dto.resource.DriverInputRequest;
import com.cretas.aims.logistics.dto.resource.LogisticsDriverDto;
import com.cretas.aims.logistics.dto.resource.LogisticsVehicleDto;
import com.cretas.aims.logistics.dto.resource.SetVehicleDriversRequest;
import com.cretas.aims.logistics.dto.resource.VehicleProfileUpdateRequest;
import com.cretas.aims.logistics.service.LogisticsResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 物流资源管理 (handoff §11.2) — 车辆物流 profile / 独立司机资料 / 一车多司机绑定。
 *
 * <p>factoryId 取自 {@code @PathVariable}，同 {@link LogisticsOrderImportController} 注释：
 * 全局 JWT 拦截器已保证 URL factoryId == token factoryId。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/logistics")
@RequiredArgsConstructor
@Tag(name = "物流-资源管理")
public class LogisticsResourceController {

    private final LogisticsResourceService resourceService;

    @GetMapping("/vehicles")
    @Operation(summary = "物流车辆列表", description = "车辆基础字段 + 物流 profile + 司机绑定合并读模型")
    public ApiResponse<List<LogisticsVehicleDto>> listVehicles(@PathVariable String factoryId) {
        return ApiResponse.success(resourceService.listVehicles(factoryId));
    }

    @RequireModule("scheduling")
    @PutMapping("/vehicles/{vehicleId}/profile")
    @Operation(summary = "更新车辆物流 profile", description = "不改 Vehicle.capacity(kg); partial update + 乐观锁")
    public ApiResponse<LogisticsVehicleDto> updateVehicleProfile(
            @PathVariable String factoryId,
            @PathVariable String vehicleId,
            @RequestBody VehicleProfileUpdateRequest request) {
        return ApiResponse.success("车辆信息已更新",
                resourceService.updateVehicleProfile(factoryId, vehicleId, request));
    }

    @GetMapping("/drivers")
    @Operation(summary = "司机列表", description = "含 inactive")
    public ApiResponse<List<LogisticsDriverDto>> listDrivers(@PathVariable String factoryId) {
        return ApiResponse.success(resourceService.listDrivers(factoryId));
    }

    @RequireModule("scheduling")
    @PostMapping("/drivers")
    @Operation(summary = "新建司机")
    public ApiResponse<LogisticsDriverDto> createDriver(
            @PathVariable String factoryId,
            @RequestBody DriverInputRequest request) {
        return ApiResponse.success("司机已创建", resourceService.createDriver(factoryId, request));
    }

    @RequireModule("scheduling")
    @PutMapping("/drivers/{driverId}")
    @Operation(summary = "更新司机", description = "partial update + 乐观锁")
    public ApiResponse<LogisticsDriverDto> updateDriver(
            @PathVariable String factoryId,
            @PathVariable String driverId,
            @RequestBody DriverInputRequest request) {
        return ApiResponse.success("司机信息已更新", resourceService.updateDriver(factoryId, driverId, request));
    }

    @RequireModule("scheduling")
    @PutMapping("/vehicles/{vehicleId}/drivers")
    @Operation(summary = "替换车辆司机绑定", description = "整体替换，支持一车 2-3 个司机（主/备 + 班次）")
    public ApiResponse<LogisticsVehicleDto> setVehicleDrivers(
            @PathVariable String factoryId,
            @PathVariable String vehicleId,
            @RequestBody SetVehicleDriversRequest request) {
        return ApiResponse.success("司机绑定已更新",
                resourceService.setVehicleDrivers(factoryId, vehicleId, request.getBindings()));
    }

    // ==================== 按日可用性覆盖 ====================

    @GetMapping("/daily-availability")
    @Operation(summary = "按日可用性覆盖列表", description = "只返回该日期存在覆盖的记录; 未出现的司机/车辆当天按固定资料默认可用")
    public ApiResponse<List<DailyAvailabilityDto>> listDailyAvailability(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(resourceService.listDailyAvailability(factoryId, date));
    }

    @RequireModule("scheduling")
    @PutMapping("/daily-availability")
    @Operation(summary = "更新按日可用性覆盖", description = "upsert (按 resourceType+resourceId+availDate 幂等); 请求体是完整覆盖状态, 非 partial patch")
    public ApiResponse<DailyAvailabilityDto> upsertDailyAvailability(
            @PathVariable String factoryId,
            @RequestBody DailyAvailabilityUpsertRequest request) {
        return ApiResponse.success("可用性已更新",
                resourceService.upsertDailyAvailability(factoryId, request));
    }

    @RequireModule("scheduling")
    @DeleteMapping("/daily-availability/{id}")
    @Operation(summary = "删除按日可用性覆盖", description = "软删除, 恢复该资源当天默认可用")
    public ApiResponse<Void> deleteDailyAvailability(
            @PathVariable String factoryId,
            @PathVariable String id) {
        resourceService.deleteDailyAvailability(factoryId, id);
        return ApiResponse.success("已删除", null);
    }
}
