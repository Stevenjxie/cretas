package com.cretas.aims.controller.operations;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CreateCustomerMaterialArrivalNoticeRequest;
import com.cretas.aims.dto.inventory.ReviewCustomerMaterialArrivalNoticeRequest;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.CustomerMaterialArrivalNoticeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Operations creates non-order inbound requests; warehouse remains the only inventory executor. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/operations/customer-material-arrivals")
@RequiredArgsConstructor
@Tag(name = "无订单入库申请", description = "运营登记客户来料、赠予或其他无订单到货来源")
public class CustomerMaterialArrivalNoticeController {

    private final CustomerMaterialArrivalNoticeService noticeService;
    private final MobileService mobileService;

    @GetMapping
    @RequirePermission({"operations:read", "operations:read_write", "warehouse:read", "warehouse:read_write"})
    @Operation(summary = "查询无订单入库申请")
    public ApiResponse<List<CustomerMaterialArrivalNotice>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return ApiResponse.success("查询成功", noticeService.list(factoryId, openOnly));
    }

    @PostMapping
    @RequireModule("operations")
    @RequirePermission({"operations:write", "operations:read_write"})
    @Operation(summary = "创建无订单入库申请（不创建库存）")
    public ApiResponse<CustomerMaterialArrivalNotice> create(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateCustomerMaterialArrivalNoticeRequest request) {
        return ApiResponse.success("无订单入库申请已提交给仓储",
                noticeService.create(factoryId, request, extractUserId(authorization)));
    }

    @PostMapping("/{noticeId}/cancel")
    @RequireModule("operations")
    @RequirePermission({"operations:write", "operations:read_write"})
    @Operation(summary = "取消尚未发生收货的无订单入库申请")
    public ApiResponse<CustomerMaterialArrivalNotice> cancel(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String noticeId) {
        return ApiResponse.success("无订单入库申请已取消",
                noticeService.cancel(factoryId, noticeId));
    }

    @PostMapping("/{noticeId}/approve")
    @RequireModule("warehouse")
    @RequirePermission({"warehouse:read_write", "warehouse:write"})
    @Operation(summary = "审批通过并交接为仓储入库任务")
    public ApiResponse<CustomerMaterialArrivalNotice> approve(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String noticeId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody(required = false) ReviewCustomerMaterialArrivalNoticeRequest request) {
        return ApiResponse.success("审批通过，已进入入库任务与批次",
                noticeService.approve(factoryId, noticeId, extractUserId(authorization),
                        request == null ? null : request.getRemark()));
    }

    @PostMapping("/{noticeId}/reject")
    @RequireModule("warehouse")
    @RequirePermission({"warehouse:read_write", "warehouse:write"})
    @Operation(summary = "驳回无订单入库申请")
    public ApiResponse<CustomerMaterialArrivalNotice> reject(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String noticeId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody(required = false) ReviewCustomerMaterialArrivalNoticeRequest request) {
        return ApiResponse.success("无订单入库申请已驳回",
                noticeService.reject(factoryId, noticeId, extractUserId(authorization),
                        request == null ? null : request.getRemark()));
    }

    private Long extractUserId(String authorization) {
        return mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
    }
}
