package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.restaurant.SupplierMonthlyReconciliationDto;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.restaurant.SupplierMonthlyReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/supplier-reconciliations")
@RequiredArgsConstructor
@Tag(name = "餐饮-供应商月对账")
@RequireModule("restaurant")
@RequireRole({"factory_super_admin", "platform_admin", "permission_admin",
        "restaurant_manager", "restaurant_owner",
        "finance_manager"})
public class SupplierMonthlyReconciliationController {

    private final SupplierMonthlyReconciliationService service;

    @GetMapping
    @Operation(summary = "供应商月对账列表")
    @RequirePermission({"finance:read", "finance:read_write"})
    public ApiResponse<PageResponse<SupplierMonthlyReconciliationDto>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String supplierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("查询成功", service.list(factoryId, supplierId, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "供应商月对账详情")
    @RequirePermission({"finance:read", "finance:read_write"})
    public ApiResponse<SupplierMonthlyReconciliationDto> detail(
            @PathVariable String factoryId,
            @PathVariable String id) {
        return ApiResponse.success("查询成功", service.getById(factoryId, id));
    }

    @PostMapping("/draft")
    @Operation(summary = "生成或刷新供应商月对账草稿",
            description = "按 factory + supplier + month 幂等生成；已确认记录直接返回，不重复创建")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<SupplierMonthlyReconciliationDto> draft(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody SupplierMonthlyReconciliationDto.DraftRequest request) {
        if (request == null || !StringUtils.hasText(request.getSupplierId())) {
            throw new BusinessException(400, "供应商不能为空")
                    .withHint("请选择要对账的供应商")
                    .withHintTarget("supplierId");
        }
        SupplierMonthlyReconciliationDto result = service.createOrRefreshDraft(
                factoryId, request.getSupplierId(), parseMonth(request.getMonth()), userId);
        return ApiResponse.success("月对账草稿已生成", result);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认供应商月对账")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<SupplierMonthlyReconciliationDto> confirm(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("月对账已确认", service.confirm(factoryId, id, userId));
    }

    private YearMonth parseMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new BusinessException(400, "月份格式无效: " + month)
                    .withHint("请使用 yyyy-MM 格式，例如 2026-06")
                    .withHintTarget("month");
        }
    }
}
