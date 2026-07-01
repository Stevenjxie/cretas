package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.service.wip.WipInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * C3 半成品重量库存视图控制器。
 *
 * <p>客户要求 "只做重量库存" — 本端点仅暴露重量字段 (producedQuantity / consumedQuantity /
 * availableQuantity / unit)，不暴露成本字段 (accumulatedCost / unitCost)。
 *
 * <p>内部 SemiFinishedInventoryTransaction 流水账 (powers 移动均价 + 撤回回放) 不通过本端点暴露，
 * 由业务层内部使用。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/semi-finished")
@Tag(name = "半成品重量库存", description = "C3 工厂级半成品重量库存视图 (仅重量, 不含成本)")
@RequiredArgsConstructor
public class SemiFinishedInventoryController {

    private final WipInventoryService wipInventoryService;

    /**
     * 工厂级半成品重量库存快照。
     *
     * <p>返回该工厂所有未软删 WIP 行，含全状态 (AVAILABLE/DEPLETED/RETURNED)，
     * 按工序序升序排列。每行含产品类型名称 ({@code productTypeName}) 和批次 ID ({@code batchId})
     * 供前端分组展示。
     *
     * <p><b>防呆过滤 (可选, 07-01 客户会议需求)</b>: 逐道录入 SFI 投料下拉传入以收窄可选半成品,
     * 防低素养操作员误选。两参数省略 → 全量快照 (向后兼容, 看板/盘点仍用无参调用):
     * <ul>
     *   <li>{@code productTypeId} — 同类过滤: 仅返回同产品半成品 (猪蹄计划不显牛肉)。</li>
     *   <li>{@code maxProcessOrder} — 阶段过滤: 仅返回 {@code processOrder < maxProcessOrder} 的更早阶段半成品 (防回锅)。</li>
     * </ul>
     */
    @RequirePermission({"production:read"})
    @GetMapping("/inventory")
    @Operation(summary = "工厂级半成品重量库存 (全状态快照, 仅重量字段; 可选 同类+阶段 防呆过滤)")
    public ApiResponse<List<WipRowDTO>> listInventory(
            @PathVariable String factoryId,
            @RequestParam(required = false) String productTypeId,
            @RequestParam(required = false) Integer maxProcessOrder) {
        List<WipRowDTO> rows = wipInventoryService.listWipByFactory(factoryId, productTypeId, maxProcessOrder);
        log.debug("[C3] listInventory factoryId={} productTypeId={} maxProcessOrder={} → {} rows",
                factoryId, productTypeId, maxProcessOrder, rows.size());
        return ApiResponse.success(rows);
    }
}
