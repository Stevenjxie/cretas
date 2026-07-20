package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.PayableSettlementRequest;
import com.cretas.aims.dto.finance.PayableSettlementResult;
import com.cretas.aims.dto.finance.UnallocatedApPaymentDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.finance.PayableSettlementService;
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

@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/payables")
@RequiredArgsConstructor
@Tag(name = "应付核销", description = "财务专用的开放应付付款与核销")
@RequireModule("finance_ap")
@RequirePermission("finance:read_write")
public class PayableSettlementController {

    private final PayableSettlementService settlementService;
    private final MobileService mobileService;

    @PostMapping("/{payableTransactionId}/settlements")
    @Operation(summary = "付款并核销指定开放应付")
    public ApiResponse<PayableSettlementResult> settle(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String payableTransactionId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody PayableSettlementRequest request) {
        Long operatedBy = mobileService
                .getUserFromToken(TokenUtils.extractToken(authorization))
                .getId();
        PayableSettlementResult result = settlementService.settle(
                factoryId, payableTransactionId, request, operatedBy);
        return ApiResponse.success(result.isReplayed() ? "付款请求已处理" : "付款核销成功", result);
    }

    @GetMapping("/anomalies/unallocated-payments")
    @Operation(summary = "查询历史未分配付款（只读，不自动匹配）")
    public ApiResponse<List<UnallocatedApPaymentDTO>> listUnallocatedPayments(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success("查询成功", settlementService.listUnallocatedPayments(factoryId));
    }
}
