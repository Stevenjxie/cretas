package com.cretas.aims.controller.operations;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CreateCustomerMaterialArrivalNoticeRequest;
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

/** Operations creates coordination documents; warehouse remains the only inventory executor. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/operations/customer-material-arrivals")
@RequiredArgsConstructor
@RequireModule("operations")
@Tag(name = "运营客户来料预告", description = "无销售订单时登记客户将送料的协调来源单")
public class CustomerMaterialArrivalNoticeController {

    private final CustomerMaterialArrivalNoticeService noticeService;
    private final MobileService mobileService;

    @GetMapping
    @RequirePermission({"operations:read", "operations:read_write"})
    @Operation(summary = "查询客户来料预告")
    public ApiResponse<List<CustomerMaterialArrivalNotice>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return ApiResponse.success("查询成功", noticeService.list(factoryId, openOnly));
    }

    @PostMapping
    @RequirePermission({"operations:write", "operations:read_write"})
    @Operation(summary = "创建客户来料预告（不创建库存）")
    public ApiResponse<CustomerMaterialArrivalNotice> create(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateCustomerMaterialArrivalNoticeRequest request) {
        return ApiResponse.success("客户来料预告已提交给仓储",
                noticeService.create(factoryId, request, extractUserId(authorization)));
    }

    @PostMapping("/{noticeId}/cancel")
    @RequirePermission({"operations:write", "operations:read_write"})
    @Operation(summary = "取消尚未发生收货的客户来料预告")
    public ApiResponse<CustomerMaterialArrivalNotice> cancel(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String noticeId) {
        return ApiResponse.success("客户来料预告已取消",
                noticeService.cancel(factoryId, noticeId));
    }

    private Long extractUserId(String authorization) {
        return mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
    }
}
