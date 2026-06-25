package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.FinishedBatchSummaryDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SP-C: 按批次号查出成率 + 成本拆分 (存货生产无订单号场景).
 *
 * <p>路径含 {@code {factoryId}} 段 — JwtAuthInterceptor 工厂守卫自动校验 token.factoryId == path.factoryId。
 * service 层均调用 {@code findByFactoryIdAndBatchNumber} (factory-scoped) — 跨租户安全。
 *
 * <p>脱敏策略与 by-order 保持一致: maskPrice = procurement:price:view 门控
 * (OrderCostBreakdownDTO 是强类型 DTO, 已防止裸 Map NPE 等泄漏)。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/batches")
@Tag(name = "批次出成率与成本", description = "SP-C — 按批次号查出成率 / 单盒成本 (存货生产无订单号场景)")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProductionBatchCostController {

    private final YieldReportService yieldReportService;
    private final OrderCostBreakdownService orderCostBreakdownService;
    private final PriceMaskResolver priceMaskResolver;
    private final ProductionBatchRepository productionBatchRepository;

    /**
     * 按批次号查出成率.
     *
     * <p>多租户安全: factory guard (path {factoryId}) + service findByFactoryIdAndBatchNumber。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/{batchNumber}/yield-summary")
    @Operation(summary = "按批次号查出成率 (存货生产无订单号)")
    public ApiResponse<BatchYieldDTO> getBatchYield(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "批次号, 如 PB-20260622-XXXXX") String batchNumber) {
        return ApiResponse.success(yieldReportService.getBatchYieldByNumber(factoryId, batchNumber));
    }

    /**
     * 按批次号查单盒成本拆分.
     *
     * <p>多租户安全: factory guard (path {factoryId}) + service findByFactoryIdAndBatchNumber。
     * 价格按 procurement:price:view 权限脱敏 (与 by-order endpoint 一致)。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/{batchNumber}/cost-breakdown")
    @Operation(summary = "按批次号查单盒成本拆分 (存货生产无订单号)")
    public ApiResponse<OrderCostBreakdownDTO> getBatchCostBreakdown(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "批次号, 如 PB-20260622-XXXXX") String batchNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(orderCostBreakdownService.computeByBatch(factoryId, batchNumber, maskPrice));
    }

    /**
     * 成品出厂核算 — 已完工批次列表 (供前端下拉选择).
     *
     * <p>路径: GET /api/mobile/{factoryId}/production/batches/finished
     * <p>多租户安全: JwtAuthInterceptor factory guard (path factoryId) + WHERE factory_id = :factoryId。
     * <p>成本字段不暴露 (仅返回是否 settled 布尔值), 无需 finance:read 门控。
     *
     * @param factoryId     工厂 ID
     * @param days          查询最近 N 天, 默认 90
     * @param productTypeId 产品类型过滤 (可选)
     */
    @RequirePermission({"production:read"})
    @GetMapping("/finished")
    @Operation(summary = "已完工批次列表 — 成品出厂核算批次选择下拉数据源")
    public ApiResponse<List<FinishedBatchSummaryDTO>> listFinishedBatches(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "90") @Parameter(description = "查询最近天数 (默认90)") int days,
            @RequestParam(required = false) @Parameter(description = "产品类型ID过滤 (可选)") String productTypeId) {

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = productionBatchRepository.findFinishedBatchSummaries(factoryId, since, productTypeId);

        List<FinishedBatchSummaryDTO> result = rows.stream().map(r -> {
            BigDecimal totalCost = r[7] != null ? new BigDecimal(r[7].toString()) : null;
            return FinishedBatchSummaryDTO.builder()
                    .batchNumber(r[0] != null ? r[0].toString() : null)
                    .orderId(r[1] != null ? r[1].toString() : null)
                    .productName(r[2] != null ? r[2].toString() : null)
                    .plannedQty(r[3] != null ? new BigDecimal(r[3].toString()) : null)
                    .actualQty(r[4] != null ? new BigDecimal(r[4].toString()) : null)
                    .unit(r[5] != null ? r[5].toString() : null)
                    .completedAt(r[6] != null ? ((java.sql.Timestamp) r[6]).toLocalDateTime() : null)
                    .settled(totalCost != null && totalCost.compareTo(BigDecimal.ZERO) > 0)
                    .build();
        }).collect(Collectors.toList());

        return ApiResponse.success(result);
    }
}
