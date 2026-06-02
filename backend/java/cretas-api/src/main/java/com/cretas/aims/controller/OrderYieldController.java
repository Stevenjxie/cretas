package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.OrderYieldSummaryDTO;
import com.cretas.aims.service.yield.YieldReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单元 F (F006 REQ-21 张权 "以订单的模式呈现…分订单分产品分工序"):
 * 订单级出成率聚合 — 一张销售订单下全部生产批次的出成率汇总。
 *
 * <p>路径与按批次的 {@link YieldReportController} (基路径 .../batches/{batchId}) 分离,
 * 挂在订单级 .../orders/{orderId} 之下, 保持 URL 语义清晰。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/orders")
@Tag(name = "订单出成率", description = "单元 F — 分订单聚合出成率 (复用逐道工序链 + 累计出成率)")
@RequiredArgsConstructor
@RequireModule("production_report")
public class OrderYieldController {

    private final YieldReportService yieldReportService;

    @RequirePermission({"production:read"})
    @GetMapping("/{orderId}/yield-summary")
    @Operation(summary = "订单整体出成率 (该订单下全部批次聚合)")
    public ApiResponse<OrderYieldSummaryDTO> getOrderYieldSummary(
            @PathVariable String factoryId,
            @PathVariable String orderId) {
        return ApiResponse.success(yieldReportService.getOrderYieldSummary(factoryId, orderId));
    }
}
