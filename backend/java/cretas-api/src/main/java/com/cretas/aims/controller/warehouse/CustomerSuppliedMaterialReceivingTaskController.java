package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceivingTaskResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Warehouse entry point for approved customer-supplied material requirements. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/warehouse/customer-supplied-receiving-tasks")
@Tag(name = "仓储-客供物料待收货任务")
public class CustomerSuppliedMaterialReceivingTaskController {

    private final SalesOrderSuppliedMaterialRequirementService requirementService;
    private final MobileService mobileService;

    @GetMapping
    @RequireModule("warehouse")
    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:write"})
    @Operation(summary = "查询已完成销售审批的客供物料待收货任务（只读）")
    public ApiResponse<List<CustomerSuppliedMaterialReceivingTaskResponse>> list(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(
                "查询成功", requirementService.getPendingReceivingTasks(factoryId));
    }

    @PostMapping("/{taskId}/receipts")
    @RequireModule("warehouse")
    @RequirePermission({"warehouse:read_write", "inventory:write"})
    @Operation(summary = "确认一笔客户来料收货并生成客户所有库存批次")
    public ApiResponse<MaterialBatchDTO> receive(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String taskId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CustomerSuppliedMaterialReceiptRequest request) {
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();
        return ApiResponse.success(
                "客户来料收货成功",
                requirementService.receive(factoryId, taskId, request, userId));
    }
}
