package com.cretas.aims.logistics.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.logistics.dto.plan.ConfirmRequest;
import com.cretas.aims.logistics.dto.plan.GeneratePlanRequest;
import com.cretas.aims.logistics.dto.plan.MoveStopRequest;
import com.cretas.aims.logistics.dto.plan.PlanDto;
import com.cretas.aims.logistics.dto.plan.PlanSnapshotDto;
import com.cretas.aims.logistics.dto.plan.ReorderStopsRequest;
import com.cretas.aims.logistics.dto.plan.SetDriverRequest;
import com.cretas.aims.logistics.dto.plan.SetVehicleRequest;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.service.LogisticsPlanService;
import com.cretas.aims.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 物流排线计划 — 生命周期 + 人工调整 + 确认状态机 (handoff §11.3; spec §6 Phase 4).
 *
 * <p>factoryId 一律取自 {@code @PathVariable}（同 {@link LogisticsOrderImportController} /
 * {@link LogisticsResourceController} 注释：全局 JWT 拦截器已保证 URL factoryId 与 JWT
 * factoryId 一致，不在 body 里信任/重复传 factoryId）。
 *
 * <p>全部端点 {@code @RequireModule("scheduling")}（含 GET —— 排线计划属于调度模块的业务数据，
 * 未开通 scheduling 模块的工厂不应读到）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/logistics")
@RequiredArgsConstructor
@RequireModule("scheduling")
@Tag(name = "物流-排线计划")
public class LogisticsPlanController {

    private final LogisticsPlanService planService;

    @PostMapping("/plans/generate")
    @Operation(summary = "生成排线计划", description = "对已提交批次跑确定性排线算法；同批次幂等（已有非取消计划直接返回）")
    public ApiResponse<PlanSnapshotDto> generatePlan(
            @PathVariable String factoryId,
            @RequestBody GeneratePlanRequest request) {
        return ApiResponse.success("计划已生成",
                planService.generatePlan(factoryId, request.getBatchId(), request.getTargetLoadPct()));
    }

    @GetMapping("/plans")
    @Operation(summary = "计划列表", description = "工厂内计划分页，最新业务日期在前；status 可选过滤")
    public ApiResponse<Page<PlanDto>> listPlans(
            @PathVariable String factoryId,
            @RequestParam(required = false) PlanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.success(
                planService.listPlans(factoryId, status, PageRequest.of(Math.max(page, 0), safeSize)));
    }

    @GetMapping("/plans/{planId}")
    @Operation(summary = "计划详情", description = "完整快照：plan + trips + stops + 未分配门店")
    public ApiResponse<PlanSnapshotDto> getPlan(
            @PathVariable String factoryId,
            @PathVariable String planId) {
        return ApiResponse.success(planService.getPlan(factoryId, planId));
    }

    @PostMapping("/plans/{planId}/regenerate")
    @Operation(summary = "重新生成计划", description = "丢弃当前全部人工调整，重新跑排线算法；已确认计划不可重生成")
    public ApiResponse<PlanSnapshotDto> regeneratePlan(
            @PathVariable String factoryId,
            @PathVariable String planId) {
        return ApiResponse.success("计划已重新生成", planService.regeneratePlan(factoryId, planId));
    }

    @PutMapping("/plans/{planId}/trips/{tripId}/stops/reorder")
    @Operation(summary = "同车次内重排停靠顺序", description = "storeIds 为该车次现有门店的完整排列（deliveryOrderId 列表）；后端重算分段公里数")
    public ApiResponse<PlanSnapshotDto> reorderStops(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @PathVariable String tripId,
            @RequestBody ReorderStopsRequest request) {
        return ApiResponse.success("顺序已更新",
                planService.reorderStops(factoryId, planId, tripId, request.getStoreIds(), request.getVersion()));
    }

    @PostMapping("/plans/{planId}/trips/{tripId}/stops/move")
    @Operation(summary = "跨车次移动门店", description = "targetTripId=null 时新建待匹配车次；重新校验目标车次容量/重量，源车次为空时软删除")
    public ApiResponse<PlanSnapshotDto> moveStop(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @PathVariable String tripId,
            @RequestBody MoveStopRequest request) {
        return ApiResponse.success("门店已移动", planService.moveStop(factoryId, planId, tripId, request));
    }

    @PutMapping("/plans/{planId}/trips/{tripId}/vehicle")
    @Operation(summary = "重新分配车次车辆", description = "vehicleId=null 解除分配；重新校验容量/重量/区域/同计划内车辆冲突")
    public ApiResponse<PlanSnapshotDto> setTripVehicle(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @PathVariable String tripId,
            @RequestBody SetVehicleRequest request) {
        return ApiResponse.success("车辆已更新",
                planService.setTripVehicle(factoryId, planId, tripId, request.getVehicleId(), request.getVersion()));
    }

    @PutMapping("/plans/{planId}/trips/{tripId}/driver")
    @Operation(summary = "重新分配车次司机", description = "driverId=null 解除分配；重新校验区域/班次/同计划内司机冲突")
    public ApiResponse<PlanSnapshotDto> setTripDriver(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @PathVariable String tripId,
            @RequestBody SetDriverRequest request) {
        return ApiResponse.success("司机已更新",
                planService.setTripDriver(factoryId, planId, tripId, request.getDriverId(), request.getVersion()));
    }

    @PostMapping("/plans/{planId}/trips/{tripId}/confirm")
    @Operation(summary = "确认车次", description = "车次必须无 NEEDS_VEHICLE/NEEDS_DRIVER/NEEDS_ROUTE_DATA 才能确认；重复确认幂等")
    public ApiResponse<PlanSnapshotDto> confirmTrip(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @PathVariable String tripId,
            @RequestBody(required = false) ConfirmRequest request) {
        Long version = request == null ? null : request.getVersion();
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success("车次已确认", planService.confirmTrip(factoryId, planId, tripId, version, userId));
    }

    @PostMapping("/plans/{planId}/confirm")
    @Operation(summary = "正式确认计划", description = "全部车次已确认且零未分配门店才能确认；重复确认幂等")
    public ApiResponse<PlanSnapshotDto> confirmPlan(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @RequestBody(required = false) ConfirmRequest request) {
        Long version = request == null ? null : request.getVersion();
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success("计划已确认", planService.confirmPlan(factoryId, planId, version, userId));
    }
}
