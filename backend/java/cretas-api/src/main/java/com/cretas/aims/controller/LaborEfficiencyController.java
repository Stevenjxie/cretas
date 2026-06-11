package com.cretas.aims.controller;

import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyOrderAggregateDTO;
import com.cretas.aims.service.LaborEfficiencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SP9/SP3: 人工双口径对比 & 人效分析 API.
 *
 * <p>M3: GET /{fid}/labor-efficiency/compare — 返回批次级人工对比列表.</p>
 * <p>M3b: GET /{fid}/labor-efficiency/compare/order-aggregate — SP3 P1 成本组/公单级聚合.</p>
 */
@Tag(name = "LaborEfficiency", description = "SP9/SP3 人工效率对比分析")
@RestController
@RequestMapping("/api/mobile/{factoryId}/labor-efficiency")
@RequiredArgsConstructor
public class LaborEfficiencyController {

    private final LaborEfficiencyService laborEfficiencyService;

    /**
     * M3: 批次级人工双口径对比列表.
     *
     * <p>查询条件: factoryId + [startDate, endDate] + 可选 productTypeId.
     * 返回已完工批次的研发预估 vs 实际人工成本对比, 含逐工序分解.</p>
     *
     * @param factoryId     工厂 ID (路径参数)
     * @param startDate     开始日期 yyyy-MM-dd (必填)
     * @param endDate       结束日期 yyyy-MM-dd (必填)
     * @param productTypeId 产品类型 ID (可选; 不传 = 全部产品)
     * @return {@code { success: true, data: [...] }}
     */
    @Operation(summary = "人工双口径对比列表", description = "SP9-M3: 批次级研发预估 vs 实际人工成本对比")
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> getLaborEfficiencyComparison(
            @PathVariable String factoryId,
            @Parameter(description = "开始日期 yyyy-MM-dd", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 yyyy-MM-dd", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "产品类型 ID (可选)")
            @RequestParam(required = false) String productTypeId) {

        List<LaborEfficiencyCompareDTO> result = laborEfficiencyService.getLaborEfficiencyComparison(
                factoryId, startDate, endDate, productTypeId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", result);
        response.put("message", "查询成功");
        return ResponseEntity.ok(response);
    }

    /**
     * M3b: SP3 P1 成本组/公单级人工双口径聚合.
     *
     * <p>将同一销售订单下的多批次人工成本归拢, 展示订单级研发预估 vs 实际人工汇总.</p>
     *
     * @param factoryId     工厂 ID (路径参数)
     * @param startDate     开始日期 yyyy-MM-dd (必填)
     * @param endDate       结束日期 yyyy-MM-dd (必填)
     * @param productTypeId 产品类型 ID (可选; 不传 = 全部产品)
     * @return {@code { success: true, data: [...] }}
     */
    @Operation(summary = "成本组/公单级人工双口径聚合", description = "SP3-P1-M3b: 将多批次人工成本归拢到销售订单级")
    @GetMapping("/compare/order-aggregate")
    public ResponseEntity<Map<String, Object>> getLaborCostOrderAggregate(
            @PathVariable String factoryId,
            @Parameter(description = "开始日期 yyyy-MM-dd", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 yyyy-MM-dd", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "产品类型 ID (可选)")
            @RequestParam(required = false) String productTypeId) {

        List<LaborEfficiencyOrderAggregateDTO> result =
                laborEfficiencyService.getLaborCostOrderAggregate(factoryId, startDate, endDate, productTypeId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", result);
        response.put("message", "查询成功");
        return ResponseEntity.ok(response);
    }
}
